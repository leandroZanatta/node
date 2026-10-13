package br.com.node.ensaio;

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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// O transporte JSON-RPC contra a BSC pública para o caminho de EXECUÇÃO: nonce, preço de gás e o
// envio da tx assinada. Próprio e não o `sondagem.Rpc` porque aqui o corpo do erro precisa sobreviver
// inteiro (o envio lê o motivo da recusa dele), e o rodízio de nós espalha a carga.
public final class RpcDoEnsaio {

	// Sem o publicnode: ele recusa `eth_getTransactionReceipt` sem token pago (HTTP 403, visto em 27/09).
	private static final List<String> NOS = List.of("https://bsc-dataseed.bnbchain.org",
			"https://bsc-dataseed1.binance.org");

	private static final Logger LOG = Logger.getLogger(RpcDoEnsaio.class.getName());

	// Teto de plausibilidade do `eth_estimateGas`. Acima disto o nó devolveu lixo (ou uma chamada
	// que ia consumir o bloco inteiro); virar limite de gás seria assinar um gasto absurdo.
	private static final BigInteger GAS_MAXIMO_PLAUSIVEL = BigInteger.valueOf(2_000_000L);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	// Os nós deste cliente: os públicos por padrão, ou um só (ex.: `RPC_LOGS`, que guarda o estado dos blocos
	// passados — a autópsia precisa do estado do bloco N, e os dataseeds já o descartaram).
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

	// Rodízio simples: o próximo nó leva a próxima chamada, para espalhar a carga.
	private int proximo;

