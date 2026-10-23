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

public final class TransferenciasInternas {

	public record Transferencia(String de, String para, BigInteger valorWei) {
	}

	public record ResultadoTrace(Optional<List<Transferencia>> transferencias, String erro) {
	}

	public record ResultadoBscScan(Optional<List<Transferencia>> transferencias, String erro) {
	}

	public record ResultadoNodeReal(Optional<List<Transferencia>> transferencias, String erro) {
	}

	private static final String API_PADRAO = "https://api.etherscan.io/v2/api?chainid=56";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final java.util.logging.Logger LOG = java.util.logging.Logger
			.getLogger(TransferenciasInternas.class.getName());

	private TransferenciasInternas() {
	}

	public static void anexar(RpcDoEnsaio rpc, String hash, String chaveBscScan, JsonObject linha) {

		ResultadoTrace trace = consultarTraceDetalhado(rpc, hash);
		linha.addProperty("trace_rpc_status", trace.transferencias().isPresent() ? "sucesso" : "falha");
		if (trace.erro() != null) {
			linha.addProperty("trace_rpc_erro", trace.erro());
		}
		if (trace.transferencias().isPresent()) {
			linha.add("transferencias_internas", paraJson(trace.transferencias().get()));
			linha.addProperty("fonte_transferencias_internas", "trace do RPC");
			return;
		}

		String nodeRealUrl = java.util.Optional.ofNullable(System.getenv("NODEREAL_API_URL")).map(String::trim)
				.filter(u -> !u.isBlank()).orElse(null);
		if (nodeRealUrl != null && linha.has("altura_do_vencedor")) {
			long altura = linha.get("altura_do_vencedor").getAsLong();
			ResultadoNodeReal nodeReal = consultarNodeRealDetalhado(hash, altura, nodeRealUrl);
			linha.addProperty("nodereal_status", nodeReal.transferencias().isPresent() ? "sucesso" : "falha");
			if (nodeReal.erro() != null) {
				linha.addProperty("nodereal_erro", nodeReal.erro());
			}
			if (nodeReal.transferencias().isPresent()) {
				linha.add("transferencias_internas", paraJson(nodeReal.transferencias().get()));
				linha.addProperty("fonte_transferencias_internas", "NodeReal nr_getAssetTransfers");
				return;
			}
		}

		ResultadoBscScan bscscan = chaveBscScan == null || chaveBscScan.isBlank()
				? new ResultadoBscScan(Optional.empty(), "BSCSCAN_API_KEY nao configurada")
				: consultarBscScanDetalhado(hash, chaveBscScan);
		linha.addProperty("bscscan_status", bscscan.transferencias().isPresent() ? "sucesso" : "falha");
		if (bscscan.erro() != null) {
			linha.addProperty("bscscan_erro", bscscan.erro());
		}
		if (bscscan.transferencias().isPresent()) {
			linha.add("transferencias_internas", paraJson(bscscan.transferencias().get()));
			linha.addProperty("fonte_transferencias_internas", "BscScan");
			return;
		}

		linha.addProperty("transferencias_internas_erro",
				"trace RPC: " + trace.erro()
						+ (nodeRealUrl != null ? "; NodeReal: "
								+ (linha.has("nodereal_erro") ? linha.get("nodereal_erro").getAsString() : "falha")
								: "")
						+ "; BscScan: " + bscscan.erro());
	}

	public static Optional<List<Transferencia>> consultarTrace(RpcDoEnsaio rpc, String hash) {
		return consultarTraceDetalhado(rpc, hash).transferencias();
	}

	public static ResultadoTrace consultarTraceDetalhado(RpcDoEnsaio rpc, String hash) {
		if (rpc == null) {
			return new ResultadoTrace(Optional.empty(), "RPC de tracing nao configurado");
		}

		JsonArray parametros = new JsonArray();
		parametros.add(hash);
		JsonObject tracer = new JsonObject();
		tracer.addProperty("tracer", "callTracer");
		parametros.add(tracer);

		List<RpcDoEnsaio.RespostaRpc> respostas = rpc.chamarComFallback("debug_traceTransaction", parametros);
		if (respostas.isEmpty()) {
			return new ResultadoTrace(Optional.empty(), "nenhum endpoint de trace configurado");
		}

		List<String> erros = new ArrayList<>();
		for (RpcDoEnsaio.RespostaRpc resposta : respostas) {
			if (resposta.erro() != null) {
				erros.add(resposta.host() + ": " + resposta.erro());
				continue;
			}
			ResultadoTrace resultado = interpretarRespostaTrace(resposta.corpo());
			if (resultado.transferencias().isPresent()) {
				return resultado;
			}
			erros.add(resposta.host() + ": " + resultado.erro());
		}
		return new ResultadoTrace(Optional.empty(), limitar(String.join("; ", erros), 700));
	}

	static ResultadoTrace interpretarRespostaTrace(String resposta) {
		try {
			JsonElement elemento = JsonParser.parseString(resposta);
			if (!elemento.isJsonObject()) {
				return new ResultadoTrace(Optional.empty(), "resposta JSON-RPC nao e um objeto");
			}
			JsonObject corpo = elemento.getAsJsonObject();
			if (corpo.has("error") && corpo.get("error").isJsonObject()) {
				JsonObject erro = corpo.getAsJsonObject("error");
				String codigo = erro.has("code") ? erro.get("code").getAsString() : "desconhecido";
				String mensagem = erro.has("message") ? erro.get("message").getAsString() : "sem mensagem";
				return new ResultadoTrace(Optional.empty(), "JSON-RPC code=" + codigo + ": " + limitar(mensagem, 300));
			}
			if (corpo.has("result") && corpo.get("result").isJsonObject()) {
				return new ResultadoTrace(Optional.of(doTrace(corpo.getAsJsonObject("result"))), null);
			}
			return new ResultadoTrace(Optional.empty(), "resposta JSON-RPC sem result de trace nem objeto error");
		} catch (com.google.gson.JsonParseException | IllegalStateException e) {
			return new ResultadoTrace(Optional.empty(), "resposta JSON-RPC invalida: " + limitar(e.getMessage(), 300));
		}
	}

