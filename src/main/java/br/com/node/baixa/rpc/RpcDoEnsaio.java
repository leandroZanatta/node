package br.com.node.baixa.rpc;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RpcDoEnsaio {

	public record RespostaRpc(String host, int statusHttp, String corpo, String erro) {
	}

	private static final List<String> NOS = List.of("https://bsc-dataseed.bnbchain.org",
			"https://bsc-dataseed1.binance.org");

	private static final Logger LOG = Logger.getLogger(RpcDoEnsaio.class.getName());

	private static final BigInteger GAS_MAXIMO_PLAUSIVEL = BigInteger.valueOf(2_000_000L);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final List<String> nos;

	public RpcDoEnsaio() {
		this(NOS);
	}

	public RpcDoEnsaio(List<String> nos) {
		if (nos.isEmpty()) {
			throw new IllegalArgumentException("RpcDoEnsaio sem nó");
		}
		this.nos = List.copyOf(nos);
	}

	public List<String> hosts() {
		return nos.stream().map(RpcDoEnsaio::host).distinct().toList();
	}

	private int proximo;

	public long contagemDeTransacoes(String endereco) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + endereco);
		parametros.add("pending");

		return resultado("eth_getTransactionCount", parametros).map(Long::decode)
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu o nonce"));
	}

	public boolean temBloco(long altura) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + Long.toHexString(altura));
		parametros.add(false);

		return corpoDe("eth_getBlockByNumber", parametros).map(JsonParser::parseString)
				.map(com.google.gson.JsonElement::getAsJsonObject)
				.map(o -> o.has("result") && !o.get("result").isJsonNull()).orElse(false);
	}

	public boolean esperarBloco(long altura, int tentativas, long esperaMs) throws InterruptedException {
		for (int i = 0; i < tentativas; i++) {
			if (temBloco(altura)) {
				return true;
			}
			Thread.sleep(esperaMs);
		}
		return temBloco(altura);
	}

	public BigInteger saldoDe(String endereco) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + endereco);
		parametros.add("latest");

		return resultado("eth_getBalance", parametros).map(hex -> new BigInteger(hex.substring(2), 16))
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu o saldo de 0x" + endereco));
	}

	public BigInteger saldoErc20(String token, String conta) {

		String semPrefixo = conta.startsWith("0x") ? conta.substring(2) : conta;
		JsonObject chamada = new JsonObject();
		chamada.addProperty("to", comPrefixo(token));
		chamada.addProperty("data", "0x70a08231000000000000000000000000" + semPrefixo);

		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add("latest");

		return resultado("eth_call", parametros).filter(r -> r.length() > 2)
				.map(r -> new BigInteger(r.substring(2), 16)).orElse(BigInteger.ZERO);
	}

	public BigInteger precoDeGas() {
		return resultado("eth_gasPrice", new JsonArray()).map(hex -> new BigInteger(hex.substring(2), 16))
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu o preço do gás"));
	}

	public Optional<BigInteger> estimarGas(String de, String para, byte[] dados) {

		JsonObject chamada = new JsonObject();
		chamada.addProperty("from", comPrefixo(de));
		chamada.addProperty("to", comPrefixo(para));
		chamada.addProperty("data", "0x" + HexFormat.of().formatHex(dados));

		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add("pending");

		return resultado("eth_estimateGas", parametros).map(hex -> new BigInteger(hex.substring(2), 16))
				.filter(gas -> gas.signum() > 0 && gas.compareTo(GAS_MAXIMO_PLAUSIVEL) <= 0);
	}

	private static String comPrefixo(String endereco) {
		return endereco.startsWith("0x") ? endereco : "0x" + endereco;
	}

	public Optional<String> enviar(String rawHex) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + rawHex);

		Optional<String> corpo = corpoDe("eth_sendRawTransaction", parametros);
		corpo.filter(c -> !c.contains("\"result\"")).ifPresent(c -> LOG.warning(() -> "envio recusado: " + c));

		return corpo.map(c -> JsonParser.parseString(c).getAsJsonObject()).filter(o -> o.has("result"))
				.map(o -> o.get("result").getAsString());
	}

	public record Recibo(boolean sucesso, BigInteger gasUsado, BigInteger precoEfetivoGas) {
		public Recibo(boolean sucesso, BigInteger gasUsado) {
			this(sucesso, gasUsado, null);
		}
	}

	public Optional<Recibo> recibo(String hash) {

		JsonArray parametros = new JsonArray();
		parametros.add(comPrefixo(hash));

		return corpoDe("eth_getTransactionReceipt", parametros).flatMap(RpcDoEnsaio::interpretarRecibo);
	}

	public static Optional<Recibo> interpretarRecibo(String corpo) {

		JsonObject objeto;
		try {
			objeto = JsonParser.parseString(corpo).getAsJsonObject();
		} catch (RuntimeException e) {

			return Optional.empty();
		}

		if (!objeto.has("result") || objeto.get("result").isJsonNull()) {
			return Optional.empty();
		}

		JsonObject recibo = objeto.getAsJsonObject("result");

		if (!recibo.has("gasUsed") || recibo.get("gasUsed").isJsonNull() || !recibo.has("status")
				|| recibo.get("status").isJsonNull()) {
			throw new IllegalStateException("recibo sem gasUsed/status: " + recibo);
		}

		try {
			BigInteger gas = inteiroHexadecimal(recibo.get("gasUsed").getAsString());
			BigInteger precoEfetivo = recibo.has("effectiveGasPrice") && !recibo.get("effectiveGasPrice").isJsonNull()
					? inteiroHexadecimal(recibo.get("effectiveGasPrice").getAsString())
					: null;
			boolean sucesso = "0x1".equalsIgnoreCase(recibo.get("status").getAsString());
			return Optional.of(new Recibo(sucesso, gas, precoEfetivo));
		} catch (RuntimeException e) {
			throw new IllegalStateException("recibo ilegível: " + recibo, e);
		}
	}

	private static BigInteger inteiroHexadecimal(String hexadecimal) {
		String valor = hexadecimal.startsWith("0x") || hexadecimal.startsWith("0X") ? hexadecimal.substring(2)
				: hexadecimal;
		return new BigInteger(valor, 16);
	}

	public record Veredicto(String seletor, String texto) {

		public Veredicto {
			seletor = seletor == null ? "" : seletor;
			texto = texto == null ? "" : texto;
		}
	}

	/** {@code Error(string)} — o revert com mensagem, da própria linguagem. */
	public static final String ERRO_COM_TEXTO = "08c379a0";

	public Veredicto veredito(String de, String para, String dados) {
		return veredito(de, para, dados, "latest");
	}

	public Veredicto veredito(String de, String para, String dados, String bloco) {

		JsonObject chamada = new JsonObject();
		chamada.addProperty("from", "0x" + de);
		chamada.addProperty("to", "0x" + para);
		chamada.addProperty("data", "0x" + dados);

		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add(bloco);

		Optional<String> corpo = corpoDe("eth_call", parametros);

		return corpo.map(RpcDoEnsaio::interpretar).orElse(new Veredicto("sem_resposta", ""));
	}

	public Optional<JsonObject> rastrearChamada(String de, String para, String dados, String bloco) {
		JsonObject chamada = new JsonObject();
		chamada.addProperty("from", comPrefixo(de));
		chamada.addProperty("to", comPrefixo(para));
		chamada.addProperty("data", comPrefixo(dados));
		JsonObject configuracao = new JsonObject();
		configuracao.addProperty("tracer", "callTracer");
		configuracao.addProperty("timeout", "3s");
		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add(bloco);
		parametros.add(configuracao);

		return chamar("debug_traceCall", parametros).flatMap(corpo -> {
			try {
				JsonObject json = JsonParser.parseString(corpo).getAsJsonObject();
				return json.has("result") && json.get("result").isJsonObject()
						? Optional.of(json.getAsJsonObject("result"))
						: Optional.empty();
			} catch (RuntimeException e) {
				return Optional.empty();
			}
		});
	}

	public Optional<String> chamarComOverride(String de, String para, String dados, JsonObject override) {
		return chamarComOverride(de, para, dados, override, "latest");
	}

	public Optional<String> chamarComOverride(String de, String para, String dados, JsonObject override, String bloco) {

		JsonObject chamada = new JsonObject();
		chamada.addProperty("from", comPrefixo(de));
		chamada.addProperty("to", comPrefixo(para));
		chamada.addProperty("data", comPrefixo(dados));

		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add(bloco);
		parametros.add(override);

		return corpoDe("eth_call", parametros);
	}

	public static Veredicto interpretar(String corpo) {

		JsonObject json = JsonParser.parseString(corpo).getAsJsonObject();

		if (json.has("result")) {
			return new Veredicto("ok", "");
		}

		if (!json.has("error") || !json.get("error").isJsonObject()) {
			return new Veredicto("revert_sem_dado", "");
		}

		JsonObject erro = json.getAsJsonObject("error");

		String dados = textoOu(erro, "data");
		String mensagem = textoOu(erro, "message");

		String limpo = dados.startsWith("0x") ? dados.substring(2) : dados;

		return new Veredicto(limpo.length() >= 8 ? limpo.substring(0, 8) : "revert_sem_dado", frase(limpo, mensagem));
	}

	public static String frase(String dadosHex, String mensagem) {

		if (dadosHex.length() >= 8 && dadosHex.regionMatches(true, 0, ERRO_COM_TEXTO, 0, 8)) {

			Optional<String> frase = textoAbi(dadosHex);

			if (frase.isPresent()) {
				return frase.get();
			}
		}

		int onde = mensagem.indexOf("reverted: ");

		return onde < 0 ? "" : mensagem.substring(onde + "reverted: ".length()).trim();
	}

	public static Optional<String> textoAbi(String dados) {

		if (dados == null || dados.length() < 136) {
			return Optional.empty();
		}

		try {

			int tamanho = new BigInteger(dados.substring(72, 136), 16).intValueExact();

			if (tamanho <= 0 || tamanho > 1_024 || 136 + tamanho * 2 > dados.length()) {
				return Optional.empty();
			}

			byte[] bytes = HexFormat.of().parseHex(dados.substring(136, 136 + tamanho * 2));

			String frase = new String(bytes, StandardCharsets.UTF_8).trim();

			return frase.isEmpty() ? Optional.empty() : Optional.of(frase);

		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("frase do revert", e);
			return Optional.empty();
		}
	}

	private static String textoOu(JsonObject objeto, String campo) {

		if (!objeto.has(campo) || !objeto.get(campo).isJsonPrimitive()) {
			return "";
		}

		return objeto.get(campo).getAsString();
	}

	private Optional<String> resultado(String metodo, JsonArray parametros) {
		return corpoDe(metodo, parametros).map(texto -> JsonParser.parseString(texto).getAsJsonObject())
				.filter(objeto -> objeto.has("result")).map(objeto -> objeto.get("result").getAsString());
	}

	public Optional<JsonObject> bloco(long altura) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + Long.toHexString(altura));
		parametros.add(true);

		return corpoDe("eth_getBlockByNumber", parametros).map(texto -> JsonParser.parseString(texto).getAsJsonObject())
				.filter(objeto -> objeto.has("result") && objeto.get("result").isJsonObject())
				.map(objeto -> objeto.getAsJsonObject("result"));
	}

	public Optional<String> chamar(String metodo, JsonArray parametros) {
		return corpoDe(metodo, parametros);
	}

	public List<RespostaRpc> chamarComFallback(String metodo, JsonArray parametros) {
		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", parametros);
		String texto = corpo.toString();
		List<RespostaRpc> respostas = new java.util.ArrayList<>();

		for (int tentativa = 0; tentativa < nos.size(); tentativa++) {
			String no = nos.get(Math.floorMod(proximo++, nos.size()));
			String host = host(no);
			try {
				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(20))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(texto)).build(),
						HttpResponse.BodyHandlers.ofString());
				RespostaRpc rpcResposta = new RespostaRpc(host, resposta.statusCode(), resposta.body(),
						resposta.statusCode() / 100 == 2 ? null : "HTTP " + resposta.statusCode());
				respostas.add(rpcResposta);
				if (rpcResposta.erro() == null && temResultadoJsonRpc(rpcResposta.corpo())) {
					break;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respostas.add(new RespostaRpc(host, 0, null, "interrompido"));
				break;
			} catch (IOException | RuntimeException e) {
				respostas.add(new RespostaRpc(host, 0, null, e.getClass().getSimpleName()));
			}
		}
		return List.copyOf(respostas);
	}

	public long cabeca() {

		return corpoDe("eth_blockNumber", new JsonArray()).map(texto -> JsonParser.parseString(texto).getAsJsonObject())
				.filter(objeto -> objeto.has("result")).map(objeto -> Long.decode(objeto.get("result").getAsString()))
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu a cabeça da BSC"));
	}

	private Optional<String> corpoDe(String metodo, JsonArray parametros) {

		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", parametros);

		String texto = corpo.toString();

		for (int tentativa = 0; tentativa < nos.size(); tentativa++) {

			String no = nos.get(Math.floorMod(proximo++, nos.size()));

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(20))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(texto)).build(),
						HttpResponse.BodyHandlers.ofString());

				if (resposta.statusCode() / 100 == 2) {
					return Optional.of(resposta.body());
				}

				br.com.node.util.Falhas.anotar("RPC " + no, new IllegalStateException(
						"HTTP " + resposta.statusCode() + " em " + metodo + ": " + resposta.body()));

			} catch (Exception transporte) {

				br.com.node.util.Falhas.anotar("RPC " + no, transporte);
			}
		}

		return Optional.empty();
	}

	private static String host(String no) {
		try {
			String host = URI.create(no).getHost();
			return host == null ? "endpoint-invalido" : host;
		} catch (IllegalArgumentException e) {
			return "endpoint-invalido";
		}
	}

	private static boolean temResultadoJsonRpc(String corpo) {
		try {
			JsonElement elemento = JsonParser.parseString(corpo);
			return elemento.isJsonObject() && elemento.getAsJsonObject().has("result")
					&& !elemento.getAsJsonObject().get("result").isJsonNull();
		} catch (com.google.gson.JsonParseException | IllegalStateException e) {
			return false;
		}
	}
}