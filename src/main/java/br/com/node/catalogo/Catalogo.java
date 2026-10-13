package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.nucleo.EstadoV3;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.ReservasV2;
import br.com.node.vo.Log;

// Guarda o estado de cada pool na altura do cursor.
//
// Só entra aqui o que a cadeia disse, e cada valor carrega a altura em que vale. Não
// há campo de confiança, não há "estimada", não há idade: se a cobertura do cursor é
// contínua até a cabeça, TODO valor daqui vale na cabeça — tenha mudado no último
// bloco ou há seis meses.
//
// Era isso que o projeto anterior não conseguia afirmar, e por não conseguir inventou
// `reserva_mais_velha`, `FORA_DA_JANELA`, `ConfiancaRpcEnum` e o cache anelar.
public final class Catalogo {

	private final Map<String, ReservasV2> v2 = new ConcurrentHashMap<>();
	private final Map<String, EstadoV3> v3 = new ConcurrentHashMap<>();

	// As pools de dentro de um gerente (V4, Infinity CL), pelo `poolId` — 32 bytes, e é o TAMANHO que as
	// separa das pools de endereço (20). O `poolId` não se repete entre os dois gerentes: o do Infinity põe
	// o próprio gerente no hash da chave. Só entram em `pools()` as de chave conhecida e modelável: sem o
	// `PoolKey` não se sabe o par, o espaçamento nem os hooks.
	private final Map<String, EstadoNoGerente> noGerente = new ConcurrentHashMap<>();

	// O `PoolKey` das pools de gerente — SÓ das que mexeram (ou nasceram) enquanto o node está no ar. Há
	// ~400 mil pools nos dois gerentes (varredura de 25/09) e quase todas mortas; guardar todas custaria
	// memória para nada. A que falta se PEDE a quem sabe (o banco), uma vez, sem travar o cursor.
	private final Map<String, ChaveDaPool> chaves = new ConcurrentHashMap<>();
	private final java.util.Set<String> pedidas = ConcurrentHashMap.newKeySet();
	private volatile java.util.function.BiConsumer<byte[], byte[]> aoFaltarChave = (gerente, poolId) -> {
	};
	private volatile java.util.function.Consumer<ChaveDaPool> aoNascer = chave -> {
	};

	// O par de cada pool, aprendido dos `Transfer` do próprio recibo: quem transfere de
	// ou para o endereço da pool é token dela. Sem banco, sem eth_call, sem factory.
	private final Map<String, Par> pares = new ConcurrentHashMap<>();

	// Pool cujo estado ficou impossível (V3: palavra do Mint/liquidez; V2: reserva fora do uint112 ou
	// zero), e quantas vezes. Contar em vez de calar: depois de corrigida a palavra do Mint isto deve
	// ficar em zero, e número acima de zero aqui é defeito de decodificação voltando, ou — no V2 —
	// contrato alheio emitindo o topic0 do `Sync` com payload inventado.
	private final Map<String, Long> descartados = new ConcurrentHashMap<>();

	// Quantas vezes cada pool moveu preço (`Sync`/`Swap`). Não é estado da pool — é o SINAL de onde o
	// fluxo passa, para a sondagem provar primeiro a pool que mais mexe (é lá que a fenda nasce). Só
	// conta, não decide nada aqui; quem prioriza lê isto.
	private final Map<String, Long> atividade = new ConcurrentHashMap<>();

	public Optional<ReservasV2> reservas(byte[] pool) {
		return Optional.ofNullable(v2.get(chave(pool)));
	}

	public Optional<EstadoV3> estado(byte[] pool) {

		if (deGerente(pool)) {
			return Optional.ofNullable(noGerente.get(chave(pool))).map(EstadoNoGerente::estado);
		}

		return Optional.ofNullable(v3.get(chave(pool)));
	}

	// O par da pool de gerente vem da CHAVE, com o BNB nativo lido como WBNB: é em WBNB que o grafo, as
	// pools de endereço e o empréstimo falam, e o Executor embrulha na borda. A pool nativo/WBNB não tem
	// par — ali as duas pontas seriam o mesmo token.
	public Optional<Par> par(byte[] pool) {

		if (deGerente(pool)) {
			return Optional.ofNullable(chaves.get(chave(pool))).flatMap(Catalogo::parDoGerente);
		}

		return Optional.ofNullable(pares.get(chave(pool)));
	}

