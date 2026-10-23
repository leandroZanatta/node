package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.catalogo.Eventos;

class RecallDaProjecaoTest {

	private static final List<String> NOS = List.of("https://bsc-rpc.publicnode.com",
			"https://bsc-dataseed.bnbchain.org");

	private static final int BLOCOS = 8;
	private static final int RECUO = 4;
	private static final double META_V2 = 0.80;

	private static final String SWAP_V2 = hex(Eventos.SWAP_V2);
	private static final String SWAP_V3 = hex(Eventos.SWAP_V3);
	private static final String SWAP_V3_PANCAKE = hex(Eventos.SWAP_V3_PANCAKE);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	@Test
	@DisplayName("recall: dos swaps que movem pool no bloco, quanto o decodificador projeta")
	void mediaORecall() {

		Optional<Long> cabeca = numero(pedir("eth_blockNumber", ""));

		if (cabeca.isEmpty()) {
			System.out.println("RELATORIO sem resposta da BSC — nada a medir");
			return;
		}

		int v2Total = 0;
		int v2Pego = 0;
		int todosTotal = 0;
		int todosPego = 0;
		Map<String, Integer> perdidosPorSeletor = new TreeMap<>();

		for (long b = cabeca.get() - RECUO; b > cabeca.get() - RECUO - BLOCOS; b--) {

			String bloco = "0x" + Long.toHexString(b);

			Map<String, Boolean> swaps = swapsDoBloco(bloco); 
			Map<String, String> entradas = entradasDoBloco(bloco); 

			for (Map.Entry<String, Boolean> swap : swaps.entrySet()) {

				String entrada = entradas.get(swap.getKey());

				if (entrada == null || entrada.length() < 10) {
					continue;
				}

				boolean pego = Roteamento.decodificar(HexFormat.of().parseHex(entrada.substring(2))).isPresent();

				todosTotal++;
				if (pego) {
					todosPego++;
				}

				if (swap.getValue()) {
					v2Total++;
					if (pego) {
						v2Pego++;
					} else {
						perdidosPorSeletor.merge(entrada.substring(2, 10), 1, Integer::sum);
					}
				}
			}
		}

		double recallV2 = v2Total == 0 ? 0 : (double) v2Pego / v2Total;
		double recallTodos = todosTotal == 0 ? 0 : (double) todosPego / todosTotal;

		System.out.println();
		System.out.println("==== RECALL DA PROJEÇÃO (" + BLOCOS + " blocos) ====");
		System.out.println(
				"swaps V2 no bloco: " + v2Total + " · projetáveis: " + v2Pego + " · recall V2: " + pct(recallV2));
		System.out.println("swaps totais (V2+V3): " + todosTotal + " · projetáveis: " + todosPego + " · recall geral: "
				+ pct(recallTodos));
		System.out.println();
		System.out.println("seletores V2 que PERDEMOS (o que decodificar em seguida):");
		perdidosPorSeletor.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
				.forEach(e -> System.out.println("   0x" + e.getKey() + " : " + e.getValue()));

		
		
		
		
		
		
		System.out.println();
		System.out.println(recallV2 >= META_V2 ? "recall na meta"
				: "recall abaixo de " + pct(META_V2)
						+ " — teto do decode; recall alto de verdade só simulando a tx crua na EVM");

		assertTrue(v2Total >= 20, "amostra de swaps V2 pequena demais: " + v2Total);
	}

	
	
	private Map<String, Boolean> swapsDoBloco(String bloco) {

		String params = "{\"fromBlock\":\"" + bloco + "\",\"toBlock\":\"" + bloco + "\",\"topics\":[[\"0x" + SWAP_V2
				+ "\",\"0x" + SWAP_V3 + "\",\"0x" + SWAP_V3_PANCAKE + "\"]]}";

		Map<String, Boolean> swaps = new LinkedHashMap<>();

		Optional<JsonElement> resposta = pedir("eth_getLogs", params);

		if (resposta.isEmpty() || !resposta.get().getAsJsonObject().has("result")) {
			return swaps;
		}

		for (JsonElement log : resposta.get().getAsJsonObject().getAsJsonArray("result")) {

			JsonObject o = log.getAsJsonObject();
			String tx = o.get("transactionHash").getAsString();
			boolean ehV2 = ("0x" + SWAP_V2).equalsIgnoreCase(o.getAsJsonArray("topics").get(0).getAsString());

			swaps.merge(tx, ehV2, (a, b) -> a || b);
		}

		return swaps;
	}

	private Map<String, String> entradasDoBloco(String bloco) {

		Map<String, String> entradas = new LinkedHashMap<>();

		Optional<JsonElement> resposta = pedir("eth_getBlockByNumber", "\"" + bloco + "\",true");

		if (resposta.isEmpty() || !resposta.get().getAsJsonObject().has("result")
				|| resposta.get().getAsJsonObject().get("result").isJsonNull()) {
			return entradas;
		}

		for (JsonElement tx : resposta.get().getAsJsonObject().getAsJsonObject("result")
				.getAsJsonArray("transactions")) {
			JsonObject o = tx.getAsJsonObject();
			entradas.put(o.get("hash").getAsString(), o.get("input").getAsString());
		}

		return entradas;
	}

	private Optional<Long> numero(Optional<JsonElement> resposta) {
		return resposta.map(JsonElement::getAsJsonObject).filter(o -> o.has("result"))
				.map(o -> Long.decode(o.get("result").getAsString()));
	}

	private Optional<JsonElement> pedir(String metodo, String params) {

		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + metodo + "\",\"params\":[" + params + "]}";

		for (String no : NOS) {

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(20))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
						HttpResponse.BodyHandlers.ofString());

				if (resposta.statusCode() / 100 == 2) {
					return Optional.of(JsonParser.parseString(resposta.body()));
				}

			} catch (Exception transporte) {
				
			}
		}

		return Optional.empty();
	}

	private static String pct(double v) {
		return Math.round(v * 1000) / 10.0 + "%";
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}