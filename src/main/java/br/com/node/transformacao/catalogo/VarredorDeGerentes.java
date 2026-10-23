package br.com.node.transformacao.catalogo;

import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.transformacao.persistencia.PoolsNoGerenteRepository;
import br.com.node.vo.Log;

public final class VarredorDeGerentes implements Closeable {

	private static final Logger LOG = Logger.getLogger(VarredorDeGerentes.class.getName());

	private static final int FAIXA = 49_999;
	private static final int TENTATIVAS = 6;

	private static final Map<String, Long> NASCIMENTO = Map.of(HexFormat.of().formatHex(Eventos.GERENTE_V4),
			45_970_610L, HexFormat.of().formatHex(Eventos.GERENTE_INFINITY_CL), 47_214_308L);

	private final String url;
	private final PoolsNoGerenteRepository banco;
	private final Runnable aoAvancar;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Map<String, Object> estado = new ConcurrentHashMap<>();
	private volatile boolean parado;
	private Thread thread;

	public VarredorDeGerentes(String url, PoolsNoGerenteRepository banco, Runnable aoAvancar) {
		this.url = url;
		this.banco = banco;
		this.aoAvancar = aoAvancar;
	}

	public static Optional<String> urlDoAmbiente() {
		return Optional.ofNullable(System.getenv("RPC_LOGS")).map(String::trim).filter(u -> !u.isBlank());
	}

	public void iniciar() {

		thread = new Thread(this::varrerTudo, "varredura-de-gerentes");
		thread.setDaemon(true);
		thread.start();
	}

	private void varrerTudo() {

		for (byte[] gerente : List.of(Eventos.GERENTE_V4, Eventos.GERENTE_INFINITY_CL)) {

			try {
				varrer(gerente);
			} catch (RuntimeException falhou) {
				estado.put(HexFormat.of().formatHex(gerente), "parou: " + falhou.getMessage());
				LOG.warning(
						() -> "varredura de " + HexFormat.of().formatHex(gerente) + " parou: " + falhou.getMessage());
			}
		}
	}

	private void varrer(byte[] gerente) {

		String nome = HexFormat.of().formatHex(gerente);

		long inicio = banco.varridoAte(gerente).orElse(NASCIMENTO.get(nome) - 1) + 1;
		long cabeca = cabeca();
		long total = 0;
		long partida = inicio;

		LOG.info(() -> "varredura de " + nome + ": de " + partida + " até " + cabeca);

		while (inicio <= cabeca && !parado) {

			long fim = Math.min(inicio + FAIXA, cabeca);
			List<ChaveDaPool> chaves = faixa(gerente, inicio, fim);

			banco.gravarFaixa(gerente, chaves, fim);
			total += chaves.size();

			long feito = fim;
			long ate = total;
			estado.put(nome, Map.of("ate", feito, "cabeca", cabeca, "pools_gravadas", ate));

			inicio = fim + 1;
		}

		long gravadas = total;
		LOG.info(() -> "varredura de " + nome + " completa: " + gravadas + " pools novas");
		estado.put(nome, Map.of("completa_ate", cabeca, "pools_gravadas", gravadas));

		aoAvancar.run();
	}

	List<ChaveDaPool> faixa(byte[] gerente, long de, long ate) {

		byte[] topico = java.util.Arrays.equals(gerente, Eventos.GERENTE_V4) ? Eventos.INICIALIZAR_V4
				: Eventos.INICIALIZAR_INFINITY_CL;
		List<ChaveDaPool> chaves = new ArrayList<>();

		for (JsonElement bruto : logs(gerente, topico, de, ate)) {
			JsonObject log = bruto.getAsJsonObject();
			ChaveDaPool.doInitialize(paraLog(log), Long.decode(log.get("blockNumber").getAsString()))
					.ifPresent(chaves::add);
		}

		return chaves;
	}

	public Map<String, Object> resumo() {
		return Map.copyOf(estado);
	}

	private JsonArray logs(byte[] gerente, byte[] topico, long de, long ate) {

		JsonObject filtro = new JsonObject();
		filtro.addProperty("address", "0x" + HexFormat.of().formatHex(gerente));
		JsonArray topicos = new JsonArray();
		topicos.add("0x" + HexFormat.of().formatHex(topico));
		filtro.add("topics", topicos);
		filtro.addProperty("fromBlock", "0x" + Long.toHexString(de));
		filtro.addProperty("toBlock", "0x" + Long.toHexString(ate));

		JsonArray parametros = new JsonArray();
		parametros.add(filtro);

		return pedir("eth_getLogs", parametros).getAsJsonArray();
	}

	private long cabeca() {
		return Long.decode(pedir("eth_blockNumber", new JsonArray()).getAsString());
	}

	private JsonElement pedir(String metodo, JsonArray parametros) {

		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", parametros);

		String ultimo = "";

		for (int tentativa = 0; tentativa < TENTATIVAS && !parado; tentativa++) {

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build(),
						HttpResponse.BodyHandlers.ofString());

				JsonObject json = JsonParser.parseString(resposta.body()).getAsJsonObject();

				if (json.has("result")) {
					return json.get("result");
				}

				ultimo = String.valueOf(json.get("error"));

			} catch (java.io.IOException | RuntimeException falhou) {
				ultimo = falhou.getMessage();
			} catch (InterruptedException interrompido) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrompida");
			}

			try {
				Thread.sleep(1_000L << tentativa);
			} catch (InterruptedException interrompido) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrompida");
			}
		}

		throw new IllegalStateException(metodo + " falhou " + TENTATIVAS + " vezes: " + ultimo);
	}

	private static Log paraLog(JsonObject log) {

		List<byte[]> topicos = new ArrayList<>();
		log.getAsJsonArray("topics").forEach(t -> topicos.add(hex(t.getAsString())));

		return new Log(hex(log.get("address").getAsString()), topicos, hex(log.get("data").getAsString()));
	}

	private static byte[] hex(String texto) {
		return HexFormat.of().parseHex(texto.startsWith("0x") ? texto.substring(2) : texto);
	}

	@Override
	public void close() {
		parado = true;
	}
}