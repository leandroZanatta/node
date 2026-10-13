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

// O acesso de leitura à cadeia por RPC público, com o ritmo adaptativo e rodízio de nós.
//
// É o implementador de produção da `Cadeia`. Só leitura (`eth_call`, `eth_getStorageAt`,
// `eth_blockNumber`); nada aqui envia transação. E existe SÓ para o enriquecimento — a decisão não o
// chama, lê o que a sondagem guardou (P2).
//
// O ritmo é medido, não configurado (ver `Ritmo`): o old apanhou 403 de 13 nós por volume. Uma recusa
// espaça as próximas; um período limpo acelera devagar. Rodízio porque um nó que cai não pode virar
// "sem resposta" definitivo.
public final class Rpc implements Cadeia {

	private static final List<String> NOS = List.of("https://bsc-dataseed.bnbchain.org",
			"https://bsc-dataseed1.binance.org", "https://bsc-rpc.publicnode.com");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Ritmo ritmo = new Ritmo();

	// Rodízio de nós: por que próximo nó, por chamada, para espalhar a carga entre os três.
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

	// Tenta os nós em rodízio, respeitando o ritmo. Devolve o corpo do primeiro que responder JSON, ou
	// vazio. Recusa (429/403/"limit") corta o ritmo; corpo com "result" ou "error" é resposta e passa.
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

				HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(no))
						.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(texto)).build(), HttpResponse.BodyHandlers.ofString());

				if (recusa(resposta)) {
					ritmo.recusou(agora);
					continue;
				}

				ritmo.passou(agora);

				return Optional.of(resposta.body());

			} catch (Exception naoRespondeu) {
				// próximo nó; não é recusa de ritmo, é transporte
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
