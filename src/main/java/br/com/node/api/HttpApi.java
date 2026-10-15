package br.com.node.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.node.RelatorioExecucao;
import br.com.node.ensaio.AutopsiaDoMercado;
import br.com.node.ensaio.Disputa;

public final class HttpApi {

	private static final Logger LOG = Logger.getLogger(HttpApi.class.getName());
	private static final Gson GSON = new Gson();

	private final HttpServer servidor;

	@SuppressWarnings("unchecked")
	public HttpApi(int porta, RelatorioExecucao relatorio, AutopsiaDoMercado mercado,
			Path arquivoDespachos, Path arquivoPagamentos, Path arquivoVencedores, Path arquivoFendas,
			Supplier<Map<String, Object>> pools) throws IOException {
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
		servidor.createContext("/fendas", troca -> responder(troca, () -> {
			try {
				return fendas(arquivoFendas, troca.getRequestURI().getQuery());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}));
		servidor.createContext("/disputa", troca -> responder(troca, () -> {
			try {
				return disputa(arquivoDespachos, arquivoPagamentos, arquivoVencedores, troca.getRequestURI().getQuery());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}));
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

	// A pergunta de cada tentativa: se não levamos, foi por quanto, e quem levou pagou quanto. Cruza o despacho
	// com os pagamentos medidos no bloco do vencedor (ver `Disputa`).
	private static Object disputa(Path despachos, Path pagamentos, Path vencedores, String query) throws IOException {
		int n = 200;
		if (query != null) {
			for (String par : query.split("&")) {
				String[] kv = par.split("=", 2);
				if (kv.length == 2 && "n".equals(kv[0])) n = Integer.parseInt(kv[1]);
			}
		}
		if (!Files.exists(despachos)) {
			return Map.of("total", 0, "entradas", List.of());
		}
		List<String> linhas = Files.readAllLines(despachos);
		List<JsonObject> tentativas = linhas.stream().skip(Math.max(0, linhas.size() - n))
				.map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();

		// Só os blocos que interessam: o do vencedor de cada tentativa (N+1, ou N+2 para `rival_n2`).
		java.util.Set<Long> alturas = new java.util.HashSet<>();
		for (JsonObject t : tentativas) {
			long altura = t.getAsJsonObject("envio").get("altura").getAsBigDecimal().longValue();
			boolean n2 = t.getAsJsonObject("desfecho").get("classe").getAsString().endsWith("n2");
			alturas.add(altura + 1);
			if (n2) alturas.add(altura + 2);
		}
		Map<Long, List<JsonObject>> pagamentosPorAltura = new java.util.HashMap<>();
		if (Files.exists(pagamentos)) {
			for (String linha : Files.readAllLines(pagamentos)) {
				if (linha.isBlank()) continue;
				JsonObject p = JsonParser.parseString(linha).getAsJsonObject();
				long altura = p.get("altura").getAsLong();
				if (alturas.contains(altura)) {
					pagamentosPorAltura.computeIfAbsent(altura, k -> new ArrayList<>()).add(p);
				}
			}
		}

		// Quem ganhou, por hash da nossa tentativa (`vencedores.jsonl`, ver `VencedorDoLeilao`).
		Map<String, JsonObject> vencedorPorHash = new java.util.HashMap<>();
		if (Files.exists(vencedores)) {
			for (String linha : Files.readAllLines(vencedores)) {
				if (linha.isBlank()) continue;
				JsonObject v = JsonParser.parseString(linha).getAsJsonObject();
				vencedorPorHash.put(v.get("nosso_hash").getAsString(), v);
			}
		}

		List<Map<String, Object>> analises = new ArrayList<>();
		for (JsonObject t : tentativas) {
			long altura = t.getAsJsonObject("envio").get("altura").getAsBigDecimal().longValue();
			boolean n2 = t.getAsJsonObject("desfecho").get("classe").getAsString().endsWith("n2");
			List<JsonObject> doVencedor = new ArrayList<>(pagamentosPorAltura.getOrDefault(altura + 1, List.of()));
			if (n2) doVencedor.addAll(pagamentosPorAltura.getOrDefault(altura + 2, List.of()));
			JsonObject vencedor = vencedorPorHash.get(t.get("id").getAsString());
			analises.add(Disputa.analisar(t, doVencedor, vencedor));
		}

		Map<String, Object> r = new java.util.LinkedHashMap<>();
		r.put("total_tentativas", linhas.size());
		r.put("analisadas", analises.size());
		r.put("resumo", Disputa.resumir(analises));
		r.put("entradas", analises);
		return r;
	}

	// Fendas grandes (0,001 BNB ou mais): a abertura foi vista pendente antes do bloco? Resume por etapa.
	private static Object fendas(Path arquivo, String query) throws IOException {
		int n = 500;
		if (query != null) {
			for (String par : query.split("&")) {
				String[] kv = par.split("=", 2);
				if (kv.length == 2 && "n".equals(kv[0])) n = Integer.parseInt(kv[1]);
			}
		}
		if (!Files.exists(arquivo)) {
			return Map.of("total", 0, "por_etapa", Map.of(), "entradas", List.of());
		}
		List<String> linhas = Files.readAllLines(arquivo);
		List<JsonObject> entradas = linhas.stream().filter(l -> !l.isBlank())
				.skip(Math.max(0, linhas.size() - n)).map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();
		Map<String, Long> porEtapa = new java.util.TreeMap<>();
		for (JsonObject e : entradas) {
			porEtapa.merge(e.get("etapa_abertura").getAsString(), 1L, Long::sum);
		}
		Map<String, Object> r = new java.util.LinkedHashMap<>();
		r.put("total", linhas.size());
		r.put("por_etapa", porEtapa);
		r.put("entradas", entradas);
		return r;
	}
}
