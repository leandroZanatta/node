package br.com.node.ensaio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import br.com.node.util.Keccak;

// Envia um BUNDLE a um relay privado da BSC (48club Puissant, bloXroute, …), fora da mempool pública.
//
// É o que conserta o NaoPagou da primeira execução real: um backrun avulso na mempool pública não tem
// como executar logo APÓS o swap alvo, então a fenda não está lá na inclusão e a conta fica curta. Um
// bundle `[tx pendente alvo, nossa tx]` diz ao builder "inclua as duas, nesta ordem, ou nenhuma" — a
// nossa roda no estado exato que o swap alvo abriu. Os bytes crus do alvo vêm do próprio mempool.
//
// Fala o puissant-builder v2 do 48club (BSC): método `eth_sendBundle`, params
// `[{txs, maxTimestamp, revertingTxHashes}]`, resposta = hash do BUNDLE. MUDOU em 25/09: o node falava o
// `eth_sendPuissant` da 1ª geração (seção "deprecated" da documentação), em que a prioridade era só o
// gas price da PRIMEIRA tx — na cauda, o nosso piso de 0,05 gwei; no N+1, o gas price do ALVO. Na v2 o
// leilão é `(gás das txs únicas × 0,9) + BNB enviado ao Builder Control EOA`, e o BNB tem prioridade
// (https://docs.48.club/puissant-builder/send-bundle). Quem monta o pagamento é o `Disparo`.
// Configurável por ambiente:
//   RELAY_URL    — endpoint (padrão https://puissant-builder.48.club/). Vazio explícito desliga o relay.
//   RELAY_METODO — método (padrão eth_sendBundle; `eth_sendPuissant` volta ao protocolo antigo)
//   RELAY_AUTH   — header Authorization, se algum dia o relay exigir; vazio quando não.
//   BLOCKRAZOR_TOKEN — liga o builder da BlockRazor: o token da conta, mandado no header Authorization.
//                      Sem ele, só a 48club.
//   BLOCKRAZOR_URL   — endpoint (padrão https://virginia.builder.blockrazor.io, o builder direto, ao lado
//                      do servidor em us-east-2). CICATRIZ de 26/09: o token virava caminho da URL e as 43
//                      entregas da primeira corrida voltaram HTTP 404 — nenhum bundle chegou à BlockRazor.
//
// VÁRIOS BUILDERS (26/09). Cada bloco da BSC é montado por UM builder, e o bundle só entra no bloco de
// quem o recebeu. Medido em 300 blocos: 48club ~56%, BlockRazor ~23%, outros ~21%. O bundle 0xd82dc7…
// chegou a tempo (254 ms depois do bloco, ~200 ms de folga), foi aceito pela 48club e ficou
// `submitted: false` — o bloco 124156709 foi da BlockRazor. Agora o MESMO bundle vai a todos, em
// paralelo, cada um com a SUA tx de pagamento (mesmo nonce, destino do builder): só um bloco inclui, então
// só um pagamento sai.
//
// A lista de revert VAZIA é a decisão-chave: o bundle só é incluído se TODAS as txs sucederem. Uma
// arb que reverteria (NaoPagou, a fenda fechou) simplesmente NÃO ENTRA e não paga gás — o downside de
// um backrun que não fecha cai de "gás por revert" para ZERO. Só se paga quando a arb de fato lucra.
//
// Nada aqui decide SE envia — só transporta o bundle. Quem decide (e monta) é o `Disparo`.
public final class RelayPrivado {

	private static final Logger LOG = Logger.getLogger(RelayPrivado.class.getName());

	// Puissant: maxTimestamp vale no máximo 2 min à frente; 90s dá folga sem passar do teto.
	private static final long VALIDADE_SEGUNDOS = 90;
	private static final int LIMITE_RESPOSTA = 8_192;
	private static final Pattern HASH_TRANSACAO = Pattern.compile("0x[0-9a-fA-F]{64}");

	public static final byte[] PAGAMENTO_48CLUB = java.util.HexFormat.of().parseHex("4848489f0b2bedd788c696e2d79b6b69d7484848");
	// https://docs.blockrazor.io/transaction-submission/block-builder/send-bundle.md
	public static final byte[] PAGAMENTO_BLOCKRAZOR = java.util.HexFormat.of().parseHex("1266c6be60392a8ff346e8d5eccd3e69dd9c5f20");

