package br.com.node.ensaio;

import java.io.Closeable;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.Quarentena;
import br.com.node.catalogo.Tarifario;
import br.com.node.util.Keccak;
import br.com.node.util.Keystore;
import br.com.node.util.Secp256k1;

// Envia a arbitragem de verdade — o único ponto do sistema que gasta dinheiro. Por isso é
// DESARMADO por padrão e cercado de travas; nada sai sem uma decisão explícita.
//
// Recebe um achado que passou no portão de piso, traduz para a rota do contrato, põe o gás pela
// estimativa calibrada do `CustoDeGas` (teto, com folga) e assina `executarComEmprestimo` com a chave
// do operador (decifrada do keystore). Não consulta RPC no caminho: quem simula antes de incluir é o
// relay, de graça.
//
// O PISO vai no `lucroMinimo` do contrato: mesmo que o estado ande entre a estimativa e a inclusão,
// a tx só entra se o lucro medido na cadeia cobrir o gás com a margem — e o flash swap nunca deixa o
// capital sair.
//
// Manda como BUNDLE `[swap alvo, nossa]` pelo relay privado (Puissant): com `acceptReverting` vazio, o
// bundle só entra se tudo suceder, então INCLUÍDO ⟹ LUCROU (o `lucroMinimo` do contrato garante) e um
// backrun que não fecha não é incluído e NÃO PAGA GÁS. Não há como perder — nem gás. Por isso não
// ensaia antes (o ensaio virou só atraso) e opera contínuo: o juiz é o contrato + o relay.
//
// TRAVAS: (1) ARMADO — sem `ARB_ARMADO=SIM`, é dry-run (loga "mandaria", não manda); (2) KILL-SWITCH
// — o arquivo `PARAR` halta na hora; (3) TETO POR TX — não manda se o gás máximo passar do limite;
// (4) TETO DIÁRIO — só vale no envio PÚBLICO (que pode reverter pagando gás); no relay é dispensado,
// porque revert não paga. O NONCE é lido fresco a cada envio: bundle não incluído não consome nonce,
// e um contador local deixaria buracos que travariam os próximos.
public final class Disparo implements Closeable {

	private static final Logger LOG = Logger.getLogger(Disparo.class.getName());

	// O Executor. Configurável por `EXECUTOR` (com ou sem 0x) para trocar de contrato sem recompilar — o
	// deploy do Executor com fallback de callbacks (24/09) cria um endereço novo. Sem a variável, o atual.
	// Definida e mal escrita ESTOURA: antes caía calada no Executor antigo, e o node chamaria outro contrato.
	private static final String CONTRATO = java.util.Optional.ofNullable(System.getenv("EXECUTOR"))
			.map(String::trim).map(e -> {
				if (!e.matches("(0x)?[0-9a-fA-F]{40}")) {
					throw new IllegalStateException("EXECUTOR ilegível: " + e);
				}
				return e.replaceFirst("^0x", "").toLowerCase(java.util.Locale.ROOT);
			})
			.orElse("b0599744c5858f9082c4ca9d92fc314476307496");

	public static byte[] executor() {
		return HexFormat.of().parseHex(CONTRATO);
	}
	private static final Path KEYSTORE = Path.of("operador.keystore");
	private static final Path KILL = Path.of("PARAR");

	// Folga do LIMITE de gás sobre a estimativa: 6/5 (+20%). O limite é teto, não
	// previsão — gasto
	// não usado é devolvido; estourar o limite, não: a tx reverte por out-of-gas. A
	// folga cobre a
	// diferença entre a calldata estimada e a que sai (o `lucroMinimo` muda alguns
	// bytes) e a
	// variação de estado entre a estimativa e a inclusão.
	private static final long GAS_LIMIT_MARGEM_NUM = 6L;
	private static final long GAS_LIMIT_MARGEM_DEN = 5L;

	private static final BigInteger TETO_GAS_TX = BigInteger.valueOf(2_000_000_000_000_000L); // 0,002 BNB

	// PAGAMENTO AO BUILDER (25/09). No puissant-builder v2 o lance do bundle é `(gás × 0,9) + BNB enviado ao
	// Builder Control EOA`, e o BNB tem prioridade. Medido no bloco 123894823 (o da fenda de 5,03 USDT que
	// perdemos): três bots arbitraram a MESMA pool e cada um mandou ~0,017 BNB a este endereço numa tx
	// separada, dentro do bundle; o nosso lance era o gás de 0,05 gwei, ~0,0000185 BNB. O pagamento é uma
	// tx SIMPLES de BNB (o que a documentação descreve e o que vimos funcionar), assinada com o nonce
	// seguinte ao da arb: o bundle é atômico e não aceita revert, então se a arb não entra, ele não paga.
	//   PAGAMENTO_BUILDER_BPS — o lance INICIAL de cada rota (padrão 3000 = 30%); dali em diante quem decide é
	//                           o `Lances`, que aprende com o desfecho de cada envio e guarda em `lances.json`
	//   PAGAMENTO_TETO_BNB    — teto por bundle, em BNB (padrão 0.05)
	// WBNB: o token de partida da maioria das rotas. O Executor pode ter WBNB depositado para rodar
	// `executar` (sem flash loan) — mais simples, sem callback, e desbloqueia V3/gerente na ponta 0.
	private static final String WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";

	// O saldo de BNB do operador, relido com o nonce. O pagamento ao builder sai DELE: lance acima do saldo o relay
	// recusa ("insufficient funds" — 3 vezes na noite de 27/09). Nulo até a primeira leitura.
	private volatile BigInteger saldoOperador;

	// Saldo de WBNB do Executor — capital disponível para `executar` (sem flash loan).
	// Zero até a primeira leitura; atualizado junto com o nonce.
	private volatile BigInteger saldoExecutorWBNB = BigInteger.ZERO;
	private final AtomicLong lanceLimitadoPeloSaldo = new AtomicLong();
	private final AtomicLong lanceLimitadoPeloLucro = new AtomicLong();

	// O lance por rota e quem acompanha o desfecho de cada envio (ligados pelo `Main`).
	private volatile Lances lances;
	private volatile java.util.function.Consumer<Desfechos.Envio> aoEnviar = envio -> {
	};
	private volatile java.util.function.BiConsumer<String, Map<String, Object>> aoRecusarTentativa = (hash, dados) -> {
	};
	private final AtomicLong sequenciaDeDrops = new AtomicLong();

	public void lancesDe(Lances lances) {
		this.lances = lances;
	}

	public void aoEnviar(java.util.function.Consumer<Desfechos.Envio> ouvinte) {
		this.aoEnviar = ouvinte;
	}

	public void aoRecusarTentativa(java.util.function.BiConsumer<String, Map<String, Object>> ouvinte) {
		this.aoRecusarTentativa = ouvinte;
	}

	private volatile java.util.function.BiConsumer<String, String> aoVeredito = (hash, veredito) -> {
	};

	public void aoVeredito(java.util.function.BiConsumer<String, String> ouvinte) {
		this.aoVeredito = ouvinte;
	}

