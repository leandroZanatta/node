package br.com.node.ensaio;

import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import br.com.node.mempool.ObservatorioMempool;
import br.com.node.nucleo.Arbitragem;

// ONDE o gatilho de cada arbitragem alheia se perdeu no nosso funil.
//
// O gabarito mostrou que as pools estão no grafo (136 de 142 arbitragens alheias com TODAS as pools
// nele) e mesmo assim não achamos nenhuma: os outros fazem o backrun no MESMO bloco do swap que abre a
// fenda, e para nós concorrermos é preciso ter visto esse swap pendente. A pergunta que resta é qual
// etapa falha. Para cada arbitragem alheia: pede o bloco ao RPC, toma a tx imediatamente ANTERIOR (o
// gatilho, na posição de backrun) e confere no registro da mempool até onde ela chegou —
//   nunca anunciado → fluxo privado ou peers que não repassam: é rede, não código;
//   anunciado sem corpo → o nosso pedido de corpo falhou ou foi filtrado;
//   corpo sem swap → não decodificamos: o contrato+seletor dele entra no placar do que decodificar;
//   swap sem projeção → o roteamento não mapeou para pools;
//   projetado → vimos; a falha é depois (fenda, piso ou tempo).
// E confere a PRÓPRIA tx do bot: se nós a vimos pendente, ele usa a mempool pública; se não, bundle privado.
//
// Fora do caminho quente: uma thread, uma chamada ao RPC por bloco (várias arbitragens do mesmo bloco
// reusam), teto de fila para não acumular se o RPC engasgar.
public final class Gatilhos implements Closeable {

	private static final Logger LOG = Logger.getLogger(Gatilhos.class.getName());
	private static final int FILA_MAXIMA = 256;
	private static final int REGISTRADOS_NO_LOG = 30;

	private final RpcDoEnsaio rpc;
	private final ToIntFunction<String> etapas;
	private final java.util.function.Function<String, java.util.OptionalLong> primeiraVisaoMs;
	private final ExecutorService laco = Executors.newSingleThreadExecutor(t -> new Thread(t, "gatilhos"));
	private final AtomicInteger naFila = new AtomicInteger();

	private final Map<String, AtomicLong> gatilhoPorEtapa = new LinkedHashMap<>();
	private final Map<String, AtomicLong> arbitradorPorEtapa = new LinkedHashMap<>();
	private final Map<String, AtomicLong> naoDecodificados = new LinkedHashMap<>();
	private final AtomicLong semBloco = new AtomicLong();
	private final AtomicLong investigadas = new AtomicLong();
	private final AtomicLong blocoNaSegundaTentativa = new AtomicLong();

	// A MARGEM do gatilho: instante do bloco que o incluiu (relógio do validador, ms) menos a nossa primeira
	// visão dele, por etapa. Positiva = chegou antes do bloco. É ela que diz se o que falta é CÓDIGO (vimos a
	// tempo e não decodificamos ou não pegamos o corpo) ou REDE (chegou depois do bloco, ou nunca).
	private final Map<String, br.com.node.util.Amostras> margemPorEtapa = new LinkedHashMap<>();
	private final Map<String, long[]> antesDoBlocoPorEtapa = new LinkedHashMap<>();

	private long alturaEmCache = -1;
	private JsonArray transacoesEmCache;
	private JsonObject transacoesDoBloco;

	// O FUNIL POR VALOR. Contar gatilhos iguais escondia o que importa: na corrida de 26/09, 107 backruns acima
	// de US$ 1 somaram ~US$ 2.197 de ~2.485 — e 72% do valor era de UM tipo de gatilho (lote 7702) que o funil
	// por contagem nem destacava. Aqui cada arbitragem entra com o valor em BNB, e a GORDA (acima de
	// GORDA_BNB) fura a fila, vai ao log inteira e tem placar próprio por etapa.
	private static final java.math.BigInteger GORDA_BNB = java.math.BigInteger.valueOf(2_000_000_000_000_000L); // 0,002 BNB

	private final java.util.function.Function<Arbitragem, Optional<java.math.BigInteger>> valorBnb;
	private final Map<String, java.math.BigInteger> valorPorEtapa = new LinkedHashMap<>();
	private final Map<String, long[]> gordasPorEtapa = new LinkedHashMap<>();
	private final Map<String, java.math.BigInteger> naoDecodificadosPorValor = new LinkedHashMap<>();
	private final AtomicLong semCotacao = new AtomicLong();
	private final AtomicLong largadasPelaFila = new AtomicLong();

	public Gatilhos(RpcDoEnsaio rpc, ToIntFunction<String> etapas) {
		this(rpc, etapas, hash -> java.util.OptionalLong.empty(), arbitragem -> Optional.empty());
	}

	public Gatilhos(RpcDoEnsaio rpc, ToIntFunction<String> etapas,
			java.util.function.Function<String, java.util.OptionalLong> primeiraVisaoMs,
			java.util.function.Function<Arbitragem, Optional<java.math.BigInteger>> valorBnb) {
		this.rpc = rpc;
		this.etapas = etapas;
		this.primeiraVisaoMs = primeiraVisaoMs;
		this.valorBnb = valorBnb;
	}

	public void investigar(Arbitragem arbitragem, long altura, int indice, int indiceDoGatilho) {

		// Nenhuma tx anterior do bloco tocou as pools dela: a fenda veio de um bloco anterior — o tipo que
		// o garimpo do estado confirmado deveria pegar, e não um backrun.
		// Sem cotação (raiz fora de WBNB/USDT) é CONTADO, não vira valor zero.
		Optional<java.math.BigInteger> valor = valorBnb.apply(arbitragem);
		if (valor.isEmpty()) {
			semCotacao.incrementAndGet();
		}
		java.math.BigInteger emBnb = valor.orElse(null);
		boolean gorda = emBnb != null && emBnb.compareTo(GORDA_BNB) >= 0;

		if (indiceDoGatilho < 0) {
			synchronized (this) {
				String etapa = "sem gatilho no bloco (fenda de bloco anterior)";
				gatilhoPorEtapa.computeIfAbsent(etapa, k -> new AtomicLong()).incrementAndGet();
				somarValor(etapa, emBnb, gorda, false);
			}
			return;
		}

		// A gorda fura a fila: é justamente a que não pode faltar no placar.
		if (!gorda && naFila.get() >= FILA_MAXIMA) {
			largadasPelaFila.incrementAndGet();
			return;
		}

		naFila.incrementAndGet();
		laco.execute(() -> {
			try {
				examinar(altura, indice, indiceDoGatilho, emBnb, gorda);
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("gatilhos", e);
			} finally {
				naFila.decrementAndGet();
			}
		});
	}

	// Soma o valor e, na gorda, o placar [quantas, vistas antes do bloco]. Chamado sob `synchronized (this)`.
	private void somarValor(String etapa, java.math.BigInteger emBnb, boolean gorda, boolean antesDoBloco) {

		if (emBnb != null) {
			valorPorEtapa.merge(etapa, emBnb, java.math.BigInteger::add);
		}

		if (gorda) {
			long[] placar = gordasPorEtapa.computeIfAbsent(etapa, k -> new long[2]);
			placar[0]++;
			if (antesDoBloco) {
				placar[1]++;
			}
		}
	}

	private void examinar(long altura, int indice, int indiceDoGatilho, java.math.BigInteger emBnb, boolean gorda) {

		Optional<JsonArray> transacoes = transacoes(altura);

		// CICATRIZ: 608 "sem bloco" contra 111 investigadas (26/09, AWS). Chegamos ao bloco antes do RPC, e a
		// falha ficava em cache para as outras arbitragens da mesma altura. Espera e tenta de novo, uma vez.
		if (transacoes.isEmpty()) {
			try {
				Thread.sleep(1_500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			transacoes = transacoes(altura);
			transacoes.ifPresent(t -> blocoNaSegundaTentativa.incrementAndGet());
		}

		if (transacoes.isEmpty() || transacoes.get().size() <= indice) {
			semBloco.incrementAndGet();
			return;
		}

		JsonObject gatilho = transacoes.get().get(indiceDoGatilho).getAsJsonObject();
		JsonObject arbitrador = transacoes.get().get(indice).getAsJsonObject();

		String etapaDoGatilho = etapa(etapas.applyAsInt(semPrefixo(texto(gatilho, "hash"))));
		long instanteDoBloco = instanteMs(transacoesDoBloco);
		java.util.OptionalLong visto = primeiraVisaoMs.apply(semPrefixo(texto(gatilho, "hash")));
		String etapaDoArbitrador = etapa(etapas.applyAsInt(semPrefixo(texto(arbitrador, "hash"))));

		long n = investigadas.incrementAndGet();
		Long margemMs = visto.isPresent() && instanteDoBloco > 0 ? instanteDoBloco - visto.getAsLong() : null;

		synchronized (this) {
			gatilhoPorEtapa.computeIfAbsent(etapaDoGatilho, k -> new AtomicLong()).incrementAndGet();
			arbitradorPorEtapa.computeIfAbsent(etapaDoArbitrador, k -> new AtomicLong()).incrementAndGet();
			somarValor(etapaDoGatilho, emBnb, gorda, margemMs != null && margemMs > 0);

			if (margemMs != null) {
				long margem = margemMs;
				margemPorEtapa.computeIfAbsent(etapaDoGatilho, k -> new br.com.node.util.Amostras(2_000)).anotar(margem);
				antesDoBlocoPorEtapa.computeIfAbsent(etapaDoGatilho, k -> new long[2])[margem > 0 ? 0 : 1]++;
			}

			if (etapaDoGatilho.equals("corpo sem swap")) {
				naoDecodificados.computeIfAbsent(assinatura(gatilho), k -> new AtomicLong()).incrementAndGet();
				// Uma amostra do começo do calldata por seletor: sem ela não dá para saber que função é, e o
				// nome do seletor sozinho não diz. Os primeiros 4 bytes e os 2 primeiros argumentos (68 bytes).
				amostraDoCalldata.putIfAbsent(assinatura(gatilho), amostraDe(gatilho));
				if (emBnb != null) {
					naoDecodificadosPorValor.merge(assinatura(gatilho), emBnb, java.math.BigInteger::add);
				}
			}
		}

		// TODA gorda vai ao log, com o que é preciso para ir atrás dela na cadeia.
		if (gorda) {
			LOG.info(() -> "gatilho GORDO: " + emBnb + " wei BNB na altura " + altura + " (arb na posição " + indice
					+ ", gatilho na " + indiceDoGatilho + ") — gatilho " + texto(gatilho, "hash") + " tipo "
					+ texto(gatilho, "type") + " para " + assinatura(gatilho) + ": " + etapaDoGatilho
					+ (margemMs != null ? " (visto " + margemMs + " ms antes do bloco)" : " (nunca visto)")
					+ " · arbitrador " + texto(arbitrador, "to") + " a " + texto(arbitrador, "gasPrice")
					+ " wei/gás: " + etapaDoArbitrador);
		}

		if (n <= REGISTRADOS_NO_LOG) {
			LOG.info(() -> "gatilho: arbitragem alheia na altura " + altura + " (posição " + indice + ", gatilho na "
					+ indiceDoGatilho + ") — gatilho "
					+ texto(gatilho, "hash") + " para " + assinatura(gatilho) + ": " + etapaDoGatilho
					+ " · a tx do arbitrador: " + etapaDoArbitrador);
		}
	}

	private Optional<JsonArray> transacoes(long altura) {

		if (altura == alturaEmCache) {
			return Optional.ofNullable(transacoesEmCache);
		}

		Optional<JsonObject> bloco = rpc.bloco(altura).filter(b -> b.has("transactions"));

		// Só o bloco LIDO fica em cache: a falha (RPC ainda sem ele) tem de poder ser repetida.
		if (bloco.isEmpty()) {
			return Optional.empty();
		}

		alturaEmCache = altura;
		transacoesDoBloco = bloco.get();
		transacoesEmCache = bloco.get().getAsJsonArray("transactions");

		return Optional.of(transacoesEmCache);
	}

	// Segundos do `timestamp` + milissegundo do `mixHash` (BSC; mesma leitura do `Eth.instanteMs`). 0 se faltar.
	static long instanteMs(JsonObject bloco) {
		try {
			long segundos = Long.parseLong(semPrefixo(texto(bloco, "timestamp")), 16);
			String mistura = semPrefixo(texto(bloco, "mixHash"));
			java.math.BigInteger ms = mistura.isEmpty() ? java.math.BigInteger.ZERO : new java.math.BigInteger(mistura, 16);
			return segundos * 1_000 + (ms.compareTo(java.math.BigInteger.valueOf(1_000)) < 0 ? ms.longValue() : 0);
		} catch (RuntimeException e) {
			return 0;
		}
	}

	static String etapa(int etapas) {

		if ((etapas & ObservatorioMempool.PROJETADO) != 0) {
			return "projetado";
		}
		if ((etapas & ObservatorioMempool.SWAP) != 0) {
			return "swap sem projeção";
		}
		if ((etapas & ObservatorioMempool.CORPO) != 0) {
			return "corpo sem swap";
		}
		if ((etapas & ObservatorioMempool.PULADO_PEQUENO) != 0) {
			return "pulado por tamanho";
		}
		if ((etapas & ObservatorioMempool.ANUNCIADO) != 0) {
			return "anunciado sem corpo";
		}
		return "nunca anunciado";
	}

	// seletor@contrato, como o `SeletoresVistos` — para cruzar os dois placares.
	private final Map<String, String> amostraDoCalldata = new java.util.concurrent.ConcurrentHashMap<>();

	private static String amostraDe(JsonObject tx) {
		String input = semPrefixo(texto(tx, "input"));
		return input.substring(0, Math.min(input.length(), 8 + 64 * 2));
	}

	private static String assinatura(JsonObject tx) {
		String input = semPrefixo(texto(tx, "input"));
		String para = semPrefixo(texto(tx, "to"));
		return (input.length() >= 8 ? input.substring(0, 8) : "sem-seletor") + "@" + (para.isEmpty() ? "create" : para);
	}

	private static String texto(JsonObject objeto, String campo) {
		return objeto.has(campo) && objeto.get(campo).isJsonPrimitive() ? objeto.get(campo).getAsString() : "";
	}

	private static String semPrefixo(String hex) {
		return (hex.startsWith("0x") ? hex.substring(2) : hex).toLowerCase(java.util.Locale.ROOT);
	}

	public synchronized Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("investigadas", investigadas.get());
		resumo.put("sem_bloco", semBloco.get());
		resumo.put("gatilho_por_etapa", copia(gatilhoPorEtapa));
		resumo.put("tx_do_arbitrador_por_etapa", copia(arbitradorPorEtapa));
		resumo.put("bloco_na_segunda_tentativa", blocoNaSegundaTentativa.get());
		Map<String, Object> margens = new LinkedHashMap<>();
		margemPorEtapa.forEach((etapa, amostras) -> {
			long[] conta = antesDoBlocoPorEtapa.get(etapa);
			Map<String, Object> m = new LinkedHashMap<>(amostras.resumo());
			m.put("antes_do_bloco", conta[0]);
			m.put("depois_do_bloco", conta[1]);
			margens.put(etapa, m);
		});
		resumo.put("margem_do_gatilho_ms_por_etapa", margens);

		Map<String, Long> topo = new LinkedHashMap<>();
		naoDecodificados.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.limit(15).forEach(e -> topo.put(e.getKey(), e.getValue().get()));
		resumo.put("gatilhos_nao_decodificados", topo);
		Map<String, String> amostras = new LinkedHashMap<>();
		topo.keySet().forEach(k -> amostras.put(k, amostraDoCalldata.getOrDefault(k, "")));
		resumo.put("gatilhos_nao_decodificados_calldata", amostras);

		resumo.put("gorda_a_partir_de_bnb", GORDA_BNB.toString());
		Map<String, String> valor = new LinkedHashMap<>();
		valorPorEtapa.forEach((etapa, v) -> valor.put(etapa, v.toString()));
		resumo.put("valor_bnb_por_etapa", valor);
		Map<String, Map<String, Long>> gordas = new LinkedHashMap<>();
		gordasPorEtapa.forEach((etapa, p) -> gordas.put(etapa, Map.of("gordas", p[0], "vistas_antes_do_bloco", p[1])));
		resumo.put("gordas_por_etapa", gordas);
		Map<String, String> topoPorValor = new LinkedHashMap<>();
		naoDecodificadosPorValor.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue())).limit(15)
				.forEach(e -> topoPorValor.put(e.getKey(), e.getValue().toString()));
		resumo.put("gatilhos_nao_decodificados_por_valor_bnb", topoPorValor);
		resumo.put("sem_cotacao", semCotacao.get());
		resumo.put("largadas_pela_fila", largadasPelaFila.get());

		return resumo;
	}

	private static Map<String, Long> copia(Map<String, AtomicLong> mapa) {
		Map<String, Long> c = new LinkedHashMap<>();
		mapa.forEach((k, v) -> c.put(k, v.get()));
		return c;
	}

	@Override
	public void close() {
		laco.shutdownNow();
	}
}
