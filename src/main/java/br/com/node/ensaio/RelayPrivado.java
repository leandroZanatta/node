package br.com.node.ensaio;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import br.com.node.util.Keccak;

public final class RelayPrivado {

	private static final Logger LOG = Logger.getLogger(RelayPrivado.class.getName());

	private static final long VALIDADE_SEGUNDOS = 90;
	private static final int LIMITE_RESPOSTA = 8_192;
	private static final Pattern HASH_TRANSACAO = Pattern.compile("0x[0-9a-fA-F]{64}");

	
	// https://docs.48.club/puissant-builder/send-bundle
	public static final byte[] PAGAMENTO_48CLUB = HexFormat.of().parseHex("4848489f0b2bedd788c696e2d79b6b69d7484848");

	public static final byte[] COLATERAL_48CLUB_BID = HexFormat.of()
			.parseHex("1f732d6ea3bd247f0a400dbbeb479ada6d9bda8c");
	// https://docs.blockrazor.io/transaction-submission/block-builder/send-bundle.md
	public static final byte[] PAGAMENTO_BLOCKRAZOR = java.util.HexFormat.of()
			.parseHex("1266c6be60392a8ff346e8d5eccd3e69dd9c5f20");

	public record Builder(String nome, String url, String metodo, Optional<String> auth, byte[] pagamento,
			boolean mevSendBid, boolean txPrivada, String urlPrivada, String metodoPrivado) {
	}

