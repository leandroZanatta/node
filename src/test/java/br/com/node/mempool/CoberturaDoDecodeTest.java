package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Eventos;

// A COBERTURA do decode, medida — e o mapa de onde ela pode crescer.
//
// O `RecallDaProjecaoTest` diz QUANTO perdemos (o histograma de seletores). Este diz ONDE a perda
// está e se ela é alcançável, que são as duas perguntas que decidem o que escrever em seguida:
//
//   1. A perda é CONCENTRADA (um router que faltou) ou CAUDA LONGA (bot privado)? Mede-se pelo par
//      (seletor, contrato de destino): MEDIDO em 14 blocos, 106 txs perdidas com 48 seletores e 46
//      contratos distintos, e o top-1 contrato respondendo por 28% da perda.
//   2. O que a tx moveu APARECE na calldata? Se os endereços dos tokens estiverem lá como texto, um
//      leitor ESTRUTURAL (sem conhecer a ABI) recupera o caminho; se não estiverem, só simular a tx
//      crua na EVM resolve. MEDIDO: ~45% das perdidas carregam TODOS os tokens, ~22% não carregam
//      nenhum — o resto carrega parte.
//
// É medidor, não portão: imprime o relatório e só exige amostra mínima, como o recall.
class CoberturaDoDecodeTest {

	private static final List<String> NOS = List.of("https://bsc-rpc.publicnode.com",
			"https://bsc-dataseed.bnbchain.org");

	private static final int BLOCOS = 8;
	private static final int RECUO = 4;
	private static final int POOLS_CHECADAS = 30;

	// Os mesmos seletores que o `Roteamento`/`RoteamentoUniversal` já leem — a régua do recall.
	private static final Set<String> CONHECIDOS = Set.of("38ed1739", "5c11d795", "18cbafe5", "791ac947", "7ff36ab5",
			"b6f9de95", "24856bc3");

	private static final String SWAP_V2 = hex(Eventos.SWAP_V2);
	private static final String SWAP_V3 = hex(Eventos.SWAP_V3);
	private static final String SWAP_V3_PANCAKE = hex(Eventos.SWAP_V3_PANCAKE);

	/** `token0()` e `token1()` — o par da pool que o log moveu é a verdade sobre os tokens. */
	private static final String TOKEN0 = "0x0dfe1681";
	private static final String TOKEN1 = "0xd21220a7";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Map<String, Set<String>> tokensDaPool = new HashMap<>();

	@Test
	@DisplayName("cobertura do decode: quanto se perde, onde está a perda e se ela é alcançável")
	void ondeEstaAPerda() {

		Optional<Long> cabeca = numero(pedir("eth_blockNumber", ""));

		if (cabeca.isEmpty()) {
			System.out.println("RELATORIO sem resposta da BSC — nada a medir");
			return;
		}

		int v2Total = 0;
		int v2Pego = 0;
		int perdidas = 0;
		Map<String, Integer> porSeletor = new TreeMap<>();
		Map<String, Integer> porContrato = new TreeMap<>();
		int comTodosOsTokens = 0;
		int comAlgumToken = 0;
		int semTokenNenhum = 0;
		int analisadas = 0;

		for (long b = cabeca.get() - RECUO; b > cabeca.get() - RECUO - BLOCOS; b--) {

			String bloco = "0x" + Long.toHexString(b);
			Map<String, String[]> txs = transacoesDoBloco(bloco);
			List<JsonElement> logs = logsDoBloco(bloco);

			for (Map.Entry<String, Boolean> swap : swapsDosLogs(logs).entrySet()) {

				String[] tx = txs.get(swap.getKey());

				if (!swap.getValue() || tx == null || tx[0].length() < 10) {
					continue;
				}

				String entrada = tx[0];

				v2Total++;

				if (CONHECIDOS.contains(entrada.substring(2, 10))) {
					v2Pego++;
					continue;
				}

				perdidas++;
				porSeletor.merge(entrada.substring(2, 10), 1, Integer::sum);
				porContrato.merge(tx[1], 1, Integer::sum);

				if (analisadas >= POOLS_CHECADAS) {
					continue;
				}

				analisadas++;
				Set<String> tokens = tokensMovidos(poolsDosLogs(logs).getOrDefault(swap.getKey(), List.of()));

				if (tokens.isEmpty()) {
					continue;
				}

				String corpo = entrada.substring(2).toLowerCase();
				int presentes = 0;
				for (String token : tokens) {
					if (corpo.contains(token)) {
						presentes++;
					}
				}

				if (presentes == 0) {
					semTokenNenhum++;
				} else if (presentes == tokens.size()) {
					comTodosOsTokens++;
				} else {
					comAlgumToken++;
				}
			}
		}

		System.out.println("RELATORIO cobertura do decode em " + BLOCOS + " blocos");
		System.out.println("  recall V2 = " + v2Pego + "/" + v2Total + " = " + pct(v2Pego, v2Total));
		System.out.println("  perdidas V2 = " + perdidas + " | seletores distintos = " + porSeletor.size()
				+ " | contratos distintos = " + porContrato.size());
		System.out.println("  onde esta a perda (top contratos, destino da tx):");

		int totalPerdidas = perdidas;

		porContrato.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
				.forEach(e -> System.out.println("    " + e.getKey() + " : " + e.getValue() + " ("
						+ pct(e.getValue(), totalPerdidas) + " da perda)"));

		System.out.println("  a calldata carrega os tokens que a tx moveu? (amostra " + analisadas + ")");
		System.out.println("    todos os tokens : " + comTodosOsTokens + " -> leitor ESTRUTURAL alcanca");
		System.out.println("    algum token     : " + comAlgumToken);
		System.out.println("    nenhum token    : " + semTokenNenhum + " -> so simulando a tx crua na EVM");

		assertTrue(v2Total >= 10, "amostra de swaps V2 pequena demais: " + v2Total);
	}