	public static Optional<List<Transferencia>> consultarBscScan(String hash, String chave) {
		return consultarBscScanDetalhado(hash, chave).transferencias();
	}

	public static ResultadoBscScan consultarBscScanDetalhado(String hash, String chave) {
		String base = java.util.Optional.ofNullable(System.getenv("BSCSCAN_API_URL")).map(String::trim)
				.filter(u -> !u.isBlank()).orElse(API_PADRAO);
		String url = base + (base.contains("?") ? "&" : "?") + "module=account&action=txlistinternal&txhash=" + hash
				+ "&apikey=" + chave;
		try {
			HttpRequest pedido = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
			HttpResponse<String> resposta = HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
			if (resposta.statusCode() != 200) {
				LOG.warning(() -> "BscScan respondeu HTTP " + resposta.statusCode() + " para " + hash);
				return new ResultadoBscScan(Optional.empty(), "HTTP " + resposta.statusCode());
			}
			JsonObject corpo = JsonParser.parseString(resposta.body()).getAsJsonObject();
			if (corpo.has("status") && "0".equals(corpo.get("status").getAsString())) {
				String mensagem = corpo.has("result") ? corpo.get("result").getAsString()
						: corpo.has("message") ? corpo.get("message").getAsString() : "resposta NOTOK";
				String detalhe = "API status=0: " + limitar(mensagem, 300);
				LOG.warning(() -> "BscScan sem lista para " + hash + ": " + detalhe);
				return new ResultadoBscScan(Optional.empty(), detalhe);
			}
			if (!corpo.has("result") || !corpo.get("result").isJsonArray()) {
				String detalhe = "resposta sem lista de transacoes internas";
				LOG.warning(() -> "BscScan sem lista para " + hash + ": " + detalhe);
				return new ResultadoBscScan(Optional.empty(), detalhe);
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
			return new ResultadoBscScan(Optional.of(lista), null);
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return new ResultadoBscScan(Optional.empty(), e.getClass().getSimpleName());
		}
	}

	public static ResultadoNodeReal consultarNodeRealDetalhado(String hash, long alturaDoBloco, String urlNodeReal) {
		String blocoHex = "0x" + Long.toHexString(alturaDoBloco);
		JsonArray categorias = new JsonArray();
		categorias.add("internal");
		JsonObject params = new JsonObject();
		params.addProperty("fromBlock", blocoHex);
		params.addProperty("toBlock", blocoHex);
		params.add("category", categorias);
		params.addProperty("maxCount", "0x64");
		JsonArray parametros = new JsonArray();
		parametros.add(params);
		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", "nr_getAssetTransfers");
		corpo.add("params", parametros);
		try {
			HttpRequest pedido = HttpRequest.newBuilder(URI.create(urlNodeReal)).timeout(Duration.ofSeconds(15))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build();
			HttpResponse<String> resposta = HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
			if (resposta.statusCode() != 200) {
				return new ResultadoNodeReal(Optional.empty(), "HTTP " + resposta.statusCode());
			}
			JsonObject json = JsonParser.parseString(resposta.body()).getAsJsonObject();
			if (json.has("error") && json.get("error").isJsonObject()) {
				JsonObject erro = json.getAsJsonObject("error");
				String msg = erro.has("message") ? erro.get("message").getAsString() : "erro desconhecido";
				return new ResultadoNodeReal(Optional.empty(), limitar(msg, 300));
			}
			if (!json.has("result") || !json.get("result").isJsonObject()) {
				return new ResultadoNodeReal(Optional.empty(), "resposta sem result");
			}
			JsonObject resultado = json.getAsJsonObject("result");
			if (!resultado.has("transfers") || !resultado.get("transfers").isJsonArray()) {
				return new ResultadoNodeReal(Optional.of(List.of()), null);
			}
			List<Transferencia> lista = new ArrayList<>();
			String hashLower = hash.toLowerCase(java.util.Locale.ROOT);
			for (JsonElement e : resultado.getAsJsonArray("transfers")) {
				JsonObject t = e.getAsJsonObject();
				String txHash = t.has("hash") ? t.get("hash").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
				if (!hashLower.equals(txHash))
					continue;
				String valorHex = t.has("value") ? t.get("value").getAsString() : null;
				if (valorHex == null)
					continue;
				BigInteger valor = hex(valorHex);
				if (valor.signum() > 0) {
					lista.add(new Transferencia(semPrefixo(t.has("from") ? t.get("from").getAsString() : ""),
							semPrefixo(t.has("to") ? t.get("to").getAsString() : ""), valor));
				}
			}
			return new ResultadoNodeReal(Optional.of(lista), null);
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException)
				Thread.currentThread().interrupt();
			return new ResultadoNodeReal(Optional.empty(), e.getClass().getSimpleName());
		}
	}

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
		return endereco == null ? null
				: (endereco.startsWith("0x") ? endereco.substring(2) : endereco).toLowerCase(java.util.Locale.ROOT);
	}

	private static String limitar(String texto, int maximo) {
		if (texto == null) {
			return "sem detalhe";
		}
		return texto.length() <= maximo ? texto : texto.substring(0, maximo);
	}
}