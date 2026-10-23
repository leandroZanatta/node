package br.com.node.sondagem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class Rpc implements Cadeia {

	private static final List<String> NOS = List.of("https://bsc-dataseed.bnbchain.org",
			"https://bsc-dataseed1.binance.org", "https://bsc-rpc.publicnode.com");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Ritmo ritmo = new Ritmo();

	private int proximo;

	@Override
	public Optional<String> chamar(String para, String dados, String estadoJson, long altura) {

		JsonObject chamada = new JsonObject();
		chamada.addProperty("to", para);
		chamada.addProperty("data", dados);

		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add("0x" + Long.toHexString(altura));
		parametros.add(JsonParser.parseString(estadoJson));

		return resultado(pedido("eth_call", parametros));
	}

	@Override
	public Optional<String> armazenamento(String contrato, String slot, long altura) {

		JsonArray parametros = new JsonArray();
		parametros.add(contrato);
		parametros.add(slot);
		parametros.add("0x" + Long.toHexString(altura));

		return resultado(pedido("eth_getStorageAt", parametros));
	}

	@Override
	public long cabeca() {

		return resultado(pedido("eth_blockNumber", new JsonArray())).map(hex -> Long.decode(hex))
				.orElseThrow(() -> new IllegalStateException("nenhum nó público respondeu a cabeça"));
	}

	private Optional<String> pedido(String metodo, JsonArray parametros) {

		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", parametros);

		String texto = corpo.toString();

		for (int tentativa = 0; tentativa < NOS.size(); tentativa++) {

			esperarRitmo();

			String no = NOS.get(Math.floorMod(proximo++, NOS.size()));
			long agora = System.currentTimeMillis();

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(20))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(texto)).build(),
						HttpResponse.BodyHandlers.ofString());

				if (recusa(resposta)) {
					ritmo.recusou(agora);
					continue;
				}

				ritmo.passou(agora);

				return Optional.of(resposta.body());

			} catch (Exception naoRespondeu) {

			}
		}

		return Optional.empty();
	}

	private static boolean recusa(HttpResponse<String> resposta) {

		if (resposta.statusCode() == 429 || resposta.statusCode() == 403) {
			return true;
		}

		String corpo = resposta.body();

		return corpo.contains("rate limit") || corpo.contains("-32005") || corpo.contains("many requests");
	}

	private static Optional<String> resultado(Optional<String> corpo) {

		return corpo.map(texto -> JsonParser.parseString(texto).getAsJsonObject())
				.filter(objeto -> objeto.has("result")).map(objeto -> objeto.get("result").getAsString());
	}

	private void esperarRitmo() {

		try {
			Thread.sleep(ritmo.intervaloMs());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}