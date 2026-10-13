package br.com.node.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.node.RelatorioExecucao;
import br.com.node.ensaio.AutopsiaDoMercado;

public final class HttpApi {

	private static final Logger LOG = Logger.getLogger(HttpApi.class.getName());
	private static final Gson GSON = new Gson();

	private final HttpServer servidor;

	@SuppressWarnings("unchecked")
	public HttpApi(int porta, RelatorioExecucao relatorio, AutopsiaDoMercado mercado,
			Path arquivoDespachos, Supplier<Map<String, Object>> pools) throws IOException {
		servidor = HttpServer.create(new InetSocketAddress(porta), 0);
		servidor.createContext("/status", troca -> responder(troca, () -> resumo(relatorio)));
		servidor.createContext("/relatorio", troca -> responder(troca, relatorio::snapshot));
		servidor.createContext("/mercado", troca -> responder(troca, () -> mercadoFiltrado(mercado, troca.getRequestURI().getQuery())));
		servidor.createContext("/despachos", troca -> {
			try {
				responder(troca, () -> {
					try {
						return despachos(arquivoDespachos, troca.getRequestURI().getQuery());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		servidor.createContext("/pools", troca -> responder(troca, (Supplier<Object>) (Supplier<?>) pools));
		servidor.setExecutor(null);
	}

	public void iniciar() {
		servidor.start();
		LOG.info(() -> "HTTP API no ar: http://localhost:" + servidor.getAddress().getPort());
	}

	private static void responder(HttpExchange troca, Supplier<Object> dados) throws IOException {
		byte[] corpo;
		int status;
		try {
			String json = GSON.toJson(dados.get());
			corpo = json.getBytes(StandardCharsets.UTF_8);
			status = 200;
		} catch (Exception e) {
			corpo = ("{\"erro\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
			status = 500;
		}
		troca.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		troca.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		troca.sendResponseHeaders(status, corpo.length);
		try (OutputStream os = troca.getResponseBody()) {
			os.write(corpo);
		}
	}

	private static Map<String, Object> resumo(RelatorioExecucao relatorio) {
		Map<String, Object> snap = relatorio.snapshot();
		Map<String, Object> r = new java.util.LinkedHashMap<>();
		r.put("instante", snap.get("instante"));
		r.put("inicio", snap.get("inicio"));
		r.put("duracao_segundos", snap.get("duracao_segundos"));
		r.put("cursor_altura", snap.get("cursor_altura"));
		r.put("sessoes_ativas", snap.get("sessoes_ativas"));
		r.put("sessoes_conhecidas", snap.get("sessoes_conhecidas"));
		r.put("latencia_recente_ms", snap.get("latencia_recente_ms"));
		r.put("pools_v2", snap.get("pools_v2"));
		r.put("pools_v3", snap.get("pools_v3"));
		r.put("arbitragem", snap.get("arbitragem"));
		r.put("ensaio", snap.get("ensaio"));
		return r;
	}

	private static Object mercadoFiltrado(AutopsiaDoMercado mercado, String query) {
		int n = 100;
		String classe = null;
		if (query != null) {
			for (String par : query.split("&")) {
				String[] kv = par.split("=", 2);
				if (kv.length == 2) {
					if ("n".equals(kv[0])) n = Integer.parseInt(kv[1]);
					if ("classe".equals(kv[0])) classe = kv[1];
				}
			}
		}
		List<Map<String, Object>> lista = mercado.recentes(n, classe);
		Map<String, Object> r = new java.util.LinkedHashMap<>();
		r.put("total", lista.size());
		r.put("entradas", lista);
		return r;
	}

	@SuppressWarnings("unchecked")
	private static Object despachos(Path arquivo, String query) throws IOException {
		int n = 50;
		if (query != null) {
			for (String par : query.split("&")) {
				String[] kv = par.split("=", 2);
				if (kv.length == 2 && "n".equals(kv[0])) n = Integer.parseInt(kv[1]);
			}
		}
		if (!Files.exists(arquivo)) return Map.of("total", 0, "entradas", List.of());
		List<String> linhas = Files.readAllLines(arquivo);
		List<Map<String, Object>> entradas = linhas.stream()
				.skip(Math.max(0, linhas.size() - n))
				.map(l -> (Map<String, Object>) GSON.fromJson(l, Map.class))
				.toList();
		return Map.of("total", linhas.size(), "entradas", entradas);
	}
}
