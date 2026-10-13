package br.com.node.conferencia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// Pergunta ao CONTRATO, por `eth_call` numa altura fixa, o que o node afirma saber.
//
// Existe só em teste, e isso é deliberado: o runtime não depende de RPC para o que o node recebe
// de graça (premissa). Aqui o RPC não é fonte, é TESTEMUNHA — a única independente do node, porque
// responde lendo o estado do contrato em vez de reler os mesmos eventos que o node leu.
//
// JANELA MEDIDA: nós públicos guardam estado por pouco mais de 50 blocos e já não por 500 ("missing
// trie node"). Então a conferência tem de acontecer perto da cabeça, e depressa — por isso lote e
// paralelo, e por isso "sem resposta" é contado à parte e nunca confundido com divergência.
public final class RpcDeConferencia {

	private static final List<String> NOS = List.of("https://bsc-dataseed.bnbchain.org",
			"https://bsc-dataseed1.binance.org", "https://bsc-rpc.publicnode.com");

	private static final int POR_LOTE = 20;

	// Leituras por agregado. getReserves custa ~2,5 mil de gas e getAmountsOut ~15 mil; 250 leituras
	// ficam muito abaixo do teto de gas de `eth_call` dos nós públicos (50 milhões por padrão).
	private static final int POR_AGREGADO = 250;

	// Pouco, de propósito: o projeto anterior levou 403 de nó público por VOLUME nosso.
	private static final int EM_PARALELO = 3;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	// Na mesma ordem das chamadas: o retorno em hex, ou vazio quando nenhum nó respondeu aquela.
	public List<Optional<String>> chamar(List<Chamada> chamadas, long altura) throws InterruptedException {

		ExecutorService paralelo = Executors.newFixedThreadPool(EM_PARALELO);

		try {

			List<Future<List<Optional<String>>>> lotes = new ArrayList<>();

			for (int inicio = 0; inicio < chamadas.size(); inicio += POR_LOTE) {

				List<Chamada> lote = chamadas.subList(inicio, Math.min(chamadas.size(), inicio + POR_LOTE));
				int primeiroNo = (inicio / POR_LOTE) % NOS.size();

				lotes.add(paralelo.submit(() -> lote(lote, altura, primeiroNo)));
			}

			List<Optional<String>> respostas = new ArrayList<>();

			for (Future<List<Optional<String>>> lote : lotes) {
				respostas.addAll(esperar(lote));
			}

			return respostas;

		} finally {
			paralelo.shutdownNow();
		}
	}

	// O mesmo que `chamar`, mas agregando as leituras em `aggregate3` do Multicall3: poucas `eth_call`
	// em vez de milhares, para caber na janela de estado dos nós públicos. Um agregado sem resposta
	// deixa sem resposta todas as leituras dele — nunca as marca como divergentes.
	public List<Optional<String>> chamarAgregado(List<Chamada> chamadas, long altura) throws InterruptedException {

		List<List<Chamada>> grupos = new ArrayList<>();
		List<Chamada> agregados = new ArrayList<>();

		for (int inicio = 0; inicio < chamadas.size(); inicio += POR_AGREGADO) {

			List<Chamada> grupo = chamadas.subList(inicio, Math.min(chamadas.size(), inicio + POR_AGREGADO));

			grupos.add(grupo);
			agregados.add(new Chamada(Multicall3.ENDERECO, Multicall3.codificar(grupo)));
		}

		List<Optional<String>> retornos = chamar(agregados, altura);
		List<Optional<String>> respostas = new ArrayList<>();

		for (int i = 0; i < grupos.size(); i++) {

			int tamanho = grupos.get(i).size();

			respostas.addAll(retornos.get(i).map(retorno -> Multicall3.decodificar(retorno, tamanho))
					.orElseGet(() -> vazio(tamanho)));
		}

		return respostas;
	}

