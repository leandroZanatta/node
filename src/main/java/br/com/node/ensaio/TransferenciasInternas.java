package br.com.node.ensaio;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// QUEM RECEBEU BNB DENTRO de uma transação. A transferência feita por um contrato durante a execução
// (um `coinbase.transfer`, por exemplo) não aparece na transação de topo nem nos logs: só o trace mostra.
//
// Duas fontes, na ordem: o trace do próprio RPC (`debug_traceTransaction`, callTracer), e se o RPC não
// tiver trace, a API de transações internas do BscScan (precisa de `BSCSCAN_API_KEY`). Se as duas faltarem,
// o registro diz isso, em vez de mostrar uma lista vazia que pareceria "não houve transferência".
public final class TransferenciasInternas {

	public record Transferencia(String de, String para, BigInteger valorWei) {
	}

	// O endereço antigo do BscScan responde 301 (redirecionado). O cliente padrão do Java NÃO segue redirecionamento,
	// e isso fazia a consulta falhar em silêncio. Por isso: segue redirecionamento, e a URL é configurável
	// (BSCSCAN_API_URL), para trocar sem recompilar se o provedor mudar de novo.
	private static final String API_PADRAO = "https://api.etherscan.io/v2/api?chainid=56";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(TransferenciasInternas.class.getName());

	private TransferenciasInternas() {
	}

	// Anexa ao registro do vencedor o campo `transferencias_internas` (lista) ou `transferencias_internas_erro`.
	public static void anexar(RpcDoEnsaio rpc, String hash, String chaveBscScan, JsonObject linha) {

		Optional<List<Transferencia>> trace = rpc == null ? Optional.empty() : consultarTrace(rpc, hash);
		if (trace.isPresent()) {
			linha.add("transferencias_internas", paraJson(trace.get()));
			linha.addProperty("fonte_transferencias_internas", "trace do RPC");
			return;
		}

		Optional<List<Transferencia>> bscscan = chaveBscScan == null || chaveBscScan.isBlank()
				? Optional.empty() : consultarBscScan(hash, chaveBscScan);
		if (bscscan.isPresent()) {
			linha.add("transferencias_internas", paraJson(bscscan.get()));
			linha.addProperty("fonte_transferencias_internas", "BscScan");
			return;
		}

		linha.addProperty("transferencias_internas_erro",
				"indisponível: o RPC não tem debug_traceTransaction e BSCSCAN_API_KEY não está definida ou não respondeu");
	}

	// Trace do RPC com callTracer. Devolve vazio se o método não existir ou der erro (não é "sem transferência").
	public static Optional<List<Transferencia>> consultarTrace(RpcDoEnsaio rpc, String hash) {
		JsonArray parametros = new JsonArray();
		parametros.add(hash);
		JsonObject tracer = new JsonObject();
		tracer.addProperty("tracer", "callTracer");
		parametros.add(tracer);
		return rpc.chamar("debug_traceTransaction", parametros)
				.map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
				.filter(o -> o.has("result") && o.get("result").isJsonObject())
				.map(o -> doTrace(o.getAsJsonObject("result")));
	}

	// Transações internas pela API do BscScan. Vazio se a chamada falhar.
	public static Optional<List<Transferencia>> consultarBscScan(String hash, String chave) {
		String base = java.util.Optional.ofNullable(System.getenv("BSCSCAN_API_URL")).map(String::trim)
				.filter(u -> !u.isBlank()).orElse(API_PADRAO);
		String url = base + (base.contains("?") ? "&" : "?") + "module=account&action=txlistinternal&txhash=" + hash
				+ "&apikey=" + chave;
		try {
			HttpRequest pedido = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
			HttpResponse<String> resposta = HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
			if (resposta.statusCode() != 200) {
				LOG.warning(() -> "BscScan respondeu HTTP " + resposta.statusCode() + " para " + hash);
				return Optional.empty();
			}
			JsonObject corpo = JsonParser.parseString(resposta.body()).getAsJsonObject();
			if (!corpo.has("result") || !corpo.get("result").isJsonArray()) {
				// Ex.: chave inválida ou limite de uso. A mensagem da API vem em "result" como texto.
				LOG.warning(() -> "BscScan sem lista para " + hash + ": " + corpo);
				return Optional.empty();
			}
			List<Transferencia> lista = new ArrayList<>();
			for (JsonElement e : corpo.getAsJsonArray("result")) {
				JsonObject t = e.getAsJsonObject();
				BigInteger valor = new BigInteger(t.get("value").getAsString());
				if (valor.signum() > 0) {
					lista.add(new Transferencia(semPrefixo(t.get("from").getAsString()),
							semPrefixo(t.get("to").getAsString()), valor));
				}
			}
			return Optional.of(lista);
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return Optional.empty();
		}
	}

	// Função pura: percorre a árvore do callTracer e junta toda chamada com valor positivo.
	public static List<Transferencia> doTrace(JsonObject chamada) {
		List<Transferencia> achadas = new ArrayList<>();
		recolher(chamada, achadas);
		return achadas;
	}

	private static void recolher(JsonObject chamada, List<Transferencia> achadas) {
		if (chamada.has("value") && !chamada.get("value").isJsonNull()) {
			BigInteger valor = hex(chamada.get("value").getAsString());
			if (valor.signum() > 0) {
				achadas.add(new Transferencia(semPrefixo(chamada.get("from").getAsString()),
						semPrefixo(chamada.get("to").getAsString()), valor));
			}
		}
		if (chamada.has("calls") && chamada.get("calls").isJsonArray()) {
			for (JsonElement filha : chamada.getAsJsonArray("calls")) {
				recolher(filha.getAsJsonObject(), achadas);
			}
		}
	}

	private static JsonArray paraJson(List<Transferencia> lista) {
		JsonArray arr = new JsonArray();
		for (Transferencia t : lista) {
			JsonObject o = new JsonObject();
			o.addProperty("de", t.de());
			o.addProperty("para", t.para());
			o.addProperty("valor_wei", t.valorWei().toString());
			arr.add(o);
		}
		return arr;
	}

	private static BigInteger hex(String h) {
		return new BigInteger(h.startsWith("0x") ? h.substring(2) : h, 16);
	}

	private static String semPrefixo(String endereco) {
		return endereco == null ? null : (endereco.startsWith("0x") ? endereco.substring(2) : endereco).toLowerCase(java.util.Locale.ROOT);
	}
}