	public record BidArgs(long blockNumber, byte[] parentHash, List<byte[]> txs, long gasUsed, BigInteger gasFee,
			BigInteger builderFee, byte[] signature, byte[] payBidTx, BigInteger nontaxableFee) {
		public BidArgs {
			txs = List.copyOf(txs);
		}
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private final List<Builder> builders;

	public RelayPrivado() {
		this(configurados());
	}

	RelayPrivado(List<Builder> builders) {
		this.builders = List.copyOf(builders);
		LOG.info(() -> "relay privado: " + (builders.isEmpty() ? "desligado (RELAY_URL vazio) — envio cai no público"
				: "ativo em "
						+ String.join(", ", builders.stream().map(b -> b.nome() + " (" + b.metodo() + ")").toList())));
	}

	static List<Builder> configurados() {

		List<Builder> lista = new java.util.ArrayList<>();

		Optional.ofNullable(System.getenv("RELAY_URL")).map(String::trim)
				.or(() -> Optional.of("https://puissant-builder.48.club")).filter(u -> !u.isBlank())
				.ifPresent(url -> lista.add(new Builder("48club", url,
						Optional.ofNullable(System.getenv("RELAY_METODO")).filter(m -> !m.isBlank())
								.orElse("eth_sendBundle"),
						Optional.ofNullable(System.getenv("RELAY_AUTH")).filter(a -> !a.isBlank()), PAGAMENTO_48CLUB,
						false, false, null, null)));

		Optional.ofNullable(System.getenv("BLOCKRAZOR_TOKEN")).map(String::trim).filter(t -> !t.isBlank())
				.ifPresent(token -> lista.add(new Builder("blockrazor",
						Optional.ofNullable(System.getenv("BLOCKRAZOR_URL")).map(String::trim).filter(u -> !u.isBlank())
								.orElse("https://virginia.builder.blockrazor.io"),
						"eth_sendBundle", Optional.of(token), PAGAMENTO_BLOCKRAZOR, false, false, null, null)));

		return lista;
	}

	public List<Builder> builders() {
		return builders;
	}

	public boolean ativo() {
		return !builders.isEmpty();
	}

	// (https://explore.48.club/v2/bundle?hash=…, guardada por 7 dias).
	public record Resposta(boolean aceito, String motivo, String bundle, long latenciaMs, Integer statusHttp,
			String codigoErro, String mensagemErro, String dadosErro, String respostaBruta, boolean respostaTruncada,
			String hashTransacaoFalha, Integer indiceTransacaoFalha, String seletorRevert, String textoRevert,
			List<String> hashesTransacoes) {

		public Resposta {
			hashesTransacoes = hashesTransacoes == null ? List.of() : List.copyOf(hashesTransacoes);
		}

		public Resposta(boolean aceito, String motivo) {
			this(aceito, motivo, null, 0, null, null, null, null, null, false, null, null, null, null, List.of());
		}

		public Resposta(boolean aceito, String motivo, String bundle) {
			this(aceito, motivo, bundle, 0, null, null, null, null, null, false, null, null, null, null, List.of());
		}

		public Resposta(boolean aceito, String motivo, String bundle, long latenciaMs) {
			this(aceito, motivo, bundle, latenciaMs, null, null, null, null, null, false, null, null, null, null,
					List.of());
		}
	}

	public record Envio(Map<String, Resposta> porBuilder) {

		public boolean aceito() {
			return porBuilder.values().stream().anyMatch(Resposta::aceito);
		}

		public long latenciaAtePrimeiraAceitacaoMs() {
			return porBuilder.values().stream().filter(Resposta::aceito).mapToLong(Resposta::latenciaMs).min()
					.orElseGet(() -> porBuilder.values().stream().mapToLong(Resposta::latenciaMs).max().orElse(0));
		}
	}

	public Envio enviar(BidArgs bid, java.util.function.Function<byte[], List<String>> montar) {

		long maxTimestamp = System.currentTimeMillis() / 1000 + VALIDADE_SEGUNDOS;
		Map<String, java.util.concurrent.CompletableFuture<Resposta>> emVoo = new java.util.LinkedHashMap<>();

		for (Builder builder : builders) {
			if (builder.mevSendBid()) {
				if (bid == null) {
					continue;
				}
				List<String> hashes = hashesDosBid(bid);
				emVoo.put(builder.nome(), enviarA(builder, corpo48Club(bid, builder.metodo()), hashes));
			} else {

				List<String> transacoes = montar.apply(builder.pagamento());
				List<String> hashes = transacoes.stream().map(RelayPrivado::hashTransacao).toList();
				JsonObject corpoBundle = corpo(builder.metodo(), transacoes, maxTimestamp, POSICAO_POR_PRIORIDADE);
				java.util.concurrent.CompletableFuture<Resposta> futuro = enviarA(builder, corpoBundle, hashes);
				emVoo.put(builder.nome(), futuro);
			}
		}

		Map<String, Resposta> respostas = new java.util.LinkedHashMap<>();
		emVoo.forEach((nome, futuro) -> respostas.put(nome, futuro.join()));

		return new Envio(respostas);
	}

	static JsonObject corpo48Club(BidArgs bid, String metodo) {

		JsonArray txsJson = new JsonArray();
		bid.txs().forEach(tx -> txsJson.add("0x" + HexFormat.of().formatHex(tx)));

		JsonArray unRevertibleJson = new JsonArray();
		bid.txs().forEach(tx -> unRevertibleJson.add("0x" + HexFormat.of().formatHex(Keccak.hash(tx))));

		JsonObject rawBid = new JsonObject();
		rawBid.addProperty("blockNumber", bid.blockNumber());
		rawBid.addProperty("parentHash", "0x" + HexFormat.of().formatHex(bid.parentHash()));
		rawBid.add("txs", txsJson);
		rawBid.add("unRevertible", unRevertibleJson);
		rawBid.addProperty("gasUsed", bid.gasUsed());
		rawBid.addProperty("gasFee", bid.gasFee().toString());
		rawBid.addProperty("builderFee", bid.builderFee().toString());

		boolean temPagamento = bid.payBidTx() != null && bid.payBidTx().length > 0;

		JsonObject args = new JsonObject();
		args.add("RawBid", rawBid);
		args.addProperty("signature", "0x" + HexFormat.of().formatHex(bid.signature()));
		args.addProperty("payBidTx", temPagamento ? "0x" + HexFormat.of().formatHex(bid.payBidTx()) : "0x");
		args.addProperty("payBidTxGasUsed", temPagamento ? 21_000L : 0L);
		args.addProperty("nontaxableFee", bid.nontaxableFee().toString());

		JsonArray lista = new JsonArray();
		lista.add(args);

		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", lista);

		return corpo;
	}

	private static List<String> hashesDosBid(BidArgs bid) {
		List<String> hashes = new java.util.ArrayList<>();
		bid.txs().forEach(tx -> hashes.add("0x" + HexFormat.of().formatHex(Keccak.hash(tx))));
		if (bid.payBidTx() != null && bid.payBidTx().length > 0) {
			hashes.add("0x" + HexFormat.of().formatHex(Keccak.hash(bid.payBidTx())));
		}
		return List.copyOf(hashes);
	}

	static String normalizar(String mensagem) {
		String amostra = mensagem.length() <= 1_024 ? mensagem : mensagem.substring(0, 1_024);
		String normalizada = amostra.replaceAll("\\[0x[0-9a-fA-F.]+\\]\\s*", "").replaceAll("0x[0-9a-fA-F]{6,}", "…")
				.replaceAll("\\d+", "N").trim();
		return normalizada.length() <= 240 ? normalizada : normalizada.substring(0, 240);
	}

	public void aquecer() {

		for (Builder builder : builders) {
			try {
				HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(builder.url()))
						.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers
								.ofString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"eth_chainId\",\"params\":[]}"));
				builder.auth().ifPresent(a -> pedido.header("Authorization", a));
				http.send(pedido.build(), HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				br.com.node.util.Falhas.anotar("aquecimento do relay " + builder.nome(), e);
			}
		}
	}

	static JsonObject corpo(String metodo, List<String> txsHex, long maxTimestamp) {
		return corpo(metodo, txsHex, maxTimestamp, false);
	}

	static final boolean POSICAO_POR_PRIORIDADE = !"0".equals(System.getenv("POSICAO_POR_PRIORIDADE"));

	static JsonObject corpo(String metodo, List<String> txsHex, long maxTimestamp, boolean posicaoPorPrioridade) {

		JsonArray txs = new JsonArray();
		txsHex.forEach(tx -> txs.add(tx.startsWith("0x") ? tx : "0x" + tx));

		JsonObject params = new JsonObject();
		params.add("txs", txs);
		params.addProperty("maxTimestamp", maxTimestamp);

		params.add("eth_sendPuissant".equals(metodo) ? "acceptReverting" : "revertingTxHashes", new JsonArray());
		if (posicaoPorPrioridade) {
			params.addProperty("positionFirst", true);
		}

		JsonArray lista = new JsonArray();
		lista.add(params);

		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", lista);

		return corpo;
	}

	private java.util.concurrent.CompletableFuture<Resposta> enviarA(Builder builder, JsonObject corpo,
			List<String> hashesTransacoes) {

		long inicioNanos = System.nanoTime();
		HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(builder.url())).timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corpo.toString()));
		builder.auth().ifPresent(a -> pedido.header("Authorization", a));

		return http.sendAsync(pedido.build(), HttpResponse.BodyHandlers.ofString()).thenApply(resposta -> {

			return interpretarResposta(resposta.statusCode(), resposta.body(), hashesTransacoes,
					milisDesde(inicioNanos));

		}).exceptionally(e -> {
			return new Resposta(false,
					"transporte: " + (e.getCause() != null ? e.getCause() : e).getClass().getSimpleName(), null,
					milisDesde(inicioNanos), null, null, null, null, null, false, null, null, null, null,
					hashesTransacoes);
		});
	}

	static Resposta interpretarResposta(int statusHttp, String corpo, List<String> hashesTransacoes, long latenciaMs) {
		boolean truncada = corpo.length() > LIMITE_RESPOSTA;
		String respostaBruta = truncada ? corpo.substring(0, LIMITE_RESPOSTA) : corpo;
		JsonObject json;
		try {
			JsonElement elemento = JsonParser.parseString(corpo);
			if (!elemento.isJsonObject()) {
				return new Resposta(false, "resposta_sem_objeto_json", null, latenciaMs, statusHttp, null, null, null,
						respostaBruta, truncada, null, null, null, null, hashesTransacoes);
			}
			json = elemento.getAsJsonObject();
		} catch (JsonParseException e) {
			String motivo = "HTTP " + statusHttp + ": " + normalizar(respostaBruta);
			return new Resposta(false, motivo, null, latenciaMs, statusHttp, null, null, null, respostaBruta, truncada,
					null, null, null, null, hashesTransacoes);
		}

		if (statusHttp / 100 == 2 && json.has("result") && !json.get("result").isJsonNull()) {
			String bundle = json.get("result").isJsonPrimitive() ? json.get("result").getAsString()
					: json.get("result").toString();
			return new Resposta(true, null, bundle, latenciaMs, statusHttp, null, null, null, respostaBruta, truncada,
					null, null, null, null, hashesTransacoes);
		}

		JsonObject erro = json.has("error") && json.get("error").isJsonObject() ? json.getAsJsonObject("error") : null;
		String codigoErro = erro == null || !erro.has("code") || erro.get("code").isJsonNull() ? null
				: textoErro(erro, "code");
		String mensagemErro = textoErro(erro, "message");
		String dadosErro = erro == null ? null : textoErro(erro, "data");
		dadosErro = limitar(dadosErro, LIMITE_RESPOSTA);
		String motivo = mensagemErro == null ? "sem result" : normalizar(mensagemErro);
		if (statusHttp / 100 != 2) {
			motivo = "HTTP " + statusHttp + ": " + motivo;
		}

		String hashFalha = hashDeFalha(mensagemErro);
		int indiceFalha = hashFalha == null ? -1 : hashesTransacoes.indexOf(hashFalha);
		RpcDoEnsaio.Veredicto revert = interpretarRevert(erro);

		return new Resposta(false, motivo, null, latenciaMs, statusHttp, codigoErro,
				limitar(mensagemErro, LIMITE_RESPOSTA), dadosErro, respostaBruta, truncada, hashFalha,
				indiceFalha >= 0 ? indiceFalha : null, revert == null ? null : revert.seletor(),
				revert == null || "revert_sem_dado".equals(revert.seletor()) ? null : limitar(revert.texto(), 1_024),
				hashesTransacoes);
	}

	private static RpcDoEnsaio.Veredicto interpretarRevert(JsonObject erro) {
		if (erro == null) {
			return null;
		}
		JsonObject envelope = new JsonObject();
		envelope.add("error", erro);
		try {
			return RpcDoEnsaio.interpretar(envelope.toString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String textoErro(JsonObject erro, String campo) {
		if (erro == null || !erro.has(campo) || erro.get(campo).isJsonNull()) {
			return null;
		}
		JsonElement valor = erro.get(campo);
		return valor.isJsonPrimitive() && valor.getAsJsonPrimitive().isString() ? valor.getAsString()
				: valor.toString();
	}

	private static String hashDeFalha(String mensagem) {
		if (mensagem == null) {
			return null;
		}
		Matcher matcher = HASH_TRANSACAO.matcher(mensagem);
		return matcher.find() ? matcher.group().toLowerCase(java.util.Locale.ROOT) : null;
	}

	private static String hashTransacao(String hexadecimal) {
		String cru = hexadecimal.startsWith("0x") ? hexadecimal.substring(2) : hexadecimal;
		return "0x" + java.util.HexFormat.of().formatHex(Keccak.hash(java.util.HexFormat.of().parseHex(cru)));
	}

	private static String limitar(String valor, int limite) {
		return valor == null || valor.length() <= limite ? valor : valor.substring(0, limite);
	}

	private static long milisDesde(long inicioNanos) {
		return (System.nanoTime() - inicioNanos) / 1_000_000;
	}
}