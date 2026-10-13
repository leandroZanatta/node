package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import br.com.node.catalogo.Achado;
import br.com.node.nucleo.Arbitragem;
import br.com.node.nucleo.Perna;

// O MERCADO como gabarito: valida a detecção sem depender de chegar a tempo.
//
// Da nossa máquina o bundle perde a corrida (o relay fica a 150–280 ms de ida), então "incluído" não
// mede se o sistema acha as fendas certas. Mas os outros bots as pegam, e o observatório as vê
// confirmadas no bloco. Cada fenda que achamos fica anotada pelo CONJUNTO de pools; cada arbitragem
// alheia é conferida contra as anotações recentes:
//   - ACERTO: alguém executou nas mesmas pools depois de nós termos achado — a fenda era real, e o que
//     faltou foi velocidade. O lucro DELES é quanto ela valia.
//   - PERDIDA: arbitragem alheia sem nenhuma fenda nossa nas pools dela — o que não estamos vendo.
//   - Nossas fendas que ninguém executou — poeira, fantasma ou oportunidade que só nós vimos.
// N+1 e confirmado contam separado: são sensores diferentes, e a pergunta é qual deles presta.
public final class Gabarito {

	private static final Logger LOG = Logger.getLogger(Gabarito.class.getName());

	// Uma fenda nossa vale como previsão desta arbitragem se veio até JANELA_MS antes dela. Com bloco
	// de ~450 ms, 10 s cobrem a fenda N+1 (que precede o bloco) e as de alguns blocos de confirmado.
	private static final long JANELA_MS = 10_000;
	private static final int TETO = 20_000;

	private record Anotada(Set<String> pools, boolean n1, BigInteger lucro, String raiz, long instante) {
	}

	private final Deque<Anotada> recentes = new ArrayDeque<>();

	// COBERTURA: para cada pool de arbitragem alheia, está no nosso grafo? Se não, por quê. Medido em
	// 23/09: 75% das projeções N+1 não tinham caminho de volta no grafo, e esta é a pergunta seguinte —
	// as pools que os outros usam nós não temos, ou temos e recusamos?
	private final java.util.function.Function<byte[], String> motivoDeFora;
	private final Map<String, AtomicLong> poolsAlheiasPorMotivo = new LinkedHashMap<>();
	private final Map<String, AtomicLong> alheiasPorCobertura = new LinkedHashMap<>();

	public Gabarito() {
		this(pool -> "sem medida");
	}

	public Gabarito(java.util.function.Function<byte[], String> motivoDeFora) {
		this.motivoDeFora = motivoDeFora;
	}

	private final AtomicLong alheias = new AtomicLong();
	private final Map<String, BigInteger> valorAlheio = new LinkedHashMap<>();
	private final Map<String, AtomicLong> acertos = new LinkedHashMap<>();
	private final Map<String, Map<String, BigInteger>> valorAcertado = new LinkedHashMap<>();
	private final Map<String, AtomicLong> anotadas = new LinkedHashMap<>();
	private final br.com.node.util.Amostras adiantamentoN1 = new br.com.node.util.Amostras(2_000);

	public synchronized void anotar(Achado achado, boolean n1) {

		long agora = System.currentTimeMillis();
		recentes.addLast(new Anotada(pools(achado), n1, achado.oportunidade().lucro(),
				HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()), agora));
		anotadas.computeIfAbsent(sensor(n1), k -> new AtomicLong()).incrementAndGet();