	// Um builder: onde se entrega o bundle e a quem se paga o lance.
	public record Builder(String nome, String url, String metodo, Optional<String> auth, byte[] pagamento) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private final List<Builder> builders;

	public RelayPrivado() {
		this(configurados());
	}

	RelayPrivado(List<Builder> builders) {
		this.builders = List.copyOf(builders);
		LOG.info(() -> "relay privado: " + (builders.isEmpty() ? "desligado (RELAY_URL vazio) — envio cai no público"
				: "ativo em " + String.join(", ", builders.stream().map(b -> b.nome() + " (" + b.metodo() + ")").toList())));
	}

	static List<Builder> configurados() {

		List<Builder> lista = new java.util.ArrayList<>();

		Optional.ofNullable(System.getenv("RELAY_URL")).map(String::trim)
				.or(() -> Optional.of("https://puissant-builder.48.club/")).filter(u -> !u.isBlank())
				.ifPresent(url -> lista.add(new Builder("48club", url,
						Optional.ofNullable(System.getenv("RELAY_METODO")).filter(m -> !m.isBlank()).orElse("eth_sendBundle"),
						Optional.ofNullable(System.getenv("RELAY_AUTH")).filter(a -> !a.isBlank()), PAGAMENTO_48CLUB)));

		Optional.ofNullable(System.getenv("BLOCKRAZOR_TOKEN")).map(String::trim).filter(t -> !t.isBlank())
				.ifPresent(token -> lista.add(new Builder("blockrazor",
						Optional.ofNullable(System.getenv("BLOCKRAZOR_URL")).map(String::trim).filter(u -> !u.isBlank())
								.orElse("https://virginia.builder.blockrazor.io"),
						"eth_sendBundle", Optional.of(token), PAGAMENTO_BLOCKRAZOR)));

		return lista;
	}

	public List<Builder> builders() {
		return builders;
	}

	public boolean ativo() {
		return !builders.isEmpty();
	}

	// Preserva o motivo normalizado para contagem e os campos originais para diagnosticar cada tentativa.
	// `bundle` é o hash que o relay devolve ao aceitar — a chave da consulta de status
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

	// O desfecho do envio a TODOS os builders, por nome, na ordem da configuração.
	public record Envio(Map<String, Resposta> porBuilder) {

		public boolean aceito() {
			return porBuilder.values().stream().anyMatch(Resposta::aceito);
		}

		public long latenciaAtePrimeiraAceitacaoMs() {
			return porBuilder.values().stream().filter(Resposta::aceito).mapToLong(Resposta::latenciaMs).min()
					.orElseGet(() -> porBuilder.values().stream().mapToLong(Resposta::latenciaMs).max().orElse(0));
		}
	}

	// Entrega o bundle a cada builder em PARALELO. `montar` recebe o endereço de pagamento do builder e
	// devolve as txs cruas daquele bundle (a de pagamento muda de destino; o resto é igual). Espera todos:
	// o mais lento custa o mesmo que um envio só, porque correm juntos.
	public Envio enviar(java.util.function.Function<byte[], List<String>> montar) {

		long maxTimestamp = System.currentTimeMillis() / 1000 + VALIDADE_SEGUNDOS;
		Map<String, java.util.concurrent.CompletableFuture<Resposta>> emVoo = new java.util.LinkedHashMap<>();

		for (Builder builder : builders) {
			// `positionFirst` vai aos DOIS: a BlockRazor documenta o mesmo campo (send-bundle), e sem ele o bundle tardio
			// ia ao final do bloco dela e revertia — o "non-reverting tx in bundle failed" de 27/09.
			List<String> transacoes = montar.apply(builder.pagamento());
			List<String> hashes = transacoes.stream().map(RelayPrivado::hashTransacao).toList();
			emVoo.put(builder.nome(), enviarA(builder,
					corpo(builder.metodo(), transacoes, maxTimestamp, POSICAO_POR_PRIORIDADE), hashes));
		}

		Map<String, Resposta> respostas = new java.util.LinkedHashMap<>();
		emVoo.forEach((nome, futuro) -> respostas.put(nome, futuro.join()));

		return new Envio(respostas);
	}

	static String normalizar(String mensagem) {
		String amostra = mensagem.length() <= 1_024 ? mensagem : mensagem.substring(0, 1_024);
		String normalizada = amostra.replaceAll("\\[0x[0-9a-fA-F.]+\\]\\s*", "")
				.replaceAll("0x[0-9a-fA-F]{6,}", "…").replaceAll("\\d+", "N").trim();
		return normalizada.length() <= 240 ? normalizada : normalizada.substring(0, 240);
	}

	// Mantém a conexão HTTP/2 com o relay VIVA. MEDIDO em 23/09: conexão nova custa 530–720 ms (TCP
	// 150–280 + TLS) e reaproveitada, 170 ms. Os bundles saem com minutos de intervalo, então sem isto
	// cada envio pagava o handshake inteiro — no caminho em que o alvo vive um bloco (~450 ms). O relay
	// não tem método de ping: `eth_chainId` volta erro com HTTP 200, o que basta para a conexão.
	public void aquecer() {

		for (Builder builder : builders) {
			try {
				HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(builder.url())).timeout(Duration.ofSeconds(5))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"eth_chainId\",\"params\":[]}"));
				builder.auth().ifPresent(a -> pedido.header("Authorization", a));
				http.send(pedido.build(), HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				br.com.node.util.Falhas.anotar("aquecimento do relay " + builder.nome(), e);
			}
		}
	}

	// Submete o bundle (txs cruas EM ORDEM, hex com 0x): `[tx alvo, nossa tx]`. Devolve vazio se o
	// relay recusou ou está desligado; o id/hash do bundle quando aceito. O corpo de erro vai ao log.
	// O corpo JSON-RPC do bundle. A lista de revert vazia tem nome diferente em cada geração da API.
	static JsonObject corpo(String metodo, List<String> txsHex, long maxTimestamp) {
		return corpo(metodo, txsHex, maxTimestamp, false);
	}

	// POSIÇÃO PELA PRIORIDADE (`positionFirst`, na 48club e na BlockRazor). Pela documentação das duas: desligada (o padrão), "bundles
	// que chegam tarde podem ser anexados ao final para garantir que entrem no bloco atual". No final, a pool da fenda
	// já foi mexida por quem ficou no topo, a nossa tx reverte e o bundle cai — medido em 27/09: 73 de 73 fendas reais
	// perdidas com a pool mexida nas posições 1–4, e a BlockRazor confirmando "non-reverting tx in bundle failed:
	// execution reverted" em bundles que ela RECEBEU. Ligada, a posição é estritamente pelo lance: se pagamos mais que
	// quem está no topo e o builder ainda remonta o bloco, entramos na frente; se não, não entramos. É o teste que
	// separa lance de tempo. `POSICAO_POR_PRIORIDADE=0` volta ao padrão, para o A/B.
	static final boolean POSICAO_POR_PRIORIDADE = !"0".equals(System.getenv("POSICAO_POR_PRIORIDADE"));

	static JsonObject corpo(String metodo, List<String> txsHex, long maxTimestamp, boolean posicaoPorPrioridade) {

		JsonArray txs = new JsonArray();
		txsHex.forEach(tx -> txs.add(tx.startsWith("0x") ? tx : "0x" + tx));

		JsonObject params = new JsonObject();
		params.add("txs", txs);
		params.addProperty("maxTimestamp", maxTimestamp);
		// vazio: só entra se TUDO suceder — revert não paga
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

	// Um builder: aceito com o hash do bundle, ou recusado com o motivo normalizado (para ser CONTADO).
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
			return new Resposta(false, motivo, null, latenciaMs, statusHttp, null, null, null, respostaBruta,
					truncada, null, null, null, null, hashesTransacoes);
		}

		if (statusHttp / 100 == 2 && json.has("result") && !json.get("result").isJsonNull()) {
			String bundle = json.get("result").isJsonPrimitive() ? json.get("result").getAsString()
					: json.get("result").toString();
			return new Resposta(true, null, bundle, latenciaMs, statusHttp, null, null, null, respostaBruta,
					truncada, null, null, null, null, hashesTransacoes);
		}

		JsonObject erro = json.has("error") && json.get("error").isJsonObject()
				? json.getAsJsonObject("error") : null;
		String codigoErro = erro == null || !erro.has("code") || erro.get("code").isJsonNull()
				? null : textoErro(erro, "code");
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
		return valor.isJsonPrimitive() && valor.getAsJsonPrimitive().isString()
				? valor.getAsString() : valor.toString();
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
