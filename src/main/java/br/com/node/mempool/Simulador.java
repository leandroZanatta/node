package br.com.node.mempool;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.vo.Log;

// Dá ao caderno de layouts o RÓTULO que faltava: o efeito da tx que nenhum decodificador leu.
//
// O caderno aprende, por contrato+seletor, onde a calldata guarda valor e tokens — a partir de pares
// (calldata, efeito na cadeia). Quem trazia o efeito era o `blocoFechado`, e ninguém o chamava: os
// recibos que o cursor aplica não trazem o hash da tx. MEDIDO em 23/09: 367 gatilhos de arbitragens
// alheias chegaram inteiros até nós e não foram lidos, e o caderno nunca aprendeu nada.
//
// Aqui o efeito vem da SIMULAÇÃO (`eth_simulateV1`, no estado mais recente, como o próprio remetente):
// os logs de Sync/Swap que ela emitiria. Conferido na cadeia em 23/09: nas txs simuladas no estado certo,
// as pools e o estado final bateram com o recibo real. A ~640 ms por chamada é lento demais para a
// corrida — por isso isto APRENDE, fora do caminho quente, e a próxima tx do mesmo contrato+seletor é
// lida local pelo caderno.
//
// Contido de propósito: RITMO simulações por segundo no total, até POR_ASSINATURA por contrato+seletor
// a cada JANELA_MS, e nunca os métodos de token (transfer/approve/transferFrom) — não são swap.
public final class Simulador implements Closeable {

	private static final Logger LOG = Logger.getLogger(Simulador.class.getName());

	private static final int RITMO = 4;
	private static final int POR_ASSINATURA = 10;
	private static final long JANELA_MS = 600_000;
	private static final int EM_VOO_MAXIMO = 16;

	private static final Set<String> DE_TOKEN = Set.of("a9059cbb", "095ea7b3", "23b872dd");
	private static final String SYNC = "1c411e9a96e071241c2f21f7726b17ae89e3cab4c78be50e062b03a9fffbbad1";

	private final BiFunction<String, JsonArray, Optional<String>> rpc;
	private final BiConsumer<String, List<Log>> aprender;
	private final ExecutorService laco = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<String, long[]> porAssinatura = new ConcurrentHashMap<>();
	private final AtomicInteger emVoo = new AtomicInteger();
	private long segundo;
	private int nesteSegundo;

	private final AtomicLong simuladas = new AtomicLong();
	private final AtomicLong comEfeito = new AtomicLong();
	private final AtomicLong semEfeito = new AtomicLong();
	private final AtomicLong reverteram = new AtomicLong();
	private final AtomicLong falhas = new AtomicLong();
	private final AtomicLong contidas = new AtomicLong();

	public Simulador(BiFunction<String, JsonArray, Optional<String>> rpc, BiConsumer<String, List<Log>> aprender) {
		this.rpc = rpc;
		this.aprender = aprender;
	}

	public void talvez(String hash, byte[] crua) {

		Optional<TransacaoPendente> tx = TransacaoPendente.decodificar(crua);

		if (tx.isEmpty() || tx.get().entrada().length < 4 || tx.get().destino() == null) {
			return;
		}

		String seletor = HexFormat.of().formatHex(tx.get().entrada(), 0, 4);

		if (DE_TOKEN.contains(seletor)) {
			return;
		}

		String assinatura = seletor + "@" + HexFormat.of().formatHex(tx.get().destino());

		if (!cabe(assinatura)) {
			contidas.incrementAndGet();
			return;
		}

		Optional<byte[]> remetente = TransacaoPendente.remetente(crua);

		if (remetente.isEmpty()) {
			return;
		}

		emVoo.incrementAndGet();
		laco.execute(() -> {
			try {
				simular(hash, tx.get(), remetente.get());
			} catch (RuntimeException e) {
				falhas.incrementAndGet();
				br.com.node.util.Falhas.anotar("simulador", e);
			} finally {
				emVoo.decrementAndGet();
			}
		});
	}