	// A cabeça segundo a rede, para saber a quantos blocos dela a foto foi tirada — é essa distância
	// que decide se a conferência ainda cabe na janela de estado.
	public long cabeca() {

		for (String no : NOS) {

			try {

				HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(no))
						.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers
								.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_blockNumber\",\"params\":[]}"))
						.build(), HttpResponse.BodyHandlers.ofString());

				return Long.decode(JsonParser.parseString(resposta.body()).getAsJsonObject().get("result").getAsString());

			} catch (Exception naoRespondeu) {
				// próximo nó
			}
		}

		throw new IllegalStateException("nenhum nó público respondeu a cabeça");
	}

	// Tenta os nós em rodízio: um nó que recusa o lote não pode virar "a pool diverge".
	private List<Optional<String>> lote(List<Chamada> lote, long altura, int primeiroNo) {

		List<Optional<String>> melhor = vazio(lote.size());

		for (int tentativa = 0; tentativa < NOS.size(); tentativa++) {

			List<Optional<String>> respostas = perguntar(NOS.get((primeiroNo + tentativa) % NOS.size()), lote, altura);

			if (respostas.stream().filter(Optional::isPresent).count() > melhor.stream().filter(Optional::isPresent)
					.count()) {
				melhor = respostas;
			}

			if (melhor.stream().allMatch(Optional::isPresent)) {
				break;
			}
		}

		return melhor;
	}

	// `eth_call` com ESTADO FORJADO (o terceiro parâmetro do eth_call), numa altura, tentando os nós em
	// rodízio. É o que a sondagem de imposto precisa: injetar o código da sonda num endereço e dar saldo
	// a ele escrevendo no armazenamento do token, sem alterar a cadeia. O override é um objeto JSON já
	// montado por quem chama, porque a forma (`code`, `stateDiff`) é do domínio da sonda, não do RPC.
	//
	// Um por vez, sem lote: o override é grande e específico de cada medição, e agrupar dois num
	// aggregate3 misturaria os estados forjados. A janela de 90 a 110 blocos vale aqui também — quem usa
	// mede perto da cabeça.
	// `eth_getStorageAt`: lê um slot cru do armazenamento de um contrato. A sondagem usa para descobrir
	// em que slot o token guarda os saldos, procurando o slot cujo valor bate com o saldo lido.
	public Optional<String> armazenamento(String contrato, String slot, long altura) {

		for (String no : NOS) {

			JsonArray parametros = new JsonArray();
			parametros.add(contrato);
			parametros.add(slot);
			parametros.add("0x" + Long.toHexString(altura));

			JsonObject pedido = new JsonObject();
			pedido.addProperty("jsonrpc", "2.0");
			pedido.addProperty("id", 1);
			pedido.addProperty("method", "eth_getStorageAt");
			pedido.add("params", parametros);

			try {

				HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(no))
						.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(pedido.toString())).build(), HttpResponse.BodyHandlers.ofString());

				JsonObject objeto = JsonParser.parseString(resposta.body()).getAsJsonObject();

				if (objeto.has("result")) {
					return Optional.of(objeto.get("result").getAsString());
				}

			} catch (Exception naoRespondeu) {
				// próximo nó
			}
		}

		return Optional.empty();
	}

	public Optional<String> chamarComEstado(String para, String dados, String estadoJson, long altura) {

		for (String no : NOS) {

			Optional<String> resposta = comEstado(no, para, dados, estadoJson, altura);

			if (resposta.isPresent()) {
				return resposta;
			}
		}

		return Optional.empty();
	}

	private Optional<String> comEstado(String no, String para, String dados, String estadoJson, long altura) {

		JsonObject chamada = new JsonObject();
		chamada.addProperty("to", para);
		chamada.addProperty("data", dados);

		JsonArray parametros = new JsonArray();
		parametros.add(chamada);
		parametros.add("0x" + Long.toHexString(altura));
		parametros.add(JsonParser.parseString(estadoJson));

		JsonObject pedido = new JsonObject();
		pedido.addProperty("jsonrpc", "2.0");
		pedido.addProperty("id", 1);
		pedido.addProperty("method", "eth_call");
		pedido.add("params", parametros);

		try {

			HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(no))
					.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(pedido.toString())).build(), HttpResponse.BodyHandlers.ofString());

			JsonObject objeto = JsonParser.parseString(resposta.body()).getAsJsonObject();

			return objeto.has("result") ? Optional.of(objeto.get("result").getAsString()) : Optional.empty();

		} catch (Exception naoRespondeu) {
			return Optional.empty();
		}
	}

	private List<Optional<String>> perguntar(String no, List<Chamada> lote, long altura) {

		JsonArray corpo = new JsonArray();

		for (int i = 0; i < lote.size(); i++) {

			JsonObject chamada = new JsonObject();
			chamada.addProperty("to", lote.get(i).para());
			chamada.addProperty("data", lote.get(i).dados());

			JsonArray parametros = new JsonArray();
			parametros.add(chamada);
			parametros.add("0x" + Long.toHexString(altura));

			JsonObject pedido = new JsonObject();
			pedido.addProperty("jsonrpc", "2.0");
			pedido.addProperty("id", i);
			pedido.addProperty("method", "eth_call");
			pedido.add("params", parametros);

			corpo.add(pedido);
		}

		try {

			HttpResponse<String> resposta = http.send(HttpRequest.newBuilder(URI.create(no))
					.timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build(), HttpResponse.BodyHandlers.ofString());

			return ler(resposta.body(), lote.size());

		} catch (Exception naoRespondeu) {
			return vazio(lote.size());
		}
	}

	private static List<Optional<String>> ler(String corpo, int quantas) {

		Map<Integer, String> porId = new HashMap<>();

		JsonElement raiz = JsonParser.parseString(corpo);

		if (raiz.isJsonArray()) {

			for (JsonElement item : raiz.getAsJsonArray()) {

				JsonObject objeto = item.getAsJsonObject();

				if (objeto.has("result") && objeto.has("id")) {
					porId.put(objeto.get("id").getAsInt(), objeto.get("result").getAsString());
				}
			}
		}

		List<Optional<String>> respostas = new ArrayList<>();

		for (int i = 0; i < quantas; i++) {
			respostas.add(Optional.ofNullable(porId.get(i)).filter(hex -> hex.length() > 2));
		}

		return respostas;
	}

	private static List<Optional<String>> esperar(Future<List<Optional<String>>> lote) throws InterruptedException {

		try {
			return lote.get();
		} catch (java.util.concurrent.ExecutionException falhou) {
			throw new IllegalStateException("lote de conferência falhou", falhou.getCause());
		}
	}

	private static List<Optional<String>> vazio(int quantas) {

		List<Optional<String>> vazio = new ArrayList<>();

		for (int i = 0; i < quantas; i++) {
			vazio.add(Optional.empty());
		}

		return vazio;
	}
}