	// txHash -> [input, to] do bloco. O `to` é o que separa router conhecido de bot privado.
	private Map<String, String[]> transacoesDoBloco(String bloco) {

		Map<String, String[]> txs = new LinkedHashMap<>();
		Optional<JsonElement> resposta = pedir("eth_getBlockByNumber", "\"" + bloco + "\",true");

		if (resposta.isEmpty() || !resposta.get().isJsonObject()) {
			return txs;
		}

		JsonObject o = resposta.get().getAsJsonObject();

		if (!o.has("result") || o.get("result").isJsonNull()) {
			return txs;
		}

		for (JsonElement tx : o.getAsJsonObject("result").getAsJsonArray("transactions")) {

			JsonObject t = tx.getAsJsonObject();
			String destino = t.has("to") && !t.get("to").isJsonNull() ? t.get("to").getAsString() : "create";

			txs.put(t.get("hash").getAsString(), new String[] { t.get("input").getAsString(), destino });
		}

		return txs;
	}

	// txHash -> é V2 (senão é V3, nas duas formas do evento).
	private Map<String, Boolean> swapsDosLogs(List<JsonElement> logs) {

		Map<String, Boolean> swaps = new LinkedHashMap<>();

		for (JsonElement log : logs) {

			JsonObject o = log.getAsJsonObject();

			swaps.merge(o.get("transactionHash").getAsString(),
					("0x" + SWAP_V2).equalsIgnoreCase(o.getAsJsonArray("topics").get(0).getAsString()),
					(a, b) -> a || b);
		}

		return swaps;
	}

	// txHash -> pools que a tx moveu, dos próprios logs: a verdade sobre o que ela tocou.
	private Map<String, List<String>> poolsDosLogs(List<JsonElement> logs) {

		Map<String, List<String>> porTx = new LinkedHashMap<>();

		for (JsonElement log : logs) {

			JsonObject o = log.getAsJsonObject();

			porTx.computeIfAbsent(o.get("transactionHash").getAsString(), h -> new java.util.ArrayList<>())
					.add(o.get("address").getAsString());
		}

		return porTx;
	}

	private List<JsonElement> logsDoBloco(String bloco) {

		String params = "{\"fromBlock\":\"" + bloco + "\",\"toBlock\":\"" + bloco + "\",\"topics\":[[\"0x" + SWAP_V2
				+ "\",\"0x" + SWAP_V3 + "\",\"0x" + SWAP_V3_PANCAKE + "\"]]}";

		return pedir("eth_getLogs", params).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
				.filter(o -> o.has("result")).map(o -> o.getAsJsonArray("result")).map(JsonArray::asList)
				.orElse(List.of());
	}

	// Os tokens das pools movidas: dois eth_call por pool, memorizados — imutável na cadeia.
	private Set<String> tokensMovidos(List<String> pools) {

		Set<String> tokens = new java.util.LinkedHashSet<>();

		for (String pool : pools.subList(0, Math.min(3, pools.size()))) {

			tokens.addAll(tokensDaPool.computeIfAbsent(pool, p -> {

				Set<String> par = new java.util.LinkedHashSet<>();

				for (String seletor : List.of(TOKEN0, TOKEN1)) {
					pedir("eth_call", "{\"to\":\"" + p + "\",\"data\":\"" + seletor + "\"},\"latest\"")
							.filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
							.filter(o -> o.has("result") && o.get("result").isJsonPrimitive())
							.map(o -> o.get("result").getAsString()).filter(t -> t.length() >= 42)
							.map(t -> t.substring(t.length() - 40)).ifPresent(par::add);
				}

				return par;
			}));
		}

		return tokens;
	}

	private Optional<Long> numero(Optional<JsonElement> resposta) {
		return resposta.filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
				.filter(o -> o.has("result")).map(o -> Long.decode(o.get("result").getAsString()));
	}

	private Optional<JsonElement> pedir(String metodo, String params) {

		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + metodo + "\",\"params\":[" + params + "]}";

		for (String no : NOS) {

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(20))
								.header("Content-Type", "application/json").header("User-Agent", "Mozilla/5.0")
								.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
						HttpResponse.BodyHandlers.ofString());

				if (resposta.statusCode() / 100 == 2) {
					return Optional.of(JsonParser.parseString(resposta.body()));
				}

			} catch (Exception transporte) {
				// próximo nó
			}
		}

		return Optional.empty();
	}

	private static String pct(int parte, int total) {
		return Math.round(1000.0 * parte / Math.max(total, 1)) / 10.0 + "%";
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