	// OS LADOS da pool: token0 e token1 na ordem em que ELA calcula o preço (√P = token1 por token0). Não é
	// o `Par`, que ordena por endereço — e a diferença só aparece na pool de gerente com BNB nativo: a
	// moeda 0 é o nativo (endereço zero), lida como WBNB (0xbb4c…) ela pode passar para trás do outro token
	// no `Par`. Na nativo/USDT o `Par` diz token0 = USDT, e a pool diz token0 = BNB. Derivar a direção do
	// `Par` ali inverteria o preço inteiro — fenda fantasma do tamanho do preço do BNB. Quem pergunta
	// "qual lado entra" pergunta AQUI; quem pergunta "que tokens a pool tem" pergunta ao `par`.
	public record Lados(byte[] token0, byte[] token1) {
	}

	public Optional<Lados> lados(byte[] pool) {

		if (deGerente(pool)) {
			return Optional.ofNullable(chaves.get(chave(pool))).flatMap(chave -> {
				byte[] token0 = Arrays.equals(chave.token0(), NATIVO) ? WBNB : chave.token0();
				byte[] token1 = Arrays.equals(chave.token1(), NATIVO) ? WBNB : chave.token1();
				return Arrays.equals(token0, token1) ? Optional.empty() : Optional.of(new Lados(token0, token1));
			});
		}

		return par(pool).map(par -> new Lados(par.token0(), par.token1()));
	}

	static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] NATIVO = new byte[20];

	private static Optional<Par> parDoGerente(ChaveDaPool chave) {

		byte[] token0 = Arrays.equals(chave.token0(), NATIVO) ? WBNB : chave.token0();
		byte[] token1 = Arrays.equals(chave.token1(), NATIVO) ? WBNB : chave.token1();

		return Arrays.equals(token0, token1) ? Optional.empty() : Optional.of(new Par(token0, token1));
	}

	// Registra o par quando e só quando dois tokens distintos apareceram. Um token só
	// não identifica a pool, e três significam que atribuímos a quem não era pool.
	public void aprenderPar(byte[] pool, List<byte[]> tokensVistos) {

		if (tokensVistos.size() != 2) {
			return;
		}

		Par par = new Par(tokensVistos.get(0), tokensVistos.get(1));

		if (pares.putIfAbsent(chave(pool), par) == null) {
			poolsPorPar.computeIfAbsent(par, p -> ConcurrentHashMap.newKeySet()).add(chave(pool));
		}
	}

	// As pools de ENDEREÇO de cada par, na ordem em que o par foi aprendido. Existe para o roteamento da
	// mempool, que achava a pool de cada perna de um swap pendente VARRENDO o catálogo inteiro — lista refeita
	// na hora, com o tarifário consultado pool a pool — em cada um dos ~212 mil swaps de uma noite, com o
	// catálogo passando de 27 mil pools. Aqui é uma consulta.
	private final Map<Par, java.util.Set<String>> poolsPorPar = new ConcurrentHashMap<>();

	public List<byte[]> poolsDoPar(byte[] tokenA, byte[] tokenB) {

		java.util.Set<String> pools = poolsPorPar.get(new Par(tokenA, tokenB));

		if (pools == null) {
			return List.of();
		}

		List<byte[]> lista = new ArrayList<>(pools.size());
		pools.forEach(pool -> lista.add(HexFormat.of().parseHex(pool)));
		return lista;
	}

	// Aprende o par de cada pool CONHECIDA a partir dos `Transfer` do bloco: quem transfere de ou
	// para o endereço de uma pool é token dela. É trabalho do catálogo, não do cursor — o cursor
	// só avança o bloco; enriquecer a propriedade da pool é daqui. Uma passada sobre os logs que o
	// cursor já separou por transação, e o `Transfer` vem ANTES do `Sync`, então se colhe o bloco
	// inteiro e se atribui no fim, quando já se sabe quem é pool.
	public void inferirPares(List<List<Log>> porTransacao) {

		Map<String, Map<String, byte[]>> candidatos = new HashMap<>();

		for (List<Log> logs : porTransacao) {
			for (Log log : logs) {

				if (!log.assinadoPor(Eventos.TRANSFER) || log.topicos().size() < 3) {
					continue;
				}

				for (int ponta = 1; ponta <= 2; ponta++) {

					byte[] endereco = Eventos.enderecoDoTopico(log, ponta);

					if (endereco.length == 20) {
						candidatos.computeIfAbsent(HexFormat.of().formatHex(endereco), chave -> new HashMap<>())
								.put(HexFormat.of().formatHex(log.endereco()), log.endereco());
					}
				}
			}
		}

		candidatos.forEach((endereco, tokens) -> {

			byte[] pool = HexFormat.of().parseHex(endereco);

			if (conhece(pool)) {
				aprenderPar(pool, new ArrayList<>(tokens.values()));
			}
		});
	}

	// Todas as pools que o fluxo mostrou, V2 e V3 juntas. Quem monta o grafo precisa
	// PERCORRER o catálogo, não consultá-lo pool a pool: consultar exige já saber o
	// endereço, e saber o endereço é justamente o que só o catálogo tem.
	public List<byte[]> pools() {

		List<byte[]> todas = new ArrayList<>();

		v2.keySet().forEach(pool -> todas.add(HexFormat.of().parseHex(pool)));
		v3.keySet().forEach(pool -> todas.add(HexFormat.of().parseHex(pool)));
		noGerente.keySet().forEach(pool -> {
			ChaveDaPool chave = chaves.get(pool);
			if (chave != null && chave.modelavel()) {
				todas.add(HexFormat.of().parseHex(pool));
			}
		});

		return todas;
	}

	public int paresConhecidos() {
		return pares.size();
	}

	public boolean conhece(byte[] pool) {
		return v2.containsKey(chave(pool)) || v3.containsKey(chave(pool)) || noGerente.containsKey(chave(pool));
	}

	// Pool de gerente é a de identidade de 32 bytes (`poolId`); as de endereço têm 20.
	public static boolean deGerente(byte[] pool) {
		return pool.length == 32;
	}

	public Optional<ChaveDaPool> chaveDaPool(byte[] poolId) {
		return Optional.ofNullable(chaves.get(chave(poolId)));
	}

	// A taxa que o último `Swap` da pool COBROU (identidade + protocolo), e não a do `PoolKey`.
	public Optional<Integer> taxaNoGerente(byte[] poolId) {
		return Optional.ofNullable(noGerente.get(chave(poolId))).map(EstadoNoGerente::taxa);
	}

	public int poolsV2() {
		return v2.size();
	}

	// Quantas vezes a pool moveu preço desde que a vimos — o sinal de prioridade da sondagem.
	public long atividade(byte[] pool) {
		return atividade.getOrDefault(chave(pool), 0L);
	}

	public int poolsV3() {
		return v3.size();
	}

	// Devolve o estado ANTERIOR, porque quem prova taxa precisa da transição, não do
	// estado. Devolver só o novo forçaria o consumidor a guardar uma cópia — e cópia
	// de estado em dois lugares é como a terceira altura nasceu no projeto anterior.
	//
	// O estado só entra se for POSSÍVEL, como no V3. Um par UniswapV2 guarda cada reserva num
	// `uint112` — o próprio par estoura em `Pancake: OVERFLOW` quando o saldo passaria disso.
	// Logo valor ACIMA do teto não é estado de par: é log inventado, e log é dado
	// NÃO-AUTENTICADO — qualquer contrato emite o topic0 do `Sync` com o payload que quiser.
	// MEDIDO: numa corrida de 9 h os 7 despachos morreram TODOS em `Pancake: OVERFLOW`, e a
	// varredura dos `Sync` reais da BSC não achou reserva acima de 7,1e26 (0,00001% do teto) —
	// o número que estourou o par não veio de par nenhum.
	//
	// ZERO ENTRA, e isso é CICATRIZ do projeto anterior, com número. A regra de lá era
	// `reserva > 0` ("zero não é reserva pequena, é pool sem lastro") e ela custou caro: quando a
	// liquidez é RETIRADA o par emite `Sync(0,0)`, que é o fato mais FORTE que existe sobre aquela
	// pool, e nós o recusávamos — ficando para sempre com a última reserva não-nula. MEDIDO lá: a
	// pool 08bb0ff2… guardava 22,1 WBNB e 307 milhões de tokens como FATO enquanto a cadeia
	// devolvia 0 e 0, e o grafo via 19% a 24% de retorno em duas pernas — o valor de uma pool que
	// não existe mais. Aqui o resultado é o mesmo: o fantasma vira fenda inventada, que é pior que
	// fenda nenhuma. Quem recusa pool sem lastro é a CURVA, que não se constrói com x = 0, e ela
	// recusa no lugar certo (`Cotador` não cota, `ligarV2` não liga).
	public Optional<ReservasV2> aplicarSync(Log log, long altura) {

		String pool = chave(log.endereco());

		// `Sync(uint112,uint112)` emite DUAS palavras, sempre. Payload mais curto não é um Sync — e
		// esta guarda passou a ser OBRIGATÓRIA quando o ZERO virou estado legítimo: `palavra` devolve
		// ZERO para a palavra AUSENTE, então sem ela um log truncado entraria como `Sync(0,0)` e
		// APAGARIA a reserva boa da pool. É a porta de trás do mesmo erro que a plausibilidade tapa na
		// frente.
		if (log.dados() == null || log.dados().length < 64) {
			descartados.merge(pool, 1L, Long::sum);
			return Optional.empty();
		}

		BigInteger reserva0 = Eventos.palavra(log, 0);
		BigInteger reserva1 = Eventos.palavra(log, 1);

		if (!plausivel(reserva0) || !plausivel(reserva1)) {
			descartados.merge(pool, 1L, Long::sum);
			return Optional.empty();
		}

		ReservasV2 antes = v2.get(pool);

		v2.put(pool, new ReservasV2(reserva0, reserva1, altura));
		atividade.merge(pool, 1L, Long::sum);

		return Optional.ofNullable(antes);
	}

	// O teto que o PRÓPRIO par impõe às reservas: `uint112`. Acima disto o par reverteria com
	// OVERFLOW, então não existe estado legítimo ali.
	private static final BigInteger TETO_UINT112 = BigInteger.TWO.pow(112).subtract(BigInteger.ONE);

	// A mesma dúvida do V3, aplicada ao V2: em vez de aceitar e cotar sobre lixo, recusa e CONTA.
	// O teto é a ÚNICA recusa: zero passa (é par sem lastro, não log inventado — quem não cota é a
	// curva), e negativo é impossível de existir num campo sem sinal, então nem se pergunta.
	private static boolean plausivel(BigInteger reserva) {
		return reserva.signum() >= 0 && reserva.compareTo(TETO_UINT112) <= 0;
	}

	// Log com estado impossível é DESCARTADO, não corrigido e não gravado.
	//
	// A recusa fica mesmo depois de achada a causa do 1,41e48 — que era a palavra do Mint lida
	// no lugar errado, não fork nenhum. Ela é barata, exata, e é o único filtro que DUVIDA: gas e
	// retorno só sabem comparar, e aquele estado atravessou os dois.
	public void aplicarSwapV3(Log log, long altura) {

		try {

			EstadoV3 depois = new EstadoV3(Eventos.palavra(log, 2), Eventos.palavra(log, 3),
					(int) Eventos.palavraComSinal(log, 4), altura);
			EstadoV3 antes = v3.put(chave(log.endereco()), depois);
			atividade.merge(chave(log.endereco()), 1L, Long::sum);

			EstadoV3.taxaEntre(antes, depois, Eventos.palavraComSinalGrande(log, 0), Eventos.palavraComSinalGrande(log, 1))
					.ifPresent(taxa -> taxasObservadasV3.merge(chave(log.endereco()), new int[] { taxa, -1 },
							(velhas, nova) -> new int[] { nova[0], velhas[0] }));

		} catch (IllegalArgumentException impossivel) {
			descartados.merge(chave(log.endereco()), 1L, Long::sum);
		}
	}

	public int poolsDescartadas() {
		return descartados.size();
	}

	// As duas últimas taxas que cada V3 COBROU (`EstadoV3.taxaEntre`), a mais nova primeiro.
	private final Map<String, int[]> taxasObservadasV3 = new ConcurrentHashMap<>();

	// A taxa de uma V3 pelo que ela cobrou — só quando as duas últimas deduções BATEM (±1 milionésimo, o
	// arredondamento). Pool de taxa dinâmica troca de taxa de um swap para o outro e fica de fora: cotar com a
	// taxa de ontem é fenda fantasma.
	public Optional<Integer> taxaObservadaV3(byte[] pool) {

		int[] ultimas = taxasObservadasV3.get(chave(pool));

		return ultimas != null && ultimas[1] >= 0 && Math.abs(ultimas[0] - ultimas[1]) <= 1 ? Optional.of(ultimas[0])
				: Optional.empty();
	}

	public boolean taxaV3JaVista(byte[] pool) {
		return taxasObservadasV3.containsKey(chave(pool));
	}

	public Optional<EstadoNoGerente> estadoNoGerente(byte[] gerente, byte[] poolId) {
		return Optional.ofNullable(noGerente.get(chave(poolId))).filter(e -> Arrays.equals(e.gerente(), gerente));
	}

	// Quantas pools deste gerente já mostraram estado.
	public int poolsNoGerente(byte[] gerente) {

		return (int) noGerente.values().stream().filter(e -> Arrays.equals(e.gerente(), gerente)).count();
	}

	// O `Swap` do gerente traz o estado ABSOLUTO depois da troca (palavras 2 a 4, as mesmas do V3
	// deslocadas) e a taxa cobrada (palavra 5). Quem chama já conferiu o emissor.
	public void aplicarSwapDeGerente(Log log, long altura) {

		String pool = chave(log.topicos().get(1));

		try {

			noGerente.put(pool,
					new EstadoNoGerente(log.endereco(), new EstadoV3(Eventos.palavra(log, 2), Eventos.palavra(log, 3),
							(int) Eventos.palavraComSinal(log, 4), altura), Eventos.palavra(log, 5).intValueExact()));
			atividade.merge(pool, 1L, Long::sum);

			if (!chaves.containsKey(pool) && pedidas.add(pool)) {
				aoFaltarChave.accept(log.endereco(), log.topicos().get(1));
			}

		} catch (IllegalArgumentException | ArithmeticException impossivel) {
			descartados.merge(pool, 1L, Long::sum);
		}
	}

	// A pool que nasceu agora: a chave entra na memória e segue para quem guarda.
	public void aplicarInicializacao(Log log, long altura) {
		ChaveDaPool.doInitialize(log, altura).ifPresent(chave -> {
			aprenderChave(chave);
			aoNascer.accept(chave);
		});
	}

	// A resposta de quem sabe (o banco), ou a pool que nasceu ao vivo.
	public void aprenderChave(ChaveDaPool chave) {

		String pool = chave(chave.poolId());

		chaves.put(pool, chave);
		pedidas.remove(pool);
	}

	// Quem sabe NÃO sabia (a varredura ainda não passou pelo nascimento dela). A pool fica como pedida —
	// pedir de novo a cada swap seria uma ida ao banco por swap para a mesma resposta — até a varredura
	// avançar, quando todas as sem resposta podem ser pedidas outra vez.
	public void liberarPedidas() {
		pedidas.removeIf(pool -> !chaves.containsKey(pool));
	}

	public Optional<ChaveDaPool> chave(byte[] gerente, byte[] poolId) {
		return Optional.ofNullable(chaves.get(chave(poolId))).filter(c -> Arrays.equals(c.gerente(), gerente));
	}

	public void aoFaltarChave(java.util.function.BiConsumer<byte[], byte[]> aoFaltarChave) {
		this.aoFaltarChave = aoFaltarChave;
	}

	// Quem mantém os mapas de ticks: cada aporte ou retirada de liquidez, com as duas pontas e o delta com sinal.
	@FunctionalInterface
	public interface MudancaDeLiquidez {
		void aoMudar(byte[] pool, int inferior, int superior, BigInteger delta, long altura);
	}

	private volatile MudancaDeLiquidez aoMudarLiquidez = (pool, inferior, superior, delta, altura) -> {
	};

	public void aoMudarLiquidez(MudancaDeLiquidez aoMudarLiquidez) {
		this.aoMudarLiquidez = aoMudarLiquidez;
	}

	public void aoNascer(java.util.function.Consumer<ChaveDaPool> aoNascer) {
		this.aoNascer = aoNascer;
	}

	// Por gerente: pools com estado visto, com chave conhecida, e MODELÁVEIS (estado + chave sem hook e de
	// taxa fixa) — a última é a que diz quanto do fluxo dos gerentes o grafo poderá ver.
	public Map<String, Long> resumoDoGerente(byte[] gerente) {

		long comEstado = 0;
		long comChave = 0;
		long modelaveis = 0;
		long hookForaDoSwap = 0;

		for (Map.Entry<String, EstadoNoGerente> pool : noGerente.entrySet()) {

			if (!Arrays.equals(pool.getValue().gerente(), gerente)) {
				continue;
			}

			comEstado++;
			ChaveDaPool chave = chaves.get(pool.getKey());

			if (chave != null) {
				comChave++;
				modelaveis += chave.modelavel() ? 1 : 0;
				hookForaDoSwap += chave.hookForaDoSwap() ? 1 : 0;
			}
		}

		Map<String, Long> resumo = new java.util.LinkedHashMap<>();
		resumo.put("com_estado", comEstado);
		resumo.put("com_chave", comChave);
		resumo.put("modelaveis", modelaveis);
		resumo.put("hook_fora_do_swap", hookForaDoSwap);

		return resumo;
	}

	// A mesma regra do Mint/Burn do V3 — só mexe na liquidez ATIVA a faixa que cobre o tick corrente —,
	// com os ticks lidos do CORPO. Provado em 25/09 com logs reais: nos 4 casos de faixa cobrindo o tick,
	// o swap seguinte trouxe exatamente liquidez anterior + delta; nos 6 de faixa fora, a mesma liquidez.
	public void aplicarLiquidezDeGerente(Log log, long altura) {

		String pool = chave(log.topicos().get(1));
		EstadoNoGerente atual = noGerente.get(pool);

		long tickInferior = Eventos.palavraComSinal(log, 0);
		long tickSuperior = Eventos.palavraComSinal(log, 1);

		aoMudarLiquidez.aoMudar(log.topicos().get(1), (int) tickInferior, (int) tickSuperior,
				Eventos.palavraComSinalGrande(log, 2), altura);

		if (atual == null || atual.estado().tick() < tickInferior || atual.estado().tick() >= tickSuperior) {
			return;
		}

		EstadoV3 antes = atual.estado();

		// Negativo aqui é o mesmo caso do V3: posição aberta antes da nossa janela, tirando mais do que
		// sabemos que havia. Não sabemos a liquidez — e dizer isso é remover, não zerar.
		try {

			noGerente.put(pool, new EstadoNoGerente(atual.gerente(), new EstadoV3(antes.sqrtPrecoX96(),
					antes.liquidez().add(Eventos.palavraComSinalGrande(log, 2)), antes.tick(), altura), atual.taxa()));

		} catch (IllegalArgumentException impossivel) {

			noGerente.remove(pool);

			descartados.merge(pool, 1L, Long::sum);
		}
	}

	// Mint e Burn só mexem na liquidez ATIVA quando a faixa cobre o tick corrente.
	// Fora dela o aporte fica guardado para quando o preço chegar lá — e como não
	// temos mapa de ticks, fora da faixa a gente simplesmente não sabe, e não finge.
	//
	// O evento se decodifica AQUI e não em quem chama: a posição do `amount` muda entre Mint e
	// Burn, e foi exatamente essa posição, espalhada no chamador, que somou endereço à liquidez.
	public void aplicarLiquidezV3(Log log, long altura) {

		EstadoV3 atual = v3.get(chave(log.endereco()));

		long tickInferior = Eventos.tickDoTopico(log, 2);
		long tickSuperior = Eventos.tickDoTopico(log, 3);

		// O mapa de ticks quer TODO aporte, dentro ou fora da faixa corrente: fora dela é o `liquidityNet` que o
		// preço vai encontrar quando atravessar.
		aoMudarLiquidez.aoMudar(log.endereco(), (int) tickInferior, (int) tickSuperior, Eventos.liquidezMovida(log), altura);

		if (atual == null || atual.tick() < tickInferior || atual.tick() >= tickSuperior) {
			return;
		}

		// Liquidez negativa é possível aqui e NÃO é erro do contrato: a nossa base de liquidez
		// começa no primeiro `Swap` que vimos, então um `Burn` de posição aberta antes da nossa
		// janela pode tirar mais do que sabemos que havia. Zerar seria inventar um número;
		// descartar é dizer "não sabemos a liquidez desta pool", que é a verdade.
		try {

			v3.put(chave(log.endereco()), new EstadoV3(atual.sqrtPrecoX96(),
					atual.liquidez().add(Eventos.liquidezMovida(log)), atual.tick(), altura));

		} catch (IllegalArgumentException impossivel) {

			v3.remove(chave(log.endereco()));

			descartados.merge(chave(log.endereco()), 1L, Long::sum);
		}
	}

	private static String chave(byte[] endereco) {
		return HexFormat.of().formatHex(endereco);
	}
}