		while (recentes.size() > TETO || (!recentes.isEmpty() && agora - recentes.peekFirst().instante() > JANELA_MS)) {
			recentes.removeFirst();
		}
	}

	// Alguma fenda nossa recente (dentro da janela) passou EXATAMENTE por estas pools? Para quem casa pelas
	// pernas reconstruídas (`AutopsiaDoMercado`), e não pelos emissores de log da tx alheia.
	public synchronized boolean viu(Set<String> pools) {

		long agora = System.currentTimeMillis();

		for (Anotada anotada : recentes) {
			if (agora - anotada.instante() <= JANELA_MS && anotada.pools().equals(pools)) {
				return true;
			}
		}

		return false;
	}

	public synchronized void confirmar(Arbitragem arbitragem, long altura) {

		long agora = System.currentTimeMillis();
		String token = HexFormat.of().formatHex(arbitragem.token());
		Set<String> delas = new TreeSet<>();
		arbitragem.pools().forEach(pool -> delas.add(HexFormat.of().formatHex(pool)));

		alheias.incrementAndGet();
		valorAlheio.merge(token, arbitragem.sobra(), BigInteger::add);

		int noGrafo = 0;
		for (byte[] pool : arbitragem.pools()) {
			String motivo = motivoDeFora.apply(pool);
			poolsAlheiasPorMotivo.computeIfAbsent(motivo, k -> new AtomicLong()).incrementAndGet();
			if (motivo.equals("no grafo")) {
				noGrafo++;
			}
		}
		String cobertura = noGrafo == arbitragem.pools().size() ? "todas no grafo"
				: noGrafo == 0 ? "nenhuma no grafo" : "parte no grafo";
		alheiasPorCobertura.computeIfAbsent(cobertura, k -> new AtomicLong()).incrementAndGet();

		// A mais recente de cada sensor que casa com o conjunto de pools dela.
		Anotada n1 = null;
		Anotada confirmado = null;

		for (Anotada anotada : recentes) {
			if (agora - anotada.instante() <= JANELA_MS && anotada.pools().equals(delas)) {
				if (anotada.n1()) {
					n1 = anotada;
				} else {
					confirmado = anotada;
				}
			}
		}

		for (Anotada casada : new Anotada[] { n1, confirmado }) {

			if (casada == null) {
				continue;
			}

			String sensor = sensor(casada.n1());
			acertos.computeIfAbsent(sensor, k -> new AtomicLong()).incrementAndGet();
			valorAcertado.computeIfAbsent(sensor, k -> new LinkedHashMap<>()).merge(token, arbitragem.sobra(),
					BigInteger::add);

			long antes = agora - casada.instante();

			if (casada.n1()) {
				adiantamentoN1.anotar(antes);
			}

			LOG.info(() -> "gabarito: ACERTO " + sensor + " — arbitragem alheia na altura " + altura + " nas pools "
					+ delas + " rendeu " + arbitragem.sobra() + " (token " + token + "); nós achamos " + antes
					+ " ms antes, prevendo " + casada.lucro() + " (raiz " + casada.raiz() + ")");
		}
	}

	public synchronized Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("arbitragens_alheias", alheias.get());
		resumo.put("valor_alheio_por_token", texto(valorAlheio));

		Map<String, Long> nossas = new LinkedHashMap<>();
		anotadas.forEach((k, v) -> nossas.put(k, v.get()));
		resumo.put("fendas_anotadas", nossas);

		Map<String, Long> acertou = new LinkedHashMap<>();
		acertos.forEach((k, v) -> acertou.put(k, v.get()));
		resumo.put("acertos", acertou);

		Map<String, Object> valor = new LinkedHashMap<>();
		valorAcertado.forEach((k, v) -> valor.put(k, texto(v)));
		resumo.put("valor_acertado_por_token", valor);

		resumo.put("n1_adiantamento_ms", adiantamentoN1.resumo());

		Map<String, Long> cobertura = new LinkedHashMap<>();
		alheiasPorCobertura.forEach((k, v) -> cobertura.put(k, v.get()));
		resumo.put("alheias_por_cobertura", cobertura);

		Map<String, Long> motivos = new LinkedHashMap<>();
		poolsAlheiasPorMotivo.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.forEach(e -> motivos.put(e.getKey(), e.getValue().get()));
		resumo.put("pools_alheias_por_motivo", motivos);

		return resumo;
	}

	private static Map<String, String> texto(Map<String, BigInteger> valores) {
		Map<String, String> copia = new LinkedHashMap<>();
		valores.forEach((k, v) -> copia.put(k, v.toString()));
		return copia;
	}

	private static String sensor(boolean n1) {
		return n1 ? "n+1" : "confirmado";
	}

	private static Set<String> pools(Achado achado) {
		Set<String> pools = new TreeSet<>();
		for (Perna perna : achado.ciclo().pernas()) {
			pools.add(HexFormat.of().formatHex(perna.pool()));
		}
		return pools;
	}
}