	// A chave da rota no `Lances`: as pools, ordenadas.
	private static String chaveDaRota(Achado achado) {
		return String.join("-", achado.ciclo().pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).sorted().toList());
	}
	private static final BigInteger TETO_PAGAMENTO = java.util.Optional
			.ofNullable(System.getenv("PAGAMENTO_TETO_BNB")).map(String::trim).filter(v -> !v.isBlank())
			.map(v -> new java.math.BigDecimal(v).movePointRight(18).toBigInteger())
			.orElse(new BigInteger("50000000000000000"));
	private static final long GAS_DO_PAGAMENTO = 21_000;
	private final java.util.concurrent.atomic.AtomicLong pagamentosOferecidos = new java.util.concurrent.atomic.AtomicLong();
	private BigInteger pagamentoOferecidoTotal = BigInteger.ZERO;

	// O piso já exigiu cotação da raiz; se o câmbio some entre ele e o pagamento, o lance virava ZERO em
	// silêncio e o bundle saía sem pagar o builder. Isso é dado incoerente, não "sem lance".
	private static IllegalStateException semCambio(byte[] raiz) {
		return new IllegalStateException("sem câmbio BNB para a raiz " + HexFormat.of().formatHex(raiz)
				+ " depois de o piso ter cotado");
	}

	// Quanto do lucro vai ao builder, no token da raiz: a fatia, mas nunca mais que o excedente sobre o
	// piso (o piso é o gás com margem — pagar além dele deixaria a arb no prejuízo) nem que o teto.
	// O pagamento que leva o lance ao ALVO em wei por gás, no token da raiz: o necessário, mas nunca mais que o
	// excedente sobre o piso (pagar além dele deixaria a arb no prejuízo) nem que o teto.
	static BigInteger pagamentoAoBuilder(BigInteger necessario, BigInteger lucro, BigInteger piso, BigInteger teto) {

		BigInteger excedente = lucro.subtract(piso);
		BigInteger pagamento = necessario.min(excedente).min(teto);

		return pagamento.signum() > 0 ? pagamento : BigInteger.ZERO;
	}

	// Quanto BNB falta pagar ao builder para o bundle chegar a `alvo` wei por gás: o 48club conta o gás a 90% e
	// soma o BNB enviado a ele. Zero quando o próprio gás já passa do alvo.
	static BigInteger necessarioParaOAlvo(BigInteger alvoWeiPorGas, BigInteger precoDoGas, BigInteger gas) {
		BigInteger falta = alvoWeiPorGas.subtract(precoDoGas.multiply(BigInteger.valueOf(9)).divide(BigInteger.TEN));
		return falta.signum() > 0 ? falta.multiply(gas) : BigInteger.ZERO;
	}
	private static final BigInteger TETO_DIA = BigInteger.valueOf(3_000_000_000_000_000L); // 0,003 BNB (~US$ 2)

	// Seletores de revert do Executor, para o post-mortem dizer o motivo em vez do
	// hex.
	private static final Map<String, String> NOMES = Map.of("7978d88b", "EmprestimoNaoV2", "54c59343", "NaoPagou",
			"f2beb76b", "SemLucro", "7ad3ddd8", "TokenForaDaPool", "b53161a2", "RotaDescontinua", "e7eb56ec",
			"NaoAutorizado");

	private final RpcDoEnsaio rpc;
	private final RelayPrivado relay;
	private final Catalogo catalogo;
	private final Tarifario tarifario;
	private final CustoDeGas custoDeGas;
	private final Impostos impostos;

	private final Optional<BigInteger> privada;
	private final String endereco;
	private final boolean armado;

	// O nonce do operador, em cache — atualizado FORA do caminho quente (thread
	// agendada), nunca
	// buscado na rede na hora de enviar: aquela busca custava ~200ms e nos fazia
	// perder a corrida (o
	// swap alvo era minerado antes do nosso bundle chegar → "nonce too low").
	// Bundle não incluído não
	// consome nonce, então o cache só desatualiza quando UMA arb aterrissa, e o
	// refresh periódico
	// corrige. Escrito pela thread agendada, lido pela do envio — volatile é a
	// coordenação.
	private volatile long nonce;
	private volatile long nonceAtualizadoEmMs;

	// Post-mortem: pergunta assíncrona ao contrato POR QUE cada envio não fechou
	// (thread única, fora do
	// caminho de envio — não adiciona latência). O mapa conta os vereditos por
	// motivo, para o relatório.
	private final ExecutorService autopsia = Executors.newSingleThreadExecutor(t -> new Thread(t, "autopsia"));
	private final Map<String, AtomicLong> motivos = new ConcurrentHashMap<>();

	// Mede o gás REAL das nossas txs pelo recibo e o confronta com a estimativa usada. Thread própria,
	// fora do caminho de envio: o único jeito de "estimativa precisa" deixar de ser argumento.
	private final MedidorDeGas medidor;

	// O laudo perna a perna de cada disparo da cauda: QUAL pool desmentiu o modelo, e como.
	private final Autopsia laudos;
	private final Quarentena quarentena;

	// Por que o relay recusou, normalizado (sem endereços e números): "nonce too low" é o alvo do N+1 já
	// minerado antes do bundle chegar — corrida perdida —, "execution reverted" é a simulação dele.
	private final Map<String, AtomicLong> recusasDoRelay = new ConcurrentHashMap<>();

	// Quanto o disparo leva da entrada ao relay responder, por caminho. No N+1 o alvo vive ~1 bloco
	// (450 ms): esse número tem de caber nisso.
	private final Map<String, br.com.node.util.Amostras> milisPorCaminho = new ConcurrentHashMap<>();

	// A LINHA DO TEMPO de cada envio, do bloco feito pelo validador à resposta do relay, em etapas. Existe
	// porque a fenda real de 5,03 USDT (25/09, bloco 123894823) foi perdida para um rival no bloco seguinte
	// e o log só tinha o SEGUNDO: não dava para dizer em que etapa foram os ≥ 200 ms. Os marcos do bloco
	// vêm da `Cabeca`; decisão e relay, daqui.
	private volatile java.util.function.LongFunction<java.util.Optional<br.com.node.sincronizacao.Cabeca.Momentos>> momentos = altura -> java.util.Optional
			.empty();
	private final Map<String, br.com.node.util.Amostras> etapasDoEnvio = new ConcurrentHashMap<>();

	// A versão do Executor publicado, perguntada na subida (`versao()`). A 2 conhece `chave` e os gerentes;
	// o antigo não tem a função, e o `eth_call` volta revert — lido como 1. Com o antigo, rota que passa por
	// gerente não se monta, e a ABI de envio é a antiga: o node novo roda com o contrato velho sem mandar
	// calldata que ele não entende.
	private final int versaoDoExecutor;

	private BigInteger gastoNoDia = BigInteger.ZERO;
	private long dia = LocalDate.now().toEpochDay();
	private long enviadas;
	private long recusadas;
	private String ultimoEnvio;

	public Disparo(RpcDoEnsaio rpc, RelayPrivado relay, Catalogo catalogo, Tarifario tarifario, CustoDeGas custoDeGas,
			Impostos impostos, Autopsia laudos, Quarentena quarentena) {

		this.rpc = rpc;
		this.relay = relay;
		this.catalogo = catalogo;
		this.tarifario = tarifario;
		this.custoDeGas = custoDeGas;
		this.impostos = impostos;
		this.laudos = laudos;
		this.quarentena = quarentena;
		this.medidor = new MedidorDeGas(rpc);
		this.privada = carregarChave();
		this.endereco = privada
				.map(p -> HexFormat.of().formatHex(Arrays.copyOfRange(Keccak.hash(Secp256k1.id(p)), 12, 32)))
				.orElse(null);
		this.armado = "SIM".equals(System.getenv("ARB_ARMADO")) && privada.isPresent();
		this.versaoDoExecutor = lerVersao();
		LOG.info(() -> "disparo: executor versão " + versaoDoExecutor
				+ (versaoDoExecutor >= 2 ? " — pernas de gerente (V4, Infinity CL) LIGADAS"
						: " — antigo: pernas de gerente DESLIGADAS até o deploy da versão 2 (EXECUTOR=…)"));

		LOG.info(() -> "disparo: " + (privada.isEmpty() ? "SEM CHAVE (inerte)"
				: "operador 0x" + endereco + (armado ? " ARMADO — envia de verdade" : " desarmado (dry-run)"))
				+ " · executor 0x" + CONTRATO);
	}

	// NÃO sincronizado por inteiro. CICATRIZ de 24/09: o método todo era `synchronized`, com a volta HTTP
	// ao relay (timeout de 5 s) dentro, numa thread única — um envio lento segurava o N+1 seguinte
	// (medido: N+1 com mediana 266 ms e máximo 10 s). Só o estado compartilhado (teto do dia, gasto,
	// contadores) é travado; dois disparos simultâneos usam o mesmo nonce, e só um pode entrar.
	public void disparar(Achado achado, byte[] cruaPendente) {

		if (privada.isEmpty()) {
			return;
		}

		if (Files.exists(KILL)) {
			LOG.warning("disparo: kill-switch (arquivo PARAR) presente — não envio");
			return;
		}

		if (tetoDiarioAtingido()) {
			return;
		}

		long inicio = System.nanoTime();
		long entradaMs = System.currentTimeMillis();
		byte[] raiz = achado.ciclo().pernas().get(0).entra();

		if (!quarentena.podeEmprestar(achado.ciclo().pernas().get(0).pool())) {
			LOG.info(() -> "disparo: DROP — a pool do empréstimo chama um callback que o Executor não implementa");
			return;
		}

		// O gás da rota vale nos DOIS lados do portão (aqui e no `Backrun`): sai do
		// número e do tipo
		// das pernas, já calibrado com swaps reais da BSC. Sem piso não se entra —
		// falha fechada.
		BigInteger gasDaCauda = custoDeGas.estimarGasDasTravessias(achado.travessias(), achado.ciclo().pernas());
		Optional<BigInteger> piso = custoDeGas.piso(raiz, achado.oportunidade().entrada(), gasDaCauda);

		if (piso.isEmpty()) {
			LOG.info(() -> "disparo: DROP sem piso (raiz não-numerário)");
			return;
		}

		// O pagamento ao builder entra no CHÃO que o contrato exige: se a arb render menos que piso +
		// pagamento, ela reverte, e o bundle (sem revert aceito) cai inteiro — o pagamento não sai.
		String rotaDoLance = chaveDaRota(achado);
		Lances lancesAgora = lances;
		if (lancesAgora == null) {
			throw new IllegalStateException("disparo sem `Lances` ligado — o Main tem de chamar lancesDe");
		}
		int fracaoMilha = lancesAgora.fracaoPara(rotaDoLance);
		BigInteger precoDoGas = custoDeGas.prioridade();
		// A 48club conta o gasLimit assinado (gasDaCauda × 6/5), não a estimativa de gasUsed.
		BigInteger gasParaOLance = gasDaCauda.multiply(BigInteger.valueOf(6)).divide(BigInteger.valueOf(5));
		// Pagamento como fração do lucro em BNB: escala com a oportunidade e não tem teto de gwei.
		BigInteger lucroOportunidadeBnb = custoDeGas.paraBnb(raiz, achado.oportunidade().lucro()).orElseThrow(() -> semCambio(raiz));
		BigInteger necessarioBnb = lucroOportunidadeBnb.multiply(BigInteger.valueOf(fracaoMilha)).divide(BigInteger.valueOf(1000));
		BigInteger necessarioBnbSemLimiteDeSaldo = necessarioBnb;
		boolean limitadoPorSaldo = false;

		// O saldo do operador é teto duro: o pagamento e o gás saem dele.
		BigInteger saldo = saldoOperador;
		boolean saldoConhecido = saldo != null;
		if (saldo != null) {
			BigInteger gasMaximo = custoDeGas.maxTaxa().multiply(gasDaCauda.multiply(BigInteger.valueOf(6)).divide(BigInteger.valueOf(5))
					.add(BigInteger.valueOf(GAS_DO_PAGAMENTO)));
			BigInteger disponivel = saldo.subtract(gasMaximo).max(BigInteger.ZERO);
			if (necessarioBnb.compareTo(disponivel) > 0) {
				limitadoPorSaldo = true;
				lanceLimitadoPeloSaldo.incrementAndGet();
				necessarioBnb = disponivel;
			}
		}

		BigInteger necessarioRaiz = custoDeGas.deBnb(raiz, necessarioBnb).orElseThrow(() -> semCambio(raiz));
		BigInteger excedenteLucro = achado.oportunidade().lucro().subtract(piso.get()).max(BigInteger.ZERO);
		BigInteger tetoPagamentoRaiz = custoDeGas.deBnb(raiz, TETO_PAGAMENTO).orElseThrow(() -> semCambio(raiz));
		boolean solicitadoAoBuilder = relay.ativo() && armado;
		boolean limitadoPorLucro = solicitadoAoBuilder && necessarioRaiz.compareTo(excedenteLucro) > 0;
		boolean limitadoPeloTeto = solicitadoAoBuilder && necessarioRaiz.compareTo(tetoPagamentoRaiz) > 0;
		BigInteger pagamentoRaiz = relay.ativo() && armado
				? pagamentoAoBuilder(necessarioRaiz, achado.oportunidade().lucro(), piso.get(), tetoPagamentoRaiz)
				: BigInteger.ZERO;
		if (limitadoPorLucro) {
			lanceLimitadoPeloLucro.incrementAndGet();
		}
		BigInteger pagamentoBnb = pagamentoRaiz.signum() > 0
				? custoDeGas.paraBnb(raiz, pagamentoRaiz).orElseThrow(() -> semCambio(raiz))
				: BigInteger.ZERO;
		// O que o lance vale de fato, por gás: o que o builder vê (divide pelo gasLimit, não pelo gasUsed estimado).
		BigInteger ofertadoWeiPorGas = pagamentoBnb.divide(gasParaOLance)
				.add(precoDoGas.multiply(BigInteger.valueOf(9)).divide(BigInteger.TEN));
		// FOLGA: quando lim_lucro=True, pagamento = lucro − piso, então chao = lucro_previsto.
		// O contrato exigiria exatamente o previsto, mas a aritmética inteira V2/V3 arredonda
		// para baixo em até N wei (N = número de pernas). 10 wei é insignificante e evita o revert.
		BigInteger chao = pagamentoBnb.signum() > 0
				? piso.get().add(pagamentoRaiz).subtract(BigInteger.TEN).max(piso.get())
				: piso.get();

		// O número que o tradutor leva é o CHÃO (piso + pagamento), e é ele o `lucroMinimo` do contrato —
		// a razão está na nota de `RotaDoModelo`.
		Optional<RotaDoModelo.Chamada> chamada = RotaDoModelo.traduzir(achado, catalogo, tarifario, chao);

		if (chamada.isEmpty()) {
			LOG.info(() -> "disparo: DROP sem tradução — rota " + achado.ciclo().pernas().size()
					+ " pernas não vira chamada (par/taxa/sentido/ponto-base)");
			return;
		}

		List<Salto> rota = chamada.get().rota();

		// Usar `executar` (sem flash loan) quando o Executor já tem capital suficiente na raiz.
		// Com WBNB na raiz e saldo ≥ montante: sem callback, sem restrição de fonte de empréstimo —
		// funciona inclusive com V3 ou gerente na perna 0 (o v2 já atravessa todos os tipos).
		boolean semLoan = versaoDoExecutor >= 2
				&& HexFormat.of().formatHex(raiz).equalsIgnoreCase(WBNB)
				&& saldoExecutorWBNB.compareTo(chamada.get().montante()) >= 0;

		// Ponta V3 ou de gerente só bloqueia quando USANDO EMPRÉSTIMO: o Executor < 3 só empresta de V2.
		// Sem empréstimo (`semLoan`), o v2 já atravessa todos os tipos pela `_percorrer`.
		if (!semLoan && versaoDoExecutor < 3 && (rota.get(0).v3() || rota.get(0).deGerente())) {
			LOG.info(() -> "disparo: DROP — ponta " + (rota.get(0).deGerente() ? "de gerente" : "V3")
					+ " e o Executor publicado só empresta de V2 (versão " + versaoDoExecutor + ")");
			return;
		}

		if (versaoDoExecutor < 2 && rota.stream().anyMatch(Salto::deGerente)) {
			LOG.info(() -> "disparo: DROP — rota por gerente (V4/Infinity) e o Executor publicado é o antigo");
			return;
		}

		byte[] calldata = semLoan
				? AbiDoExecutor.executarV2(rota, chamada.get().montante(), chamada.get().lucroMinimo())
				: versaoDoExecutor >= 2
						? AbiDoExecutor.executarComEmprestimoV2(rota, chamada.get().montante(), chamada.get().lucroMinimo())
						: AbiDoExecutor.executarComEmprestimo(rota, chamada.get().montante(), chamada.get().lucroMinimo());

		// SEM PRÉ-VOO. Ele custava uma volta ao RPC público (~300 ms) para responder o que o relay responde
		// de graça: o Puissant simula o bundle e, com `acceptReverting` vazio, o que reverteria não entra
		// e não paga gás. Serviu enquanto não sabíamos POR QUE tudo revertia; esse porquê agora vem da
		// autópsia, assíncrona, depois do envio. O limite de gás é o analítico — teto calibrado com swaps
		// reais — com a folga de 6/5.
		BigInteger gasEstimado = custoDeGas.estimarGas(rota, calldata);
		long gasFinal = comFolgaDeLimite(gasEstimado);

		byte[] calldataFinal = calldata;
		RotaDoModelo.Chamada chamadaFinal = chamada.get();

		if (!armado) {
			LOG.info(() -> "disparo (dry-run): MANDARIA altura " + achado.altura() + " · rota "
					+ achado.ciclo().pernas().size() + " pernas · gás " + gasFinal / 1_000 + "k"
					+ (semLoan ? " · SEM LOAN (saldo executor)" : ""));
			autopsiar(achado, chamadaFinal, calldataFinal, "dry_run", cruaPendente, null, null);
			return;
		}

		BigInteger maxTaxa = custoDeGas.maxTaxa();
		BigInteger custoMaximo = maxTaxa.multiply(BigInteger.valueOf(gasFinal));

		if (custoMaximo.compareTo(TETO_GAS_TX) > 0) {
			registrarDropSemEnvio(achado, rotaDoLance, fracaoMilha, ofertadoWeiPorGas, piso.get(), chamadaFinal, rota,
					"gas_acima_do_teto_por_transacao", Map.of("custo_maximo_gas_bnb_wei", custoMaximo.toString(),
							"teto_gas_tx_bnb_wei", TETO_GAS_TX.toString(), "gas_estimado", gasEstimado.toString(),
							"gas_limite_assinado", gasFinal));
			LOG.info(() -> "disparo: gás caro (" + bnb(custoMaximo) + " BNB > teto por tx) — pulo esta");
			return;
		}

		long nonceOperadorUsado = nonce;
		long nonceAtualizadoUsado = nonceAtualizadoEmMs;
		byte[] raw = AssinadorDeTransacao.assinar(nonceOperadorUsado, custoDeGas.prioridade(), maxTaxa, gasFinal,
				HexFormat.of().parseHex(CONTRATO), BigInteger.ZERO, calldata, privada.get());

		// O hash da NOSSA tx (keccak do cru), para achar o recibo depois — vale para
		// bundle e público.
		String nossoHash = "0x" + HexFormat.of().formatHex(Keccak.hash(raw));
		String nossoRaw = HexFormat.of().formatHex(raw);

		// A tx do pagamento: BNB puro ao builder, nonce seguinte, mesmo preço de gás — uma por builder, cada
		// uma ao endereço dele (só o bloco de um deles inclui, então só uma sai).
		boolean paga = pagamentoBnb.signum() > 0;
		boolean aceito;
		long milisAteAceiteRelay = 0;
		Map<String, Object> respostasRelay = new LinkedHashMap<>();

		if (relay.ativo()) {
			// Bundle `[alvo, nossa, pagamento]` mira o PRÓXIMO bloco: a nossa roda no estado que o swap alvo
			// abriu. Sem alvo (caminho confirmado), vai a nossa, ainda privada.
			java.util.function.Function<byte[], List<String>> montar = destino -> {
				List<String> bundle = new java.util.ArrayList<>();
				if (cruaPendente != null) {
					bundle.add("0x" + HexFormat.of().formatHex(cruaPendente));
				}
				bundle.add("0x" + nossoRaw);
				if (paga) {
					bundle.add("0x" + HexFormat.of().formatHex(AssinadorDeTransacao.assinar(nonceOperadorUsado + 1,
							custoDeGas.prioridade(), maxTaxa, GAS_DO_PAGAMENTO, destino, pagamentoBnb, new byte[0],
							privada.get())));
				}
				return bundle;
			};
			long antesDeAguardarRelay = (System.nanoTime() - inicio) / 1_000_000;
			RelayPrivado.Envio envio = relay.enviar(montar);
			aceito = envio.aceito();
			milisAteAceiteRelay = antesDeAguardarRelay + envio.latenciaAtePrimeiraAceitacaoMs();
			envio.porBuilder().forEach((nome, resposta) -> {
				Map<String, Object> detalhe = new LinkedHashMap<>();
				detalhe.put("aceito", resposta.aceito());
				detalhe.put("motivo", resposta.motivo());
				detalhe.put("bundle", resposta.bundle());
				detalhe.put("latencia_resposta_ms", resposta.latenciaMs());
				detalhe.put("status_http", resposta.statusHttp());
				detalhe.put("codigo_erro_json_rpc", resposta.codigoErro());
				detalhe.put("mensagem_erro_completa", resposta.mensagemErro());
				detalhe.put("dados_erro", resposta.dadosErro());
				detalhe.put("resposta_bruta", resposta.respostaBruta());
				detalhe.put("resposta_truncada", resposta.respostaTruncada());
				detalhe.put("hash_transacao_falha", resposta.hashTransacaoFalha());
				detalhe.put("indice_transacao_falha", resposta.indiceTransacaoFalha());
				detalhe.put("seletor_revert", resposta.seletorRevert());
				detalhe.put("texto_revert", resposta.textoRevert());
				detalhe.put("nonce_operador_usado", nonceOperadorUsado);
				detalhe.put("nonce_operador_atualizado_epoch_ms", nonceAtualizadoUsado);
				detalhe.put("nonce_pagamento_usado", paga ? nonceOperadorUsado + 1 : null);
				detalhe.put("transacoes_bundle", transacoesDoBundle(resposta.hashesTransacoes(), cruaPendente != null,
						paga, nonceOperadorUsado, resposta.indiceTransacaoFalha()));
				respostasRelay.put(nome, detalhe);
			});

			// Cada recusa conta com o nome do builder: "nonce too low" na 48club e aceito na BlockRazor são
			// histórias diferentes.
			envio.porBuilder().forEach((nome, resposta) -> {
				if (!resposta.aceito()) {
					recusasDoRelay.computeIfAbsent(nome + ": " + resposta.motivo(), k -> new AtomicLong()).incrementAndGet();
					// No N+1 a simulação do relay é a única que roda COM o alvo: o token que não se move ali
					// vai para a sonda como no veredito da cauda.
					if (naoSeMove(resposta.motivo())) {
						suspeitarDaRota(rota, resposta.motivo());
					}
					// Quarentena imediata: relay confirmou o revert antes de a autopsia assíncrona terminar —
					// sem isso o mesmo bloco pode re-despachar a mesma rota ~27× antes de a quarentena aplicar.
					String motivoRelay = Autopsia.motivoDoVeredito(resposta.motivo()).trim();
					if (!motivoRelay.isBlank() && chamadaFinal != null) {
						String razao = "relay " + nome + " recusou (" + motivoRelay + ")";
						if (Autopsia.proibicao(motivoRelay)) {
							quarentena.condenar(HexFormat.of().parseHex(chamadaFinal.rota().get(0).pool()),
									Autopsia.PENA_DE_PROIBICAO, razao);
						} else if (Autopsia.overflow(motivoRelay)) {
							chamadaFinal.rota().stream().filter(s -> !s.v3() && !s.deGerente())
									.forEach(s -> quarentena.condenar(HexFormat.of().parseHex(s.pool()),
											Autopsia.PENA_DE_ESTADO, razao));
						} else {
							// Qualquer outro revert (TRANSFER_FAILED, NaoPagou, etc.): quarentena curta na
							// emprestadora, evita re-despacho até a autopsia assíncrona terminar (~3 s).
							quarentena.condenar(HexFormat.of().parseHex(chamadaFinal.rota().get(0).pool()),
									Duration.ofMinutes(30), razao);
						}
					}
				}
			});

			if (paga) {
				synchronized (this) {
					pagamentosOferecidos.incrementAndGet();
					pagamentoOferecidoTotal = pagamentoOferecidoTotal.add(pagamentoBnb);
				}
			}
		} else {
			somarGasto(custoMaximo); // só o público pode reverter pagando gás
			aceito = rpc.enviar(nossoRaw).isPresent();
			respostasRelay.put("rpc_publico", Map.of("aceito", aceito));
		}

		long milis = (System.nanoTime() - inicio) / 1_000_000;
		milisPorCaminho.computeIfAbsent(cruaPendente == null ? "cauda" : "n+1",
				k -> new br.com.node.util.Amostras(2_000)).anotar(milis);

		long tempoAteConfirmacao = aceito && relay.ativo() ? milisAteAceiteRelay : milis;
		Map<String, Object> tempo = anotarLinhaDoTempo(achado.altura(), entradaMs, tempoAteConfirmacao,
				cruaPendente == null ? "cauda" : "n+1");

		synchronized (this) {
			if (aceito) {
				enviadas++;
				ultimoEnvio = nossoHash;
				medidor.anotar(gasEstimado, nossoHash);
			} else {
				recusadas++;
			}
		}

		Map<String, Object> detalhesEnvio = detalhesEnvio(achado, chamadaFinal, rota, piso.get(), fracaoMilha,
				ofertadoWeiPorGas, necessarioBnbSemLimiteDeSaldo, necessarioBnb, saldoConhecido, limitadoPorSaldo, necessarioRaiz,
				pagamentoRaiz, pagamentoBnb, precoDoGas, gasDaCauda, gasEstimado, gasFinal, maxTaxa,
				cruaPendente == null ? "cauda" : "n+1", tempoAteConfirmacao, milis, tempo, respostasRelay, aceito, solicitadoAoBuilder,
				limitadoPorLucro, limitadoPeloTeto, semLoan);
		if (aceito) {
			java.util.Set<String> poolsDaRota = new java.util.HashSet<>();
			achado.ciclo().pernas().forEach(p -> poolsDaRota.add(HexFormat.of().formatHex(p.pool())));
			aoEnviar.accept(new Desfechos.Envio(nossoHash, achado.altura(), poolsDaRota, entradaMs + tempoAteConfirmacao,
					cruaPendente == null ? "cauda" : "n+1", rotaDoLance, fracaoMilha, ofertadoWeiPorGas,
					lucroOportunidadeBnb, gasParaOLance, detalhesEnvio));
		} else {
			aoRecusarTentativa.accept(nossoHash, detalhesEnvio);
		}

		autopsiar(achado, chamadaFinal, calldataFinal, aceito ? "enviada" : "recusada_relay", cruaPendente,
				aceito ? nossoHash : null, nossoHash);
	}

	private void registrarDropSemEnvio(Achado achado, String rotaId, int fracaoMilha, BigInteger ofertado,
			BigInteger piso, RotaDoModelo.Chamada chamada, List<Salto> rota, String motivo,
			Map<String, Object> detalhes) {
		Map<String, Object> tentativa = new LinkedHashMap<>(detalhes);
		tentativa.put("altura", achado.altura());
		tentativa.put("caminho", "cauda_ou_n+1");
		tentativa.put("rota_id", rotaId);
		tentativa.put("pools", achado.ciclo().pernas().stream()
				.map(perna -> HexFormat.of().formatHex(perna.pool())).sorted().toList());
		tentativa.put("raiz_ativo", HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()));
		tentativa.put("montante_entrada_wei", chamada.montante().toString());
		tentativa.put("lucro_previsto_ativo_wei", achado.oportunidade().lucro().toString());
		tentativa.put("lucro_minimo_ativo_wei", chamada.lucroMinimo().toString());
		tentativa.put("piso_ativo_wei", piso.toString());
		tentativa.put("lance_fracao_milha", fracaoMilha);
		tentativa.put("lance_ofertado_total_wei_por_gas", ofertado.toString());
		tentativa.put("motivo_nao_enviado", motivo);
		tentativa.put("pernas", rota.stream().map(salto -> Map.of("pool", salto.pool(), "tipo",
				salto.v3() ? "V3" : salto.deGerente() ? "gerente" : "V2", "token_entrada", salto.entra(), "token_saida",
				salto.sai(), "taxa_bps", salto.taxaBps())).toList());
		tentativa.put("relay_aceitou", null);
		tentativa.put("tempo", Map.of("marcos_disponiveis", false));
		aoRecusarTentativa.accept("drop:" + achado.altura() + ":" + rotaId + ":" + sequenciaDeDrops.incrementAndGet(),
				tentativa);
	}

	// A autópsia de um achado que o piso RECUSOU: nada se assina, nada se envia, e o veredito do contrato
	// não é pedido (ele diria SemLucro pelo próprio piso e sujaria a contagem dos motivos reais). Só o
	// laudo perna a perna — que não exige chave nem autorização, então roda também sem keystore.
	public void examinarSemEnviar(Achado achado) {

		byte[] raiz = achado.ciclo().pernas().get(0).entra();
		BigInteger gas = custoDeGas.estimarGasDasTravessias(achado.travessias(), achado.ciclo().pernas());
		Optional<RotaDoModelo.Chamada> chamada = custoDeGas.piso(raiz, achado.oportunidade().entrada(), gas)
				.flatMap(piso -> RotaDoModelo.traduzir(achado, catalogo, tarifario, piso));

		if (chamada.isEmpty()) {
			return;
		}

		String de = endereco != null ? endereco : "0000000000000000000000000000000000000001";

		autopsia.submit(() -> {
			try {
				laudos.examinar(de, CONTRATO, achado, chamada.get(), "sombra", "nao_pedido");
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("autópsia sombra", e);
			}
		});
	}

	// POST-MORTEM assíncrono (fora do caminho de envio, zero latência): pergunta ao contrato, via
	// eth_call, POR QUE esta arb não fecha — o seletor do revert e a FRASE dele — e, na cauda, pede o
	// laudo perna a perna. No N+1 o laudo não vale: roda no estado sem o alvo, que é justamente o que a
	// nossa tx não vai encontrar.
	private void autopsiar(Achado achado, RotaDoModelo.Chamada chamada, byte[] calldata, String desfecho,
			byte[] cruaPendente, String hashEnviado, String hashTentativa) {

		String dadosHex = HexFormat.of().formatHex(calldata);

		autopsia.submit(() -> {

			// No N+1 o veredito roda SEM o alvo: "NaoPagou" ali é o esperado (a fenda só existe depois do
			// swap alheio) e não diz nada — contá-lo junto com os da cauda fazia parecer erro novo.
			if (cruaPendente != null) {
				motivos.computeIfAbsent("n+1 (veredito sem o alvo, não informa)", k -> new AtomicLong())
						.incrementAndGet();
				laudos.concluir(hashTentativa,
						Map.<String, Object>of("status", "nao_aplicavel_sem_transacao_alvo"));
				return;
			}

			String veredito = anotarVeredito(chamada.rota(), dadosHex, achado.altura());

			// O desfecho precisa saber se a fenda FECHAVA: bundle que reverteria o builder descarta calado, e
			// contá-lo como "perdeu para rival" ensinaria o lance errado (27/09: 80% das rotas por gerente).
			if (hashEnviado != null) {
				aoVeredito.accept(hashEnviado, veredito);
			}

			try {
				laudos.examinar(endereco, CONTRATO, achado, chamada, desfecho, veredito, hashTentativa);
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("autópsia do envio", e);
				laudos.concluir(hashTentativa,
						Map.<String, Object>of("status", "falha_na_autopsia", "erro", e.getClass().getSimpleName()));
			}
		});
	}

	// Conta o veredito do contrato por motivo, para o relatório dizer POR QUE os
	// envios não fecham — e
	// transforma o veredicto em conhecimento: o revert que nomeia a transferência
	// que não passou tira
	// o par do grafo na hora (via `Impostos.suspeitar`) e põe a sonda a remedi-lo.
	// O RPC do veredito: o de estado histórico (`RPC_LOGS`), quando ligado pelo `Main`.
	private volatile RpcDoEnsaio rpcDoVeredito;

	public void vereditoPor(RpcDoEnsaio rpc) {
		this.rpcDoVeredito = rpc;
	}

	private final AtomicLong vereditoSemEstadoDoBloco = new AtomicLong();

	private String anotarVeredito(List<Salto> rota, String dadosHex, long altura) {

		try {

			// No BLOCO N da fenda, e esperando o RPC recebê-lo (vemos o bloco antes dele), como a autópsia.
			RpcDoEnsaio fonte = rpcDoVeredito != null ? rpcDoVeredito : rpc;
			String bloco = "0x" + Long.toHexString(altura);
			// Só pede quando o nó JÁ tem o bloco N: antes disso ele simula no N−1 sem avisar.
			if (!fonte.esperarBloco(altura, 15, 200)) {
				vereditoSemEstadoDoBloco.incrementAndGet();
				return "sem_veredito";
			}
			RpcDoEnsaio.Veredicto veredicto = fonte.veredito(endereco, CONTRATO, dadosHex, bloco);
			for (int tentativa = 0; tentativa < 3 && Autopsia.blocoAindaNaoChegou(veredicto.texto()); tentativa++) {
				Thread.sleep(700);
				veredicto = fonte.veredito(endereco, CONTRATO, dadosHex, bloco);
			}
			// Sem o estado do bloco N não há veredito — e "não sei" não pode virar "inválida" nem condenar rota.
			if (Autopsia.blocoAindaNaoChegou(veredicto.texto()) || Autopsia.estadoIndisponivel(veredicto.texto())) {
				vereditoSemEstadoDoBloco.incrementAndGet();
				return "sem_veredito";
			}
			String nome = veredicto.texto().isBlank() ? NOMES.getOrDefault(veredicto.seletor(), veredicto.seletor())
					: veredicto.texto();
			motivos.computeIfAbsent(nome, chave -> new AtomicLong()).incrementAndGet();

			if (naoSeMove(veredicto.texto())) {
				suspeitarDaRota(rota, veredicto.texto());
			}

			condenarGerentesSeOTokenNaoSeMove(rota, veredicto.seletor(), nome);

			return nome;

		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("veredito do contrato", e);
			return "sem_veredito";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "sem_veredito";
		}
	}

	// O veredicto diz que o TOKEN não se move por esta rota? É a assinatura das
	// pools quando o token
	// devolve falso ou reverte na transferência: pausado, lista negra, taxa que
	// zera a saída. Não é a
	// nossa calldata errada nem corrida perdida — é rota que nunca ia fechar, com
	// aquele token, para
	// ninguém (a mesma leitura do old, 41 vezes medidas). EmprestimoNaoV2 NÃO entra
	// aqui: é defeito
	// nosso, da ponta V3, e condenar token por ele seria jogar a culpa no inocente.
	static boolean naoSeMove(String texto) {

		String minusculo = texto == null ? "" : texto.toLowerCase(java.util.Locale.ROOT);

		return minusculo.contains("transfer_failed") || minusculo.contains("transfer_from_failed");
	}

	// Planta a suspeita nos DOIS tokens de CADA perna, porque o revert da pool não
	// diz qual deles
	// travou — e a sonda, que simula a transferência contra o estado real, decide
	// qual volta. Errar
	// para o lado de suspeitar demais só custa uma remedição; errar para menos é
	// repetir o revert.
	private void suspeitarDaRota(List<Salto> rota, String motivo) {

		for (Salto salto : rota) {
			// Na perna de gerente `pool` é o gerente de todas: suspeitar ali não tira pool nenhuma do grafo.
			if (salto.deGerente()) {
				continue;
			}
			byte[] pool = HexFormat.of().parseHex(semPrefixo(salto.pool()));
			impostos.suspeitar(pool, HexFormat.of().parseHex(semPrefixo(salto.entra())));
			impostos.suspeitar(pool, HexFormat.of().parseHex(semPrefixo(salto.sai())));
		}

		LOG.warning(() -> "disparo: a cadeia diz que o token não se move (" + motivo + ") — " + rota.size()
				+ " pools postas sob suspeita; a sonda remede, e o grafo as solta ou condena");
	}

	// A ROTA POR GERENTE QUE MORRE PORQUE UM TOKEN NÃO SE MOVE. Medido na noite de 26/09 no servidor (7,6 h):
	// 43 vereditos `WrappedError` da V4 (o `transfer` do token 0x51ea…99999 dando divisão por zero dentro da
	// sessão) e 27 `FalhaNaChamada` do Executor, com as MESMAS rotas fantasmas (lucro "previsto" de 2,77 e 65,8)
	// voltando a cada poucos blocos por horas — a autópsia de rota por gerente só registrava o veredito, e nada
	// virava conhecimento. Agora a pool de gerente sai do grafo: no `WrappedError`, que NOMEIA o token, só as
	// pools da rota que têm aquele token; na `FalhaNaChamada`, todas as de gerente da rota.
	private static final java.time.Duration PENA_DO_TOKEN_QUE_NAO_SE_MOVE = java.time.Duration.ofHours(3);
	private static final String WRAPPED_ERROR = "90bfb865";
	private static final String FALHA_NA_CHAMADA = "033d7ade";
	// SafeCast.toInt128 dentro do V4 — pool com token taxado (fee-on-transfer); reverte sempre.
	private static final String SAFE_CAST_OVERFLOW = "93dafdf1";

	private void condenarGerentesSeOTokenNaoSeMove(List<Salto> rota, String seletor, String veredito) {

		String texto = veredito == null ? "" : veredito.toLowerCase(java.util.Locale.ROOT);
		boolean embrulhado = WRAPPED_ERROR.equals(seletor) || texto.startsWith("0x" + WRAPPED_ERROR);
		boolean falhou = FALHA_NA_CHAMADA.equals(seletor) || texto.contains(FALHA_NA_CHAMADA) || naoSeMove(veredito);
		boolean safeCast = SAFE_CAST_OVERFLOW.equals(seletor) || texto.contains(SAFE_CAST_OVERFLOW);

		if ((!embrulhado && !falhou && !safeCast) || rota.stream().noneMatch(Salto::deGerente)) {
			return;
		}

		// `WrappedError(address alvo, bytes4, bytes, bytes)`: o alvo é o token que não se moveu.
		String culpado = embrulhado && texto.length() >= 2 + 8 + 64 ? texto.substring(2 + 8 + 24, 2 + 8 + 64) : null;
		int condenadas = 0;

		for (Salto salto : rota) {

			if (!salto.deGerente()) {
				continue;
			}

			String chave = HexFormat.of().formatHex(salto.chave());

			if (culpado != null && !chave.contains(culpado)) {
				continue;
			}

			String motivo = embrulhado ? "WrappedError do token 0x" + culpado
					: safeCast ? "SafeCastOverflow (token taxado em pool V4)"
					: "FalhaNaChamada";
			quarentena.condenar(Keccak.hash(salto.chave()), PENA_DO_TOKEN_QUE_NAO_SE_MOVE,
					motivo + " em rota por gerente");
			condenadas++;
		}

		int total = condenadas;
		if (total > 0) {
			LOG.warning(() -> "disparo: token que não se move em rota por gerente — " + total + " pool(s) de gerente em quarentena"
					+ (culpado != null ? " (token 0x" + culpado + ")" : ""));
		}
	}

	// O limite de gás a assinar: a estimativa (exata da EVM ou o teto calibrado)
	// com a folga. A
	// divisão inteira trunca para BAIXO, então o teto vem do produto — nunca
	// assinamos menos gas do
	// que a estimativa pediu por causa de arredondamento.
	private static long comFolgaDeLimite(BigInteger gas) {
		return gas.multiply(BigInteger.valueOf(GAS_LIMIT_MARGEM_NUM)).divide(BigInteger.valueOf(GAS_LIMIT_MARGEM_DEN))
				.longValueExact();
	}

	private int lerVersao() {

		try {

			com.google.gson.JsonObject chamada = new com.google.gson.JsonObject();
			chamada.addProperty("to", "0x" + CONTRATO);
			chamada.addProperty("data", "0x" + HexFormat.of().formatHex(AbiDoExecutor.VERSAO));
			com.google.gson.JsonArray parametros = new com.google.gson.JsonArray();
			parametros.add(chamada);
			parametros.add("latest");

			return rpc.chamar("eth_call", parametros).map(com.google.gson.JsonParser::parseString)
					.map(com.google.gson.JsonElement::getAsJsonObject).filter(o -> o.has("result"))
					.map(o -> o.get("result").getAsString()).filter(r -> r.length() > 2)
					.map(r -> new BigInteger(r.substring(2), 16).intValue())
					// Sem versão NÃO se adivinha: tratar como antigo mudava em silêncio quais rotas saem.
					.orElseThrow(() -> new IllegalStateException("versão do Executor 0x" + CONTRATO
							+ " não veio do RPC — não subo sem saber que contrato estou chamando"));

		} catch (RuntimeException e) {
			throw new IllegalStateException("versão do Executor 0x" + CONTRATO + " ilegível: " + e.getMessage(), e);
		}
	}

	public int versaoDoExecutor() {
		return versaoDoExecutor;
	}

	private static String semPrefixo(String hexadecimal) {
		return hexadecimal.startsWith("0x") ? hexadecimal.substring(2) : hexadecimal;
	}

	// Relê o nonce pending do operador para o cache — chamado periodicamente
	// (thread agendada), FORA
	// do caminho de envio. Sem chave, não faz nada.
	public void atualizarNonce() {

		if (privada.isEmpty()) {
			return;
		}

		try {
			nonce = rpc.contagemDeTransacoes(endereco);
			nonceAtualizadoEmMs = System.currentTimeMillis();
		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("nonce do operador", e);
		}

		try {
			saldoOperador = rpc.saldoDe(endereco);
		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("saldo do operador", e);
		}

		try {
			saldoExecutorWBNB = rpc.saldoErc20(WBNB, CONTRATO);
		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("saldo WBNB do executor", e);
		}
	}

	public synchronized long enviadas() {
		return enviadas;
	}

	public synchronized long recusadas() {
		return recusadas;
	}

	public Map<String, Long> recusasDoRelay() {
		Map<String, Long> copia = new LinkedHashMap<>();
		recusasDoRelay.forEach((motivo, n) -> copia.put(motivo, n.get()));
		return copia;
	}

	// Mediana e máximo do tempo de disparo por caminho, e a linha do tempo dos envios por etapa.
	public Map<String, Object> milis() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		milisPorCaminho.forEach((caminho, amostras) -> resumo.put(caminho, amostras.resumo()));
		Map<String, Object> etapas = new LinkedHashMap<>();
		new java.util.TreeMap<>(etapasDoEnvio).forEach((etapa, amostras) -> etapas.put(etapa, amostras.resumo()));
		resumo.put("linha_do_tempo", etapas);
		synchronized (this) {
			resumo.put("pagamentos_ao_builder_oferecidos", pagamentosOferecidos.get());
			resumo.put("pagamento_oferecido_total_bnb", bnb(pagamentoOferecidoTotal));
			resumo.put("lance_limitado_pelo_saldo", lanceLimitadoPeloSaldo.get());
			resumo.put("veredito_sem_estado_do_bloco", vereditoSemEstadoDoBloco.get());
			resumo.put("lance_limitado_pelo_lucro", lanceLimitadoPeloLucro.get());
			resumo.put("saldo_operador_bnb", saldoOperador == null ? "nunca lido" : bnb(saldoOperador));
			resumo.put("saldo_executor_wbnb", bnb(saldoExecutorWBNB));
		}
		return resumo;
	}

	public void medirCom(
			java.util.function.LongFunction<java.util.Optional<br.com.node.sincronizacao.Cabeca.Momentos>> momentos) {
		this.momentos = momentos;
	}

	private static Map<String, Object> detalhesEnvio(Achado achado, RotaDoModelo.Chamada chamada, List<Salto> rota,
			BigInteger piso, int fracaoMilha, BigInteger ofertado, BigInteger necessarioBnbSemLimiteDeSaldo,
			BigInteger necessarioBnb, boolean saldoConhecido, boolean limitadoPorSaldo, BigInteger necessarioRaiz, BigInteger pagamentoRaiz,
			BigInteger pagamentoBnb, BigInteger precoDoGas, BigInteger gasDaCauda, BigInteger gasEstimado, long gasFinal,
			BigInteger maxTaxa, String caminho, long tempoAteConfirmacao, long milisTotal, Map<String, Object> tempo,
			Map<String, Object> respostasRelay, boolean aceito, boolean solicitadoAoBuilder, boolean limitadoPorLucro,
			boolean limitadoPeloTeto, boolean semLoan) {

		Map<String, Object> detalhes = new LinkedHashMap<>();
		detalhes.put("altura", achado.altura());
		detalhes.put("caminho", caminho);
		detalhes.put("sem_loan", semLoan);
		detalhes.put("rota_id", chaveDaRota(achado));
		detalhes.put("pools", achado.ciclo().pernas().stream()
				.map(perna -> HexFormat.of().formatHex(perna.pool())).sorted().toList());
		boolean envioPrivado = !respostasRelay.containsKey("rpc_publico");
		detalhes.put("submissao_aceita", aceito);
		detalhes.put("relay_aceitou", envioPrivado ? aceito : null);
		detalhes.put("raiz_ativo", HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()));
		detalhes.put("montante_entrada_wei", chamada.montante().toString());
		detalhes.put("saida_prevista_wei", achado.oportunidade().saida().toString());
		detalhes.put("lucro_previsto_ativo_wei", achado.oportunidade().lucro().toString());
		detalhes.put("lucro_minimo_ativo_wei", chamada.lucroMinimo().toString());
		detalhes.put("piso_ativo_wei", piso.toString());
		detalhes.put("pernas", rota.stream().map(salto -> {
			Map<String, Object> perna = new LinkedHashMap<>();
			perna.put("pool", salto.pool());
			perna.put("tipo", salto.v3() ? "V3" : salto.deGerente() ? "gerente" : "V2");
			perna.put("token_entrada", salto.entra());
			perna.put("token_saida", salto.sai());
			perna.put("taxa_bps", salto.taxaBps());
			return perna;
		}).toList());

		Map<String, Object> lance = new LinkedHashMap<>();
		lance.put("fracao_milha", fracaoMilha);
		lance.put("ofertado_total_wei_por_gas", ofertado.toString());
		lance.put("preco_gas_proprio_wei_por_gas", precoDoGas.toString());
		lance.put("pagamento_direto_builder_bnb_wei", pagamentoBnb.toString());
		lance.put("pagamento_direto_builder_ativo_raiz_wei", pagamentoRaiz.toString());
		lance.put("necessario_ativo_raiz_wei", necessarioRaiz.toString());
		lance.put("necessario_bnb_sem_limite_saldo_wei", necessarioBnbSemLimiteDeSaldo.toString());
		lance.put("necessario_bnb_apos_limite_saldo_wei", necessarioBnb.toString());
		lance.put("estimativa_gas_para_bid", gasDaCauda.toString());
		lance.put("gas_limite_para_bid", gasDaCauda.multiply(BigInteger.valueOf(6)).divide(BigInteger.valueOf(5)).toString());
		lance.put("teto_builder_bnb_wei", TETO_PAGAMENTO.toString());
		lance.put("solicitado_ao_builder", solicitadoAoBuilder);
		lance.put("saldo_operador_conhecido", saldoConhecido);
		lance.put("limitado_por_saldo", limitadoPorSaldo);
		lance.put("limitado_por_lucro", limitadoPorLucro);
		lance.put("limitado_pelo_teto_builder", limitadoPeloTeto);
		detalhes.put("lance", lance);

		Map<String, Object> gas = new LinkedHashMap<>();
		gas.put("estimado", gasEstimado.toString());
		gas.put("limite_assinado", gasFinal);
		gas.put("max_fee_wei_por_gas", maxTaxa.toString());
		gas.put("priority_fee_wei_por_gas", precoDoGas.toString());
		detalhes.put("gas", gas);

		Map<String, Object> transporte = new LinkedHashMap<>();
		transporte.put("tipo", respostasRelay.containsKey("rpc_publico") ? "rpc_publico" : "bundle_privado");
		transporte.put("caminho", caminho);
		transporte.put("quantidade_tx_bundle", respostasRelay.containsKey("rpc_publico") ? 1
				: (caminho.equals("n+1") ? 2 : 1) + (pagamentoBnb.signum() > 0 ? 1 : 0));
		transporte.put("tempo_ate_primeira_aceitacao_ms", tempoAteConfirmacao);
		transporte.put("tempo_ate_todas_as_respostas_ms", milisTotal);
		transporte.put("respostas_por_builder", respostasRelay);
		detalhes.put("transporte", transporte);
		detalhes.put("tempo", tempo);
		return detalhes;
	}

	private static List<Map<String, Object>> transacoesDoBundle(List<String> hashes, boolean temAlvo, boolean paga,
			long nonceOperador, Integer indiceFalha) {
		List<String> papeis = new java.util.ArrayList<>();
		if (temAlvo) {
			papeis.add("transacao_alvo_mempool");
		}
		papeis.add("arbitragem_operador");
		if (paga) {
			papeis.add("pagamento_builder");
		}
		List<Map<String, Object>> resultado = new java.util.ArrayList<>();
		for (int indice = 0; indice < hashes.size(); indice++) {
			Map<String, Object> transacao = new LinkedHashMap<>();
			transacao.put("indice", indice);
			transacao.put("papel", indice < papeis.size() ? papeis.get(indice) : "desconhecido");
			transacao.put("hash", hashes.get(indice));
			transacao.put("nonce", "arbitragem_operador".equals(transacao.get("papel")) ? nonceOperador
					: "pagamento_builder".equals(transacao.get("papel")) ? nonceOperador + 1 : null);
			transacao.put("indicada_como_falha_pelo_relay",
					indiceFalha != null && indiceFalha.intValue() == indice);
			resultado.add(transacao);
		}
		return resultado;
	}

	// Uma linha por envio: onde foi o tempo, do bloco feito à resposta do relay. O bloco seguinte sai
	// ~450 ms depois do da fenda; o que passar disso chega tarde para ele.
	private Map<String, Object> anotarLinhaDoTempo(long altura, long entradaMs, long relayMs, String caminho) {

		java.util.Optional<br.com.node.sincronizacao.Cabeca.Momentos> m = momentos.apply(altura);
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("inicio_disparo_epoch_ms", entradaMs);
		linha.put("confirmacao_relay_epoch_ms", entradaMs + relayMs);
		linha.put("inicio_disparo_ate_confirmacao_ms", relayMs);

		if (m.isEmpty()) {
			linha.put("marcos_disponiveis", false);
			return linha;
		}

		long bloco = m.get().blocoMs();
		long anuncio = m.get().anuncioMs();
		long aplicado = m.get().aplicadoMs();
		long total = entradaMs + relayMs - bloco;

		linha.put("marcos_disponiveis", true);
		linha.put("bloco_ms", bloco);
		linha.put("anuncio_ms", anuncio > 0 ? anuncio : null);
		linha.put("aplicado_ms", aplicado > 0 ? aplicado : null);
		linha.put("bloco_ate_anuncio_ms", anuncio > 0 ? anuncio - bloco : null);
		linha.put("anuncio_ate_aplicado_ms", anuncio > 0 ? aplicado - anuncio : null);
		linha.put("aplicado_ate_decisao_ms", aplicado > 0 ? entradaMs - aplicado : null);
		linha.put("bloco_ate_resposta_relay_ms", total);

		etapa(caminho + ".1_bloco_ate_anuncio", anuncio > 0 ? anuncio - bloco : -1);
		etapa(caminho + ".2_anuncio_ate_aplicado", anuncio > 0 ? aplicado - anuncio : -1);
		etapa(caminho + ".3_aplicado_ate_decisao", aplicado > 0 ? entradaMs - aplicado : -1);
		etapa(caminho + ".4_decisao_ate_relay", relayMs);
		etapa(caminho + ".5_total_bloco_ate_relay", total);

		return linha;
	}

	private void etapa(String nome, long ms) {
		if (ms >= 0) {
			etapasDoEnvio.computeIfAbsent(nome, k -> new br.com.node.util.Amostras(2_000)).anotar(ms);
		}
	}

	public Map<String, Object> autopsias() {
		return laudos.resumo();
	}

	// O hash da última tx enviada de verdade, ou vazio se nada saiu — vai no jsonl
	// para a gente
	// conferir o recibo na cadeia depois que o node parar.
	public synchronized String ultimoEnvio() {
		return ultimoEnvio == null ? "" : ultimoEnvio;
	}

	// O veredito do contrato por motivo (EmprestimoNaoV2, NaoPagou, SemLucro, ok…)
	// — POR QUE os envios
	// não fecham. É o fim da cegueira: o relatório mostra a causa, não só
	// "recusado".
	public Map<String, Long> motivos() {

		Map<String, Long> copia = new LinkedHashMap<>();
		motivos.forEach((nome, n) -> copia.put(nome, n.get()));
		return copia;
	}

	// O placar da precisão do gás: estimado × medido pelo recibo (amostras, medianas, desvio). Vai no
	// relatório para que a calibração seja CONFERIDA no ar, não argumentada no código.
	public Map<String, Object> gasMedido() {
		return medidor.resumo();
	}

	@Override
	public void close() {
		autopsia.shutdownNow();
		laudos.close();
		medidor.close();
		privada.ifPresent(
				p -> LOG.info(() -> "disparo encerrado: " + enviadas + " enviadas, " + recusadas + " recusadas"));
	}

	private synchronized boolean tetoDiarioAtingido() {

		reiniciarDiaSeVirou();

		if (!relay.ativo() && gastoNoDia.compareTo(TETO_DIA) >= 0) {
			LOG.warning(() -> "disparo: teto diário de gás atingido (" + bnb(gastoNoDia) + " BNB) — parado por hoje");
			return true;
		}

		return false;
	}

	private synchronized void somarGasto(BigInteger custo) {
		gastoNoDia = gastoNoDia.add(custo);
	}

	private void reiniciarDiaSeVirou() {

		long hoje = LocalDate.now().toEpochDay();

		if (hoje != dia) {
			dia = hoje;
			gastoNoDia = BigInteger.ZERO;
		}
	}

	// Carrega a chave do keystore cifrado + a senha da variável de ambiente. Sem
	// keystore ou sem
	// senha, o Disparo fica inerte (nunca envia) — falha fechada, nunca aberta.
	private static Optional<BigInteger> carregarChave() {

		String senha = System.getenv("OPERADOR_SENHA");

		if (!Files.exists(KEYSTORE) || senha == null || senha.isBlank()) {
			return Optional.empty();
		}

		try {
			return Optional.of(new BigInteger(1, Keystore.decifrar(Files.readString(KEYSTORE), senha.toCharArray())));
		} catch (RuntimeException | java.io.IOException e) {
			// Mandaram armar e a chave não abre: isso é erro de operação, não "fica inerte" calado.
			if ("SIM".equals(System.getenv("ARB_ARMADO"))) {
				throw new IllegalStateException("ARB_ARMADO=SIM e o keystore não abriu: " + e.getMessage(), e);
			}
			LOG.warning(() -> "disparo: keystore não abriu (" + e.getMessage() + ") — inerte");
			return Optional.empty();
		}
	}

	private static String bnb(BigInteger wei) {
		return new java.math.BigDecimal(wei).divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)))
				.setScale(6, java.math.RoundingMode.DOWN).toPlainString();
	}
}