	private synchronized boolean cabe(String assinatura) {

		long agora = System.currentTimeMillis();

		if (emVoo.get() >= EM_VOO_MAXIMO) {
			return false;
		}

		if (agora / 1_000 != segundo) {
			segundo = agora / 1_000;
			nesteSegundo = 0;
		}

		if (nesteSegundo >= RITMO) {
			return false;
		}

		long[] janela = porAssinatura.computeIfAbsent(assinatura, k -> new long[] { agora, 0 });

		if (agora - janela[0] > JANELA_MS) {
			janela[0] = agora;
			janela[1] = 0;
		}

		if (janela[1] >= POR_ASSINATURA) {
			return false;
		}

		if (porAssinatura.size() > 20_000) {
			porAssinatura.values().removeIf(j -> agora - j[0] > JANELA_MS);
		}

		janela[1]++;
		nesteSegundo++;
		return true;
	}

	private void simular(String hash, TransacaoPendente tx, byte[] remetente) {

		simuladas.incrementAndGet();

		JsonObject chamada = new JsonObject();
		chamada.addProperty("from", "0x" + HexFormat.of().formatHex(remetente));
		chamada.addProperty("to", "0x" + HexFormat.of().formatHex(tx.destino()));
		chamada.addProperty("input", "0x" + HexFormat.of().formatHex(tx.entrada()));
		chamada.addProperty("value", "0x" + tx.valor().toString(16));

		JsonArray chamadas = new JsonArray();
		chamadas.add(chamada);
		JsonObject estado = new JsonObject();
		estado.add("calls", chamadas);
		JsonArray blocos = new JsonArray();
		blocos.add(estado);
		JsonObject pedido = new JsonObject();
		pedido.add("blockStateCalls", blocos);
		pedido.addProperty("validation", false);

		JsonArray parametros = new JsonArray();
		parametros.add(pedido);
		parametros.add("latest");

		Optional<String> corpo = rpc.apply("eth_simulateV1", parametros);

		if (corpo.isEmpty()) {
			falhas.incrementAndGet();
			return;
		}

		Optional<List<Log>> logs = logs(corpo.get());

		if (logs.isEmpty()) {
			reverteram.incrementAndGet();
			return;
		}

		boolean mexeuEmPool = logs.get().stream().anyMatch(log -> !log.topicos().isEmpty()
				&& HexFormat.of().formatHex(log.topicos().get(0)).equals(SYNC) || ehSwapV3(log));

		if (!mexeuEmPool) {
			semEfeito.incrementAndGet();
			return;
		}

		comEfeito.incrementAndGet();
		aprender.accept(hash, logs.get());
	}

	private static boolean ehSwapV3(Log log) {
		return br.com.node.catalogo.Eventos.ehSwapV3(log);
	}

	// Os logs da primeira chamada do primeiro bloco simulado; vazio se a chamada reverteu ou a resposta
	// não é a esperada. Separado para o teste prová-lo com uma resposta real.
	static Optional<List<Log>> logs(String corpo) {

		JsonObject json = JsonParser.parseString(corpo).getAsJsonObject();

		if (!json.has("result") || !json.get("result").isJsonArray() || json.getAsJsonArray("result").isEmpty()) {
			return Optional.empty();
		}

		JsonObject bloco = json.getAsJsonArray("result").get(0).getAsJsonObject();
		JsonObject chamada = bloco.getAsJsonArray("calls").get(0).getAsJsonObject();

		if (!"0x1".equals(chamada.get("status").getAsString())) {
			return Optional.empty();
		}

		List<Log> logs = new ArrayList<>();

		for (JsonElement elemento : chamada.getAsJsonArray("logs")) {
			JsonObject l = elemento.getAsJsonObject();
			List<byte[]> topicos = new ArrayList<>();
			l.getAsJsonArray("topics").forEach(t -> topicos.add(hex(t.getAsString())));
			logs.add(new Log(hex(l.get("address").getAsString()), topicos, hex(l.get("data").getAsString())));
		}

		return Optional.of(logs);
	}

	private static byte[] hex(String texto) {
		String limpo = texto.startsWith("0x") ? texto.substring(2) : texto;
		return HexFormat.of().parseHex(limpo.length() % 2 == 0 ? limpo : "0" + limpo);
	}

	public Map<String, Object> resumo() {
		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("simuladas", simuladas.get());
		resumo.put("com_efeito_em_pool", comEfeito.get());
		resumo.put("sem_efeito_em_pool", semEfeito.get());
		resumo.put("reverteram", reverteram.get());
		resumo.put("falhas", falhas.get());
		resumo.put("contidas_pelo_ritmo", contidas.get());
		return resumo;
	}

	@Override
	public void close() {
		laco.shutdownNow();
	}
}
