package br.com.node.ensaio;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.Quarentena;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.PoolV3;
import br.com.node.nucleo.Travessia;

// Diz QUAL perna mentiu, e de que jeito.
//
// O veredito do contrato para no fim da rota: "NaoPagou(10,78; 7,39)" diz que o ciclo devolveu 68,6%
// do principal onde o modelo prometia +25%, e não diz onde os 56 pontos sumiram. Esta classe roda a
// MESMA rota pela `Autopsia.sol` — o mesmo flash swap, as mesmas pernas, no estado real, via
// `eth_call` com o código trocado no endereço do Executor — e recebe de volta o que a cadeia entregou
// em cada perna.
//
// Quando a sonda produz laudo, a comparação isola a culpa: para cada perna, o modelo é recalculado com
// a entrada que a CADEIA produziu até ali, não com a do plano. Assim o erro de uma perna não contamina
// as seguintes, e a culpada é a que diverge sozinha. A sonda atual não executa flash loan com ponta V3;
// nesse caso o erro dela não é um laudo da execução real e não pode condenar a pool. E a classe do erro
// sai do próprio laudo:
//   - REVERTEU:        a perna reverteu (motivo cru no laudo);
//   - TAXA_SUBESTIMADA: a perna V2 reverteu pelo invariante (`K`): pedimos a saída com a nossa taxa e o
//                      par cobra mais — o caso da 7a3eb6e7 (5 bps provados, 10% cobrados de nós);
//   - IMPOSTO:         chegou à pool menos do que mandamos, ou a nós menos do que a pool mandou;
//   - ESTADO:          as reservas/preço que o modelo usou não são as da cadeia;
//   - CURVA_OU_TAXA:   mesmo estado, sem imposto, e a saída é outra — a conta do modelo está errada
//                      (taxa inferida errada, curva, V3 cruzando tick que o modelo não vê).
//
// Não está no caminho de envio: roda na thread de autópsia do `Disparo`, depois do veredito.
public final class Autopsia implements Closeable {

	private static final Logger LOG = Logger.getLogger(Autopsia.class.getName());

	private static final String CODIGO = carregarCodigo();

	private static final byte[] AUTOPSIAR = AbiDoExecutor
			.seletor("autopsiar((address,address,address,uint8,uint16)[],uint256)");
	private static final String LAUDO = HexFormat.of().formatHex(AbiDoExecutor
			.seletor("Laudo(uint256[],uint256[],uint256[],uint256[],uint256[],uint256[],uint256,bytes,bytes4)"));
	private static final String EMPRESTIMO_NAO_V2 = "7978d88b";

	// Os callbacks de flash swap que o Executor IMPLEMENTA. A pool que chama outro
	// nome faz a rota
	// real reverter sem dado nenhum — o "0x" do veredito.
	private static final Set<String> CALLBACKS_DO_EXECUTOR = Set.of(
			HexFormat.of().formatHex(AbiDoExecutor.seletor("uniswapV2Call(address,uint256,uint256,bytes)")),
			HexFormat.of().formatHex(AbiDoExecutor.seletor("pancakeCall(address,uint256,uint256,bytes)")),
			HexFormat.of().formatHex(AbiDoExecutor.seletor("BiswapCall(address,uint256,uint256,bytes)")),
			HexFormat.of().formatHex(AbiDoExecutor.seletor("swapV2Call(address,uint256,uint256,bytes)")));

	// Tolerâncias, em pontos-base. A perna é culpada acima de TOLERANCIA_BPS de
	// saída a menos; o
	// estado e o imposto contam como causa acima de SINAL_BPS. Uma arb vive de 10 a
	// 25 bps, então
	// 30 já é o dobro do edge — abaixo disso é arredondamento e corrida, não
	// modelo.
	static final long TOLERANCIA_BPS = 30;
	static final long SINAL_BPS = 10;

	// Pena da pool culpada. ESTADO fica mais: se o cursor não atualizou a pool até
	// agora, é provável
	// que os eventos dela não estejam sendo lidos, e ela voltaria fantasma em meia
	// hora.
	private static final Duration PENA = Duration.ofMinutes(30);
	static final Duration PENA_DE_PROIBICAO = Duration.ofDays(30);
	static final Duration PENA_DE_ESTADO = Duration.ofHours(3);

	public enum Classe {
		OK, REVERTEU, TAXA_SUBESTIMADA, IMPOSTO, ESTADO, CURVA_OU_TAXA, SEM_MODELO
	}

	record Laudo(List<BigInteger> estadoA, List<BigInteger> estadoB, List<BigInteger> enviado, List<BigInteger> entrou,
			List<BigInteger> prometido, List<BigInteger> chegou, int falhouEm, String motivo, String callback) {

		boolean passou(int perna) {
			return falhouEm < 0 || perna < falhouEm;
		}
	}

	record Perna(int indice, String pool, boolean v3, int taxaBps, Classe classe, long desvioBps, long estadoBps,
			long impostoVendaBps, long impostoCompraBps) {
	}

	private final RpcDoEnsaio rpc;
	private final Quarentena quarentena;
	private final Impostos impostos;
	private final Path arquivo;
	// `serializeNulls`: "a cadeia não chegou ao fim" é um null que diz alguma
	// coisa, e o Gson o omitia.
	private final Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();

	private final Map<String, AtomicLong> porClasse = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> culpadas = new ConcurrentHashMap<>();
	private final AtomicLong examinadas = new AtomicLong();
	private final AtomicLong semLaudo = new AtomicLong();
	private final AtomicLong estadoDoBlocoIndisponivel = new AtomicLong();
	private final AtomicLong fechariam = new AtomicLong();
	private final AtomicLong callbackDesconhecido = new AtomicLong();

	private BufferedWriter saida;
	private volatile java.util.function.BiConsumer<String, Map<String, Object>> aoConcluir = (hash, resultado) -> {
	};

	public Autopsia(RpcDoEnsaio rpc, Quarentena quarentena, Impostos impostos, Path arquivo) {
		this.rpc = rpc;
		this.quarentena = quarentena;
		this.impostos = impostos;
		this.arquivo = arquivo;
	}

	public void aoConcluir(java.util.function.BiConsumer<String, Map<String, Object>> ouvinte) {
		this.aoConcluir = ouvinte;
	}

	public void concluir(String hash, Map<String, Object> resultado) {
		publicar(hash, resultado);
	}

	public void examinar(String operador, String contrato, Achado achado, RotaDoModelo.Chamada chamada, String desfecho,
			String veredito) {
		examinar(operador, contrato, achado, chamada, desfecho, veredito, null, null);
	}

	public void examinar(String operador, String contrato, Achado achado, RotaDoModelo.Chamada chamada, String desfecho,
			String veredito, String hashEnviado) {
		examinar(operador, contrato, achado, chamada, desfecho, veredito, hashEnviado, null);
	}

	public void examinar(String operador, String contrato, Achado achado, RotaDoModelo.Chamada chamada, String desfecho,
			String veredito, String hashEnviado, Map<String, Object> extras) {

		examinadas.incrementAndGet();

		// O contrato-sonda do laudo perna a perna só sabe V2 e V3. Rota com perna de
		// gerente fica com o
		// veredito do CONTRATO (que roda o Executor de verdade) e mais nada — e,
		// principalmente, sem cair no
		// "sem laudo" que culparia a pool do empréstimo por um revert que não é dela.
		if (chamada.rota().stream().anyMatch(Salto::deGerente)) {
			porClasse.computeIfAbsent("GERENTE_SO_VEREDITO", k -> new AtomicLong()).incrementAndGet();
			Map<String, Object> registro = new LinkedHashMap<>();
			registro.put("tipo", "autopsia_gerente");
			registro.put("instante", Instant.now().toString());
			registro.put("altura_achado", achado.altura());
			registro.put("desfecho", desfecho);
			registro.put("veredito", veredito);
			registro.put("montante", chamada.montante().toString());
			registro.put("lucro_previsto", achado.oportunidade().lucro().toString());
			registro.put("rota", chamada.rota().stream().map(s -> s.pool() + " tipo " + s.tipo()).toList());

			// O emprestador (rota[0]) pode ser BLACKLISTED/FORBIDDEN mesmo numa rota com
			// gerente:
			// o sonda não roda aqui, mas a condenação permanente ainda se aplica.
			String motivoGerente = Autopsia.motivoDoVeredito(veredito).trim();
			if (!motivoGerente.isBlank()) {
				byte[] emprestadora = HexFormat.of().parseHex(chamada.rota().get(0).pool());
				if (proibicao(motivoGerente)) {
					quarentena.condenar(emprestadora, PENA_DE_PROIBICAO,
							"empréstimo reverteu (" + motivoGerente + ") em gerente em " + registro.get("instante"));
					registro.put("culpada",
							Map.of("indice", 0, "pool", chamada.rota().get(0).pool(), "classe", "EMPRESTIMO_REVERTEU"));
				} else if (overflow(motivoGerente)) {
					chamada.rota().stream().filter(s -> !s.v3() && !s.deGerente())
							.forEach(s -> quarentena.condenar(HexFormat.of().parseHex(s.pool()), PENA_DE_ESTADO,
									"Pancake: OVERFLOW na rota gerente em " + registro.get("instante")));
				}
			}

			if (extras != null) {
				registro.putAll(extras);
			}
			escrever(registro);
			publicar(hashEnviado, registro);
			return;
		}

		String bloco = "0x" + Long.toHexString(achado.altura());

		// Só compara quando o nó JÁ tem o bloco N: antes disso ele responde no N−1 sem
		// avisar (27/09).
		try {
			if (!rpc.esperarBloco(achado.altura(), 15, 200)) {
				estadoDoBlocoIndisponivel.incrementAndGet();
				publicar(hashEnviado, Map.of("status", "sem_estado_do_bloco", "altura", achado.altura()));
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			publicar(hashEnviado, Map.of("status", "interrompida"));
			return;
		}

		Optional<String> corpo = pedirCorpo(operador, contrato, chamada, bloco);

		// O bloco é NOVO demais para o RPC que atendeu (o node o viu antes dele):
		// espera e pede o MESMO
		// bloco de novo — ir ao `latest` compararia outro instante. Medido em 24/09:
		// "block not found".
		for (int tentativa = 0; tentativa < 3 && corpo.map(Autopsia::blocoAindaNaoChegou).orElse(false); tentativa++) {
			try {
				Thread.sleep(700);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				publicar(hashEnviado, Map.of("status", "interrompida"));
				return;
			}
			corpo = pedirCorpo(operador, contrato, chamada, bloco);
		}

		// O nó já descartou o estado daquele bloco: NÃO há laudo. CICATRIZ de 27/09:
		// aqui se caía em silêncio no
		// `latest`, e a comparação com um bloco mais novo virava "ESTADO" — 9 de 9
		// fendas marcadas como erro de
		// modelo quando o modelo batia com a cadeia no bloco N ao wei (conferido). Nem
		// vai ao `semLaudo`: lá o
		// corpo de erro seria lido como "empréstimo reverteu" e condenaria a pool
		// emprestadora.
		// Idem se, depois das tentativas, o bloco AINDA não chegou ao RPC.
		boolean aindaNaoChegou = corpo.map(Autopsia::blocoAindaNaoChegou).orElse(false);
		if (aindaNaoChegou || corpo.map(Autopsia::estadoIndisponivel).orElse(false)) {
			estadoDoBlocoIndisponivel.incrementAndGet();
			publicar(hashEnviado,
					Map.of("status", aindaNaoChegou ? "bloco_ainda_nao_chegou_ao_rpc" : "estado_historico_indisponivel",
							"altura", achado.altura()));
			return;
		}

		Optional<Laudo> laudo = corpo.flatMap(Autopsia::lerLaudo);

		if (laudo.isEmpty()) {
			semLaudo.incrementAndGet();
			publicar(hashEnviado, semLaudo(achado, chamada, desfecho, veredito, corpo.orElse(null), extras));
			return;
		}

		Map<String, Object> registro = comparar(achado, chamada, laudo.get());
		if (laudo.get().falhouEm() < 0) {
			quarentena.absolverRota(
					chamada.rota().stream().map(s -> s.pool() + ":" + s.entra() + ":" + s.sai()).toList());
		}
		registro.put("desfecho", desfecho);
		registro.put("veredito", veredito);
		if (extras != null) {
			registro.putAll(extras);
		}

		escrever(registro);
		gravarImpostosDasPernas(registro);
		julgar(registro, operador, contrato, chamada, bloco);
		publicar(hashEnviado, registro);
	}

	private void publicar(String hash, Map<String, Object> resultado) {
		if (hash != null) {
			aoConcluir.accept(hash, resultado);
		}
	}

	// A comparação, sem rede — separada para o teste prová-la com um laudo montado
	// à mão.
	Map<String, Object> comparar(Achado achado, RotaDoModelo.Chamada chamada, Laudo laudo) {

		List<Travessia> travessias = achado.travessias();
		List<Salto> rota = chamada.rota();
		int n = rota.size();

		List<BigInteger> plano = planoDoModelo(travessias, chamada.montante());

		List<Map<String, Object>> pernas = new ArrayList<>();
		Perna culpada = null;

		for (int i = 0; i < n; i++) {

			Salto salto = rota.get(i);
			Travessia travessia = travessias.get(i);
			Map<String, Object> perna = new LinkedHashMap<>();

			perna.put("pool", salto.pool());
			perna.put("tipo", salto.v3() ? "V3" : "V2");
			perna.put("taxa_bps_contrato", salto.taxaBps());
			perna.put("entra", salto.entra());
			perna.put("sai", salto.sai());
			perna.put("modelo_estado", estadoDoModelo(travessia.pool()));
			perna.put("cadeia_estado", List.of(laudo.estadoA().get(i).toString(), laudo.estadoB().get(i).toString()));
			perna.put("plano_entra", i < plano.size() ? plano.get(i).toString() : null);
			perna.put("plano_sai", i + 1 < plano.size() ? plano.get(i + 1).toString() : null);

			long estadoBps = desvioDeEstado(travessia.pool(), laudo, i);
			perna.put("estado_desvio_bps", estadoBps);

			if (!laudo.passou(i)) {
				Classe reverteu = !salto.v3() && violouInvariante(laudo.motivo()) ? Classe.TAXA_SUBESTIMADA
						: Classe.REVERTEU;
				perna.put("classe", i == laudo.falhouEm() ? reverteu.name() : "NAO_ALCANCADA");
				if (i == laudo.falhouEm()) {
					perna.put("motivo", laudo.motivo());
					Perna p = new Perna(i, salto.pool(), salto.v3(), salto.taxaBps(), reverteu, Long.MAX_VALUE,
							estadoBps, 0, 0);
					// Revert DEPOIS de uma perna com imposto é consequência dele (a perna recebeu
					// menos do que a
					// conta pedia): a culpada segue sendo o imposto. CICATRIZ de 24/09: o revert
					// tomava a culpa,
					// a pool inocente ia para a quarentena e o token de 50% de imposto ficava no
					// grafo.
					boolean impostoAntes = culpada != null && culpada.classe() == Classe.IMPOSTO;
					culpada = culpada == null || (culpada.classe() != Classe.REVERTEU && !impostoAntes) ? p : culpada;
				}
				pernas.add(perna);
				continue;
			}

			BigInteger enviado = laudo.enviado().get(i);
			BigInteger entrou = laudo.entrou().get(i);
			BigInteger prometido = laudo.prometido().get(i);
			BigInteger chegou = laudo.chegou().get(i);

			perna.put("cadeia_enviado", enviado.toString());
			perna.put("cadeia_entrou", entrou.toString());
			perna.put("cadeia_prometido", prometido.toString());
			perna.put("cadeia_chegou", chegou.toString());

			long vendaBps = perda(enviado, entrou);
			long compraBps = perda(prometido, chegou);
			perna.put("imposto_venda_bps", vendaBps);
			perna.put("imposto_compra_bps", compraBps);

			// O modelo com a entrada que a CADEIA produziu até aqui: isola a perna.
			Optional<BigInteger> isolado = aplicar(travessia, enviado);
			perna.put("modelo_sai_com_entrada_real", isolado.map(BigInteger::toString).orElse(null));

			Classe classe;
			long desvioBps;

			if (isolado.isEmpty()) {
				classe = Classe.SEM_MODELO;
				desvioBps = 0;
			} else {
				desvioBps = perda(isolado.get(), chegou);
				classe = classificar(desvioBps, vendaBps, compraBps, estadoBps);
			}

			perna.put("desvio_bps", desvioBps);
			perna.put("classe", classe.name());
			pernas.add(perna);

			Perna p = new Perna(i, salto.pool(), salto.v3(), salto.taxaBps(), classe, desvioBps, estadoBps, vendaBps,
					compraBps);

			if (classe != Classe.OK && classe != Classe.SEM_MODELO
					&& (culpada == null || (culpada.classe() != Classe.REVERTEU && desvioBps > culpada.desvioBps()))) {
				culpada = p;
			}
		}

		Map<String, Object> registro = new LinkedHashMap<>();
		registro.put("tipo", "autopsia");
		registro.put("instante", Instant.now().toString());
		registro.put("altura_achado", achado.altura());
		registro.put("pernas_n", n);
		registro.put("montante", chamada.montante().toString());
		registro.put("lucro_previsto", achado.oportunidade().lucro().toString());
		registro.put("lucro_minimo", chamada.lucroMinimo().toString());
		registro.put("final_previsto", achado.oportunidade().saida().toString());

		boolean completa = laudo.falhouEm() < 0;
		BigInteger finalCadeia = completa ? laudo.chegou().get(n - 1) : null;
		registro.put("final_cadeia", finalCadeia == null ? null : finalCadeia.toString());
		registro.put("retorno_cadeia_bps", finalCadeia == null ? null
				: finalCadeia.multiply(BigInteger.valueOf(10_000)).divide(chamada.montante()).longValue());
		registro.put("retorno_previsto_bps", achado.oportunidade().saida().multiply(BigInteger.valueOf(10_000))
				.divide(chamada.montante()).longValue());
		registro.put("fecharia_agora",
				finalCadeia != null && finalCadeia.compareTo(chamada.montante().add(chamada.lucroMinimo())) >= 0);
		registro.put("callback", laudo.callback());
		registro.put("callback_conhecido", CALLBACKS_DO_EXECUTOR.contains(laudo.callback()));
		registro.put("falhou_em", laudo.falhouEm());
		registro.put("motivo", laudo.motivo());
		registro.put("pernas", pernas);

		if (culpada != null) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("indice", culpada.indice());
			c.put("pool", culpada.pool());
			c.put("tipo", culpada.v3() ? "V3" : "V2");
			c.put("classe", culpada.classe().name());
			c.put("desvio_bps", culpada.desvioBps() == Long.MAX_VALUE ? null : culpada.desvioBps());
			c.put("estado_bps", culpada.estadoBps());
			c.put("imposto_venda_bps", culpada.impostoVendaBps());
			c.put("imposto_compra_bps", culpada.impostoCompraBps());
			registro.put("culpada", c);
		}

		return registro;
	}

	// A frase com que os forks V2 recusam saída acima do que o invariante permite:
	// "Pancake: K",
	// "UniswapV2: K", "BiswapPair: K", "K"…
	// O veredito do relay vem com prefixo ("[0x65d0..5a46] execution reverted: LL:
	// K") ou já curto ("LL: K").
	static String motivoDoVeredito(Object veredito) {
		String v = veredito == null ? "" : veredito.toString();
		int i = v.indexOf("execution reverted:");
		return i < 0 ? v : v.substring(i + "execution reverted:".length());
	}

	static boolean violouInvariante(String motivo) {
		String m = motivo == null ? "" : motivo.trim();
		return m.equals("K") || m.endsWith(": K") || m.toUpperCase(java.util.Locale.ROOT).contains("INVARIANT");
	}

	// O erro vira CORREÇÃO, não só pena — o remédio de cada classe é outro:
	// - IMPOSTO: a autópsia MEDIU o imposto com um swap de verdade (prometido −
	// chegou; enviado −
	// entrou). Grava como prova, e o portão do cotador passa a barrar o par — sem
	// depender da sonda,
	// que falha justo nos tokens de layout estranho (foi o slot não achado que
	// virou honeypot falso).
	// - TAXA_SUBESTIMADA: acha a taxa que a pool cobra de NÓS, por bisseção com a
	// própria autópsia, e
	// a corrige no tarifário: a pool volta ao grafo precificada certo em vez de
	// sair dele.
	// - o resto (revert, estado, curva): pena, que dobra a cada reincidência.
	// TODA perna em que a cadeia mediu imposto vira prova — não só a culpada. Uma
	// rota pode ter imposto
	// numa perna e o revert noutra; gravar só a culpada deixava o token taxado no
	// grafo.
	@SuppressWarnings("unchecked")
	void gravarImpostosDasPernas(Map<String, Object> registro) {

		for (Map<String, Object> perna : (List<Map<String, Object>>) registro.get("pernas")) {

			long venda = ((Number) perna.getOrDefault("imposto_venda_bps", 0L)).longValue();
			long compra = ((Number) perna.getOrDefault("imposto_compra_bps", 0L)).longValue();

			if (venda <= SINAL_BPS && compra <= SINAL_BPS) {
				continue;
			}

			byte[] pool = HexFormat.of().parseHex((String) perna.get("pool"));

			// Cada direção no seu lugar: a venda é do token que ENTRA na pool, a compra do
			// que SAI dela. Até 27/09
			// as duas iam para a mesma chave e a segunda apagava a primeira.
			if (venda > SINAL_BPS) {
				impostos.medidaVenda(pool, HexFormat.of().parseHex((String) perna.get("entra")), (int) venda);
			}
			if (compra > SINAL_BPS) {
				byte[] sai = HexFormat.of().parseHex((String) perna.get("sai"));
				if (compra >= 10_000) {
					impostos.honeypot(pool, sai);
				} else {
					impostos.medidaCompra(pool, sai, (int) compra);
				}
			}

			impostosGravados.incrementAndGet();
		}
	}

	private void aprender(Classe classe, int indice, Map<String, Object> perna, String operador, String contrato,
			RotaDoModelo.Chamada chamada, String bloco, String instante) {

		byte[] pool = HexFormat.of().parseHex((String) perna.get("pool"));

		if (classe == Classe.IMPOSTO) {
			return;
		}

		if (classe == Classe.TAXA_SUBESTIMADA) {

			Optional<Integer> real = sondarTaxa(operador, contrato, chamada, indice, bloco);

			if (real.isPresent()) {
				quarentena.medirTaxa(pool, real.get() * br.com.node.nucleo.Pool.PONTO_BASE);
				taxasCorrigidas.incrementAndGet();
				return;
			}

			return;
		}

	}

	private static final List<Integer> CANDIDATAS_BPS = List.of(20, 25, 30, 35, 40, 50, 60, 75, 100, 150, 200, 300, 500,
			1_000);

	Optional<Integer> sondarTaxa(String operador, String contrato, RotaDoModelo.Chamada chamada, int indice,
			String bloco) {

		int atual = chamada.rota().get(indice).taxaBps();
		List<Integer> candidatas = CANDIDATAS_BPS.stream().filter(t -> t > atual).toList();

		int baixo = 0;
		int alto = candidatas.size() - 1;
		Integer achada = null;

		while (baixo <= alto) {

			int meio = (baixo + alto) >>> 1;

			if (passa(operador, contrato, chamada, indice, candidatas.get(meio), bloco)) {
				achada = candidatas.get(meio);
				alto = meio - 1;
			} else {
				baixo = meio + 1;
			}
		}

		return Optional.ofNullable(achada);
	}

	private boolean passa(String operador, String contrato, RotaDoModelo.Chamada chamada, int indice, int taxaBps,
			String bloco) {

		List<Salto> rota = new ArrayList<>(chamada.rota());
		Salto s = rota.get(indice);
		rota.set(indice, new Salto(s.pool(), s.entra(), s.sai(), s.tipo(), taxaBps, s.chave()));

		return pedirLaudo(operador, contrato, new RotaDoModelo.Chamada(rota, chamada.montante(), chamada.lucroMinimo()),
				bloco).map(laudo -> laudo.passou(indice)).orElse(false);
	}

	private final AtomicLong impostosGravados = new AtomicLong();
	private final AtomicLong taxasCorrigidas = new AtomicLong();

	static Classe classificar(long desvioBps, long vendaBps, long compraBps, long estadoBps) {

		if (desvioBps <= TOLERANCIA_BPS) {
			return Classe.OK;
		}

		if (vendaBps > SINAL_BPS || compraBps > SINAL_BPS) {
			return Classe.IMPOSTO;
		}

		if (estadoBps > SINAL_BPS) {
			return Classe.ESTADO;
		}

		return Classe.CURVA_OU_TAXA;
	}

	@SuppressWarnings("unchecked")
	private void julgar(Map<String, Object> registro, String operador, String contrato, RotaDoModelo.Chamada chamada,
			String bloco) {

		boolean fecharia = Boolean.TRUE.equals(registro.get("fecharia_agora"));
		boolean callbackOk = Boolean.TRUE.equals(registro.get("callback_conhecido"));
		Map<String, Object> culpada = (Map<String, Object>) registro.get("culpada");

		if (fecharia) {
			fechariam.incrementAndGet();
		}

		if (!callbackOk) {
			callbackDesconhecido.incrementAndGet();
		}

		String classe = culpada != null ? (String) culpada.get("classe")
				: !callbackOk ? "CALLBACK_DESCONHECIDO" : fecharia ? "FECHARIA" : "SEM_CULPADA";
		porClasse.computeIfAbsent(classe, k -> new AtomicLong()).incrementAndGet();

		if (culpada == null && violouInvariante(motivoDoVeredito(registro.get("veredito")))) {
			List<Map<String, Object>> v2 = ((List<Map<String, Object>>) registro.get("pernas")).stream()
					.filter(p -> "V2".equals(p.get("tipo"))).toList();
			if (v2.size() == 1) {
				String pool = (String) v2.get(0).get("pool");
				quarentena.condenar(HexFormat.of().parseHex(pool), PENA,
						"K no relay com laudo limpo em " + registro.get("instante"));
				porClasse.computeIfAbsent("K_SO_NO_RELAY", k -> new AtomicLong()).incrementAndGet();
			}
		}

		if (culpada != null) {

			String pool = (String) culpada.get("pool");
			culpadas.computeIfAbsent(pool, k -> new AtomicLong()).incrementAndGet();

			Classe c = Classe.valueOf((String) culpada.get("classe"));
			int indice = (int) culpada.get("indice");
			Map<String, Object> perna = ((List<Map<String, Object>>) registro.get("pernas")).get(indice);

			aprender(c, indice, perna, operador, contrato, chamada, bloco, (String) registro.get("instante"));
		}
	}

	private Optional<Laudo> pedirLaudo(String operador, String contrato, RotaDoModelo.Chamada chamada, String bloco) {
		return pedirCorpo(operador, contrato, chamada, bloco).flatMap(Autopsia::lerLaudo);
	}

	private Map<String, Object> semLaudo(Achado achado, RotaDoModelo.Chamada chamada, String desfecho, String veredito,
			String corpo, Map<String, Object> extras) {

		String motivo = corpo == null ? "nenhum nó respondeu" : RpcDoEnsaio.interpretar(corpo).texto();
		String motivoFinal = motivo.isBlank()
				? String.valueOf(corpo).substring(0, Math.min(300, String.valueOf(corpo).length()))
				: motivo;

		List<String> pools = new ArrayList<>();
		chamada.rota().forEach(
				salto -> pools.add(salto.pool() + (salto.v3() ? " (V3)" : " (V2 " + salto.taxaBps() + " bps)")));

		Map<String, Object> registro = new LinkedHashMap<>();
		registro.put("tipo", "autopsia_sem_laudo");
		registro.put("instante", Instant.now().toString());
		registro.put("altura_achado", achado.altura());
		registro.put("desfecho", desfecho);
		registro.put("veredito", veredito);
		registro.put("motivo", motivoFinal);
		registro.put("motivo_sonda", motivoFinal);
		registro.put("motivo_executor", motivoDoVeredito(veredito).trim());
		registro.put("montante", chamada.montante().toString());
		registro.put("lucro_previsto", achado.oportunidade().lucro().toString());
		registro.put("rota", pools);

		String motivoExecutor = motivoDoVeredito(veredito).trim();
		boolean sondaSemFlashV3 = !chamada.rota().isEmpty() && chamada.rota().get(0).v3()
				&& (motivo.toLowerCase(java.util.Locale.ROOT).contains(EMPRESTIMO_NAO_V2)
						|| motivo.toLowerCase(java.util.Locale.ROOT).contains("emprestimonaov2"));
		boolean transferenciaFalhou = transferenciaFalhou(motivoExecutor);

		if (sondaSemFlashV3) {
			porClasse.computeIfAbsent("SONDA_SEM_FLASH_V3", k -> new AtomicLong()).incrementAndGet();
		}
		if (transferenciaFalhou) {
			List<String> saltos = chamada.rota().stream().map(s -> s.pool() + ":" + s.entra() + ":" + s.sai()).toList();
			String chaveRota = Quarentena.chaveRota(saltos);
			registro.put("rota_em_quarentena", chaveRota);
			porClasse.computeIfAbsent("TRANSFERENCIA_SEM_PERNA", k -> new AtomicLong()).incrementAndGet();
		}

		if (!transferenciaFalhou && !motivoExecutor.isBlank() && overflow(motivoExecutor)) {
			chamada.rota().stream().filter(s -> !s.v3() && !s.deGerente())
					.forEach(s -> quarentena.condenar(HexFormat.of().parseHex(s.pool()), PENA_DE_ESTADO,
							"Pancake: OVERFLOW na rota em " + registro.get("instante")));
			porClasse.computeIfAbsent("OVERFLOW", k -> new AtomicLong()).incrementAndGet();
		} else if (!transferenciaFalhou && !motivoExecutor.isBlank() && proibicao(motivoExecutor)
				&& !chamada.rota().isEmpty()) {
			byte[] emprestadora = HexFormat.of().parseHex(chamada.rota().get(0).pool());
			quarentena.condenar(emprestadora, PENA_DE_PROIBICAO,
					"executor recebeu proibição explícita (" + motivoExecutor + ") em " + registro.get("instante"));
			registro.put("culpada",
					Map.of("indice", 0, "pool", chamada.rota().get(0).pool(), "classe", "PROIBICAO_EXPLICITA"));
			porClasse.computeIfAbsent("PROIBICAO_EXPLICITA", k -> new AtomicLong()).incrementAndGet();
		} // else if (!sondaSemFlashV3 && !transferenciaFalhou) {
			// porClasse.computeIfAbsent("SEM_LAUDO", k -> new
			// AtomicLong()).incrementAndGet();
			// }

		if (sondaSemFlashV3) {
			registro.put("limitacao_sonda", "autopsia-runtime nao simula flash loan com ponta V3");
		}

		if (extras != null) {
			registro.putAll(extras);
		}

		registro.put("status", transferenciaFalhou ? "transferencia_falhou_sem_perna"
				: sondaSemFlashV3 ? "sonda_sem_flash_v3" : "sem_laudo");
		escrever(registro);
		return registro;
	}

	static boolean transferenciaFalhou(String motivo) {
		String m = motivo == null ? "" : motivo.trim().toLowerCase(java.util.Locale.ROOT);
		return m.equals("tf") || m.endsWith(": tf") || m.equals("transfer_failed")
				|| m.equals("transferhelper: transfer failed") || m.equals("pancake: transfer_failed");
	}

	static boolean proibicao(String motivo) {
		String m = motivo.toLowerCase(java.util.Locale.ROOT);
		// "forbidden" = par recusa chamador por regra de acesso; "blacklisted" =
		// Executor na blacklist do token/par.
		// Ambas são permanentes: o estado do contrato que bloqueia não se desfaz com o
		// tempo.
		return m.contains("forbidden") || m.contains("blacklisted") || m.contains("blacklist");
	}

	static boolean overflow(String motivo) {
		return motivo.toLowerCase(java.util.Locale.ROOT).contains("overflow");
	}

	// Rejeição por gás insuficiente do nosso lado — culpa nossa, não da pool. Pena
	// mínima.
	static boolean gasInsuficiente(String motivo) {
		String m = motivo.toLowerCase(java.util.Locale.ROOT);
		return m.contains("out of gas") || m.contains("gas limit") || m.contains("intrinsic gas")
				|| m.contains("gas required") || m.contains("gas too low");
	}

	// A tx-alvo do bundle falhou na simulação do relay — problema de timing/bundle,
	// não da pool.
	static boolean txAlvoFalhou(String motivo) {
		return motivo.toLowerCase(java.util.Locale.ROOT).contains("non-reverting tx in bundle failed");
	}

	// "header not found" é o nodereal (RPC_LOGS) sem o bloco AINDA: vemos o bloco
	// antes dele. Medido em 27/09: ele
	// responde assim para qualquer altura acima da cabeça dele. Estava em
	// `estadoIndisponivel`, e 23 de 28
	// autópsias ficaram sem laudo — e sem laudo o imposto não se mede, e a mesma
	// fenda fantasma voltou a cada bloco.
	static boolean blocoAindaNaoChegou(String corpo) {
		String c = corpo.toLowerCase(java.util.Locale.ROOT);
		return c.contains("block not found") || c.contains("header not found");
	}

	static boolean estadoIndisponivel(String corpo) {
		String c = corpo.toLowerCase(java.util.Locale.ROOT);
		return c.contains("missing trie node") || c.contains("state is not available") || c.contains("state histories");
	}

	private Optional<String> pedirCorpo(String operador, String contrato, RotaDoModelo.Chamada chamada, String bloco) {

		JsonObject codigo = new JsonObject();
		codigo.addProperty("code", "0x" + CODIGO);
		JsonObject override = new JsonObject();
		override.add("0x" + contrato, codigo);

		String dados = HexFormat.of().formatHex(calldata(chamada.rota(), chamada.montante()));

		return rpc.chamarComOverride(operador, contrato, dados, override, bloco);
	}

	// `autopsiar(Travessia[],uint256)`: a mesma codificação da rota do Executor,
	// sem o lucro mínimo.
	static byte[] calldata(List<Salto> rota, BigInteger montante) {

		byte[] completo = AbiDoExecutor.executar(rota, montante, BigInteger.ZERO);

		// `executar` codifica (offset, montante, lucroMinimo, rota...). Aqui são dois
		// parâmetros: o
		// offset da rota passa de 0x60 para 0x40 e o lucro mínimo sai.
		byte[] dados = new byte[completo.length - 32];
		System.arraycopy(AUTOPSIAR, 0, dados, 0, 4);
		dados[4 + 31] = 0x40;
		System.arraycopy(completo, 36, dados, 36, 32);
		System.arraycopy(completo, 100, dados, 68, completo.length - 100);

		return dados;
	}

	static Optional<Laudo> lerLaudo(String corpo) {

		try {

			JsonObject json = JsonParser.parseString(corpo).getAsJsonObject();

			if (!json.has("error") || !json.get("error").isJsonObject()) {
				return Optional.empty();
			}

			JsonObject erro = json.getAsJsonObject("error");

			if (!erro.has("data") || !erro.get("data").isJsonPrimitive()) {
				return Optional.empty();
			}

			String dados = erro.get("data").getAsString();
			dados = dados.startsWith("0x") ? dados.substring(2) : dados;

			if (!dados.startsWith(LAUDO)) {
				return Optional.empty();
			}

			return Optional.of(decodificar(dados.substring(8)));

		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("decodificar laudo do contrato", e);
			return Optional.empty();
		}
	}

	static Laudo decodificar(String corpo) {

		List<List<BigInteger>> arrays = new ArrayList<>();

		for (int i = 0; i < 6; i++) {
			arrays.add(array(corpo, palavra(corpo, i).intValueExact()));
		}

		BigInteger falhou = palavra(corpo, 6);
		int falhouEm = falhou.bitLength() > 31 ? -1 : falhou.intValueExact();

		int onde = palavra(corpo, 7).intValueExact();
		int tamanho = new BigInteger(corpo.substring(onde * 2, onde * 2 + 64), 16).intValueExact();
		String motivo = corpo.substring(onde * 2 + 64, onde * 2 + 64 + tamanho * 2);

		String callback = corpo.substring(8 * 64, 8 * 64 + 8);

		return new Laudo(arrays.get(0), arrays.get(1), arrays.get(2), arrays.get(3), arrays.get(4), arrays.get(5),
				falhouEm, motivoLegivel(motivo), callback);
	}

	// O motivo cru vira texto quando é `Error(string)`; senão fica o hex (seletor +
	// argumentos).
	private static String motivoLegivel(String hex) {

		if (hex.isEmpty()) {
			return "(revert sem dado)";
		}

		String frase = RpcDoEnsaio.frase(hex, "");

		return frase.isEmpty() ? "0x" + hex : frase;
	}

	private static BigInteger palavra(String corpo, int indice) {
		return new BigInteger(corpo.substring(indice * 64, indice * 64 + 64), 16);
	}

	private static List<BigInteger> array(String corpo, int offset) {

		int tamanho = new BigInteger(corpo.substring(offset * 2, offset * 2 + 64), 16).intValueExact();
		List<BigInteger> valores = new ArrayList<>(tamanho);

		for (int i = 0; i < tamanho; i++) {
			int inicio = offset * 2 + 64 + i * 64;
			valores.add(new BigInteger(corpo.substring(inicio, inicio + 64), 16));
		}

		return valores;
	}

	// O plano do modelo: a entrada de cada perna e a saída final, andando as
	// travessias do montante.
	// Pára onde o modelo recusa — o que já é resposta.
	private static List<BigInteger> planoDoModelo(List<Travessia> travessias, BigInteger montante) {

		List<BigInteger> plano = new ArrayList<>();
		BigInteger valor = montante;
		plano.add(valor);

		for (Travessia travessia : travessias) {

			Optional<BigInteger> saida = aplicar(travessia, valor);

			if (saida.isEmpty()) {
				break;
			}

			valor = saida.get();
			plano.add(valor);
		}

		return plano;
	}

	private static Optional<BigInteger> aplicar(Travessia travessia, BigInteger entrada) {

		if (entrada.signum() <= 0) {
			return Optional.empty();
		}

		try {
			return Optional.of(travessia.aplicar(entrada).saida());
		} catch (IllegalArgumentException recusou) {
			return Optional.empty();
		}
	}

	private static List<String> estadoDoModelo(Pool pool) {

		if (pool instanceof PoolV2 v2) {
			return List.of(v2.reserva0().toString(), v2.reserva1().toString());
		}

		if (pool instanceof PoolV3 v3) {
			return List.of(v3.sqrtPrecoX96().toString(), v3.liquidez().toString());
		}

		return List.of();
	}

	// O quanto o estado que o modelo usou difere do da cadeia, no pior dos dois
	// números. Zero quando a
	// cadeia não respondeu o estado (o laudo põe zero, e zero não é desvio: é falta
	// de medida).
	private static long desvioDeEstado(Pool pool, Laudo laudo, int i) {

		BigInteger a = laudo.estadoA().get(i);
		BigInteger b = laudo.estadoB().get(i);

		if (a.signum() == 0 || b.signum() == 0) {
			return 0;
		}

		if (pool instanceof PoolV2 v2) {
			return Math.max(diferenca(v2.reserva0(), a), diferenca(v2.reserva1(), b));
		}

		if (pool instanceof PoolV3 v3) {
			return Math.max(diferenca(v3.sqrtPrecoX96(), a), diferenca(v3.liquidez(), b));
		}

		return 0;
	}

	private static long diferenca(BigInteger modelo, BigInteger cadeia) {
		return modelo.subtract(cadeia).abs().multiply(BigInteger.valueOf(10_000)).divide(cadeia).longValue();
	}

	// Quanto `depois` ficou abaixo de `antes`, em bps de `antes`. Negativo (a
	// cadeia deu MAIS) vira 0:
	// só interessa o lado que faz reverter.
	static long perda(BigInteger antes, BigInteger depois) {

		if (antes.signum() <= 0 || depois.compareTo(antes) >= 0) {
			return 0;
		}

		return antes.subtract(depois).multiply(BigInteger.valueOf(10_000)).divide(antes).longValue();
	}

	private synchronized void escrever(Map<String, Object> registro) {

		if (arquivo == null) {
			return;
		}
		try {

			if (saida == null) {
				Files.createDirectories(arquivo.getParent());
				saida = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			}

			saida.write(gson.toJson(registro));
			saida.newLine();
			saida.flush();

		} catch (IOException e) {
			LOG.warning(() -> "autópsia: não gravou em " + arquivo + ": " + e.getMessage());
		}
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("examinadas", examinadas.get());
		resumo.put("sem_laudo", semLaudo.get());
		resumo.put("sem_estado_do_bloco_no_rpc", estadoDoBlocoIndisponivel.get());
		resumo.put("fechariam_agora", fechariam.get());
		resumo.put("callback_desconhecido", callbackDesconhecido.get());

		Map<String, Long> classes = new LinkedHashMap<>();
		porClasse.forEach((k, v) -> classes.put(k, v.get()));
		resumo.put("por_classe", classes);

		Map<String, Long> top = new LinkedHashMap<>();
		culpadas.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().get(), x.getValue().get())).limit(10)
				.forEach(e -> top.put(e.getKey(), e.getValue().get()));
		resumo.put("pools_culpadas", top);
		resumo.put("em_quarentena", quarentena.presas().size());
		resumo.put("proibidas_de_emprestar", quarentena.semEmprestimo());
		resumo.put("impostos_gravados", impostosGravados.get());
		resumo.put("taxas_corrigidas", taxasCorrigidas.get());
		resumo.put("taxas_medidas", quarentena.taxasMedidas());
		resumo.put("arquivo", arquivo == null ? "integrado ao diario de ensaio" : arquivo.toString());

		return resumo;
	}

	@Override
	public synchronized void close() {

		try {
			if (saida != null) {
				saida.close();
			}
		} catch (IOException e) {
			// encerrando; nada a fazer
		}
	}

	private static String carregarCodigo() {

		try (InputStream in = Autopsia.class.getResourceAsStream("/autopsia-runtime.hex")) {

			if (in == null) {
				throw new IllegalStateException("autopsia-runtime.hex não está no classpath");
			}

			return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();

		} catch (IOException e) {
			throw new IllegalStateException("autopsia-runtime.hex ilegível", e);
		}
	}
}
