package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class RemetenteNaCadeiaTest {

	private static final String NO = "https://bsc-rpc.publicnode.com";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	@Test
	@DisplayName("o remetente recuperado da assinatura bate com o from do nó, em transações reais")
	void recuperaORemetenteReal() {

		Optional<JsonObject> bloco = pedir("eth_getBlockByNumber", "\"latest\",false");

		if (bloco.isEmpty()) {
			System.out.println("RELATORIO sem resposta da BSC — nada a validar");
			return;
		}

		JsonArray hashes = bloco.get().getAsJsonObject("result").getAsJsonArray("transactions");
		int conferidas = 0;

		for (int i = 0; i < hashes.size() && conferidas < 6; i++) {

			String hash = hashes.get(i).getAsString();

			Optional<JsonObject> crua = pedir("eth_getRawTransactionByHash", "\"" + hash + "\"");
			Optional<JsonObject> tx = pedir("eth_getTransactionByHash", "\"" + hash + "\"");

			if (crua.isEmpty() || tx.isEmpty() || crua.get().get("result").isJsonNull()) {
				continue;
			}

			byte[] bytes = HexFormat.of().parseHex(crua.get().get("result").getAsString().substring(2));
			String esperado = tx.get().getAsJsonObject("result").get("from").getAsString().toLowerCase();

			Optional<byte[]> recuperado = TransacaoPendente.remetente(bytes);

			assertTrue(recuperado.isPresent(), "não recuperou remetente da tx " + hash);
			assertEquals(esperado, "0x" + HexFormat.of().formatHex(recuperado.get()),
					"remetente recuperado difere do from do nó na tx " + hash);

			conferidas++;
		}

		System.out.println("RELATORIO remetentes conferidos contra o nó: " + conferidas);
		assertTrue(conferidas > 0, "nenhuma tx com raw disponível para conferir");
	}

	private Optional<JsonObject> pedir(String metodo, String params) {

		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + metodo + "\",\"params\":[" + params + "]}";

		try {

			HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(NO))
					.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());

			if (resposta.statusCode() / 100 != 2) {
				return Optional.empty();
			}

			return Optional.of(JsonParser.parseString(resposta.body()).getAsJsonObject());

		} catch (Exception transporte) {
			return Optional.empty();
		}
	}
}