	// O nonce da PRÓXIMA tx do operador (estado pendente, para não colidir com tx
	// já no mempool).
	public long contagemDeTransacoes(String endereco) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + endereco);
		parametros.add("pending");

		return resultado("eth_getTransactionCount", parametros).map(Long::decode)
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu o nonce"));
	}

	// O nó já TEM o bloco `altura`? CICATRIZ de 27/09: o nodereal responde `eth_call` para 1 a 3 blocos ACIMA da
	// cabeça dele sem erro nenhum — simula no estado mais novo que tem —, e só diz "header not found" mais adiante.
	// Vemos o bloco antes dele, então o veredito pedido na hora simulava no N−1 e acusava revert em fenda que fechava.
	public boolean temBloco(long altura) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + Long.toHexString(altura));
		parametros.add(false);

		return corpoDe("eth_getBlockByNumber", parametros).map(JsonParser::parseString).map(com.google.gson.JsonElement::getAsJsonObject)
				.map(o -> o.has("result") && !o.get("result").isJsonNull()).orElse(false);
	}

	// Espera o nó ter o bloco, até `tentativas` × `esperaMs`. Falso se não chegou.
	public boolean esperarBloco(long altura, int tentativas, long esperaMs) throws InterruptedException {
		for (int i = 0; i < tentativas; i++) {
			if (temBloco(altura)) {
				return true;
			}
			Thread.sleep(esperaMs);
		}
		return temBloco(altura);
	}

	// O saldo de BNB de uma conta, em wei (estado mais recente).
	public BigInteger saldoDe(String endereco) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + endereco);
		parametros.add("latest");

		return resultado("eth_getBalance", parametros).map(hex -> new BigInteger(hex.substring(2), 16))
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu o saldo de 0x" + endereco));
	}

	// Saldo ERC-20 de `conta` no contrato `token` (balanceOf), em wei. Zero se o nó não responder.
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

	// O preço de gás sugerido pela rede, em wei.
	public BigInteger precoDeGas() {
		return resultado("eth_gasPrice", new JsonArray()).map(hex -> new BigInteger(hex.substring(2), 16))
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu o preço do gás"));
	}

	// O gás EXATO que a EVM gastaria nesta chamada, via `eth_estimateGas`. É o único oráculo que
	// mede imposto, honeypot, tick cruzado e slot frio de uma vez — a estimativa analítica do
	// `CustoDeGas` é teto calibrado, não medição.
	//
	// O `from` é OBRIGATÓRIO: o `Executor` só aceita o operador, e sem ele o nó estima uma chamada
	// que reverteria por NaoAutorizado. O estado é `pending` porque é nele que o bundle entra.
	//
	// Vazio quando nenhum nó respondeu OU a estimativa reverteu (rota que não fecha no estado atual
	// — o revert é o próprio filtro); gás acima do teto de plausibilidade também vira vazio, em vez
	// de limite de gás.
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

	// ENVIA a tx assinada. O ÚNICO método que muda a cadeia — todo o resto aqui é
	// leitura. Devolve o
	// hash, ou vazio com o erro no log se o nó recusou (nonce, saldo de gás, revert
	// de estimativa).
	public Optional<String> enviar(String rawHex) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + rawHex);

		Optional<String> corpo = corpoDe("eth_sendRawTransaction", parametros);
		corpo.filter(c -> !c.contains("\"result\"")).ifPresent(c -> LOG.warning(() -> "envio recusado: " + c));

		return corpo.map(c -> JsonParser.parseString(c).getAsJsonObject()).filter(o -> o.has("result"))
				.map(o -> o.get("result").getAsString());
	}

	// O RECIBO da nossa tx: é ele que diz, no fim, quanto gás a EVM REALMENTE gastou — o único
	// número que fecha a conta da estimativa. `sucesso` separa incluída-e-fechou de
	// incluída-e-reverteu; o gás deste último também é gasto de verdade (só acontece no envio
	// público, onde revert paga), e por isso também ensina.
	public record Recibo(boolean sucesso, BigInteger gasUsado) {
	}

	// Vazio quando o nó ainda não tem o recibo — pendente, ou bundle que não foi incluído. Isso é
	// DIFERENTE de reverter, e o `status` do recibo é quem separa (0x1 sucesso, 0x0 revert).
	public Optional<Recibo> recibo(String hash) {

		JsonArray parametros = new JsonArray();
		parametros.add(comPrefixo(hash));

		return corpoDe("eth_getTransactionReceipt", parametros).flatMap(RpcDoEnsaio::interpretarRecibo);
	}

	// Público para o teste poder prová-lo sem rede: leitura de recibo é onde um `status` lido ao
	// contrário transforma revert em sucesso, e o erro não aparece em lugar nenhum.
	static Optional<Recibo> interpretarRecibo(String corpo) {

		JsonObject objeto;
		try {
			objeto = JsonParser.parseString(corpo).getAsJsonObject();
		} catch (RuntimeException e) {
			// JSON inválido ou não-objeto: lixo de rede, trata como ausência.
			return Optional.empty();
		}

		if (!objeto.has("result") || objeto.get("result").isJsonNull()) {
			return Optional.empty();
		}

		JsonObject recibo = objeto.getAsJsonObject("result");

		// Recibo que VEIO sem gás ou status não é "ainda não incluída": é resposta torta, e confundir as duas
		// fazia um envio incluído parecer perdido.
		if (!recibo.has("gasUsed") || !recibo.has("status")) {
			throw new IllegalStateException("recibo sem gasUsed/status: " + recibo);
		}

		try {
			BigInteger gas = new BigInteger(recibo.get("gasUsed").getAsString().substring(2), 16);
			boolean sucesso = "0x1".equalsIgnoreCase(recibo.get("status").getAsString());
			return Optional.of(new Recibo(sucesso, gas));
		} catch (RuntimeException e) {
			throw new IllegalStateException("recibo ilegível: " + recibo, e);
		}
	}

	// O VEREDITO do contrato para esta chamada, via eth_call — para SABER por que
	// um envio não fecha,
	// em vez de ficar cego no "execution reverted" do relay. Vem em duas partes: o
	// SELETOR (8 hex:
	// 7978d88b=EmprestimoNaoV2, 54c59343=NaoPagou, f2beb76b=SemLucro,
	// 7ad3ddd8=TokenForaDaPool…) e o
	// TEXTO, que é a frase do revert quando ela existe.
	//
	// A FRASE É O ACHADO. MEDIDO no `run-armado`: 5 dos 7 envios caíram em
	// `0x08c379a0` — que é
	// `Error(string)`, o revert COM mensagem — e a mensagem (`Pancake:
	// TRANSFER_FAILED`) foi jogada
	// fora, deixando cinco reverts idênticos reduzidos a um seletor opaco. O mesmo
	// nó que responde só
	// `"message":"execution reverted"` deixa a frase codificada em `data`; ler os
	// dois caminhos é o
	// que faz a MESMA recusa ser lida igual em qualquer endpoint.
	//
	// É diagnóstico, roda FORA do caminho de envio — não adiciona latência ao
	// disparo.
	public record Veredicto(String seletor, String texto) {

		public Veredicto {
			seletor = seletor == null ? "" : seletor;
			texto = texto == null ? "" : texto;
		}
	}

	/** {@code Error(string)} — o revert com mensagem, da própria linguagem. */
	static final String ERRO_COM_TEXTO = "08c379a0";

	public Veredicto veredito(String de, String para, String dados) {
		return veredito(de, para, dados, "latest");
	}

	// No BLOCO dado. CICATRIZ de 27/09: o veredito ia no `latest` de um dataseed público, que está ATRÁS de nós
	// (vemos o bloco antes dele) — simulava a rota num bloco em que a fenda ainda não existia e acusava prejuízo:
	// 6 de 7 fendas que fechavam no bloco N saíram "inválidas".
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

	// O `eth_call` com OVERRIDE de estado (o terceiro parâmetro): é como a `Autopsia` roda um código que
	// não está deployado no endereço do Executor. Devolve o CORPO cru — o laudo vem no `data` do erro,
	// e quem o lê é quem sabe decodificá-lo. Vazio só quando nenhum nó respondeu.
	public Optional<String> chamarComOverride(String de, String para, String dados, JsonObject override) {
		return chamarComOverride(de, para, dados, override, "latest");
	}

	// O mesmo, num bloco dado (`0x…` ou "latest"). É o que deixa a autópsia comparar o modelo com a
	// cadeia NO MESMO INSTANTE: no `latest`, a pool que andou um bloco depois do achado aparecia como
	// "estado errado" do modelo, quando era só o mercado.
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

	// Lê o JSON-RPC de resposta do `eth_call`: `result` é aceitação; `error` é
	// recusa, e o texto dele
	// (no `data` codificado ou no `message`) é o motivo. Separado para poder ser
	// testado sem rede — é
	// exatamente aqui que a frase estava sendo perdida.
	static Veredicto interpretar(String corpo) {

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

	// A frase do revert, venha ela do `data` codificado ou do texto do nó. Os dois
	// caminhos existem
	// porque os nós divergem, e quem lê só um classifica a MESMA recusa de formas
	// diferentes.
	static String frase(String dadosHex, String mensagem) {

		if (dadosHex.length() >= 8 && dadosHex.regionMatches(true, 0, ERRO_COM_TEXTO, 0, 8)) {

			Optional<String> frase = textoAbi(dadosHex);

			if (frase.isPresent()) {
				return frase.get();
			}
		}

		int onde = mensagem.indexOf("reverted: ");

		return onde < 0 ? "" : mensagem.substring(onde + "reverted: ".length()).trim();
	}

	// Decodifica o argumento de `Error(string)`: offset (32) + comprimento (32) +
	// bytes. Comprimento
	// absurdo ou truncado devolve vazio em vez de inventar frase.
	static Optional<String> textoAbi(String dados) {

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

	// O bloco com as transações COMPLETAS (hash, to, input). É o que liga a posição de uma tx no bloco —
	// que é tudo o que os recibos dão — ao hash dela, e o hash ao que a nossa mempool viu.
	public Optional<JsonObject> bloco(long altura) {

		JsonArray parametros = new JsonArray();
		parametros.add("0x" + Long.toHexString(altura));
		parametros.add(true);

		return corpoDe("eth_getBlockByNumber", parametros).map(texto -> JsonParser.parseString(texto).getAsJsonObject())
				.filter(objeto -> objeto.has("result") && objeto.get("result").isJsonObject())
				.map(objeto -> objeto.getAsJsonObject("result"));
	}

	// Chamada crua, corpo inteiro de volta (erro inclusive). Para quem fala um método que esta classe
	// não embrulha — o `eth_simulateV1` do simulador da mempool.
	public Optional<String> chamar(String metodo, JsonArray parametros) {
		return corpoDe(metodo, parametros);
	}

	public long cabeca() {

		return corpoDe("eth_blockNumber", new JsonArray()).map(texto -> JsonParser.parseString(texto).getAsJsonObject())
				.filter(objeto -> objeto.has("result")).map(objeto -> Long.decode(objeto.get("result").getAsString()))
				.orElseThrow(() -> new IllegalStateException("nenhum nó respondeu a cabeça da BSC"));
	}

	// Devolve o CORPO — o de erro inclusive. Vazio só quando nenhum nó respondeu no
	// transporte,
	// que é diferente de reverter, e o veredito separa os dois.
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

				// HTTP fora de 2xx também é falha do nó (limite de taxa, 5xx) — antes pulava calado.
				br.com.node.util.Falhas.anotar("RPC " + no, new IllegalStateException(
						"HTTP " + resposta.statusCode() + " em " + metodo + ": " + resposta.body()));

			} catch (Exception transporte) {
				// Próximo nó; não é resposta da cadeia — mas fica contado e à vista.
				br.com.node.util.Falhas.anotar("RPC " + no, transporte);
			}
		}

		return Optional.empty();
	}
}