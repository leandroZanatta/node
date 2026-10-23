package br.com.node.auditoria.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.node.auditoria.Auditoria;
import br.com.node.auditoria.Consulta;

public final class HttpApi {

	private static final Logger LOG = Logger.getLogger(HttpApi.class.getName());
	private static final Gson GSON = new Gson();
	private static final int LIMITE_MAXIMO = 500;

	private final HttpServer servidor;

	public HttpApi(int porta, Consulta consulta, Auditoria auditoria, Supplier<Map<String, Object>> estadoDoNode) {
		try {
			servidor = HttpServer.create(new InetSocketAddress(porta), 0);
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível abrir a API de auditoria na porta " + porta, e);
		}
		servidor.createContext("/despachos", troca -> responder(troca,
				() -> consulta.despachos(limite(troca.getRequestURI().getQuery()))));
		servidor.createContext("/arbitragens", troca -> responder(troca,
				() -> consulta.arbitragens(limite(troca.getRequestURI().getQuery()))));
		servidor.createContext("/resumo", troca -> responder(troca, consulta::resumo));
		servidor.createContext("/estado", troca -> responder(troca, () -> {
			JsonObject estado = new JsonObject();
			estado.add("auditoria", GSON.toJsonTree(auditoria.estado()));
			estado.add("node", GSON.toJsonTree(estadoDoNode.get()));
			return estado;
		}));
	}

	public void iniciar() {
		servidor.start();
		LOG.info(() -> "auditoria: API no ar em " + servidor.getAddress());
	}

	public void fechar() {
		servidor.stop(0);
	}

	private static void responder(HttpExchange troca, Supplier<JsonObject> corpo) throws IOException {
		byte[] bytes;
		int status;
		try {
			bytes = GSON.toJson(corpo.get()).getBytes(StandardCharsets.UTF_8);
			status = 200;
		} catch (RuntimeException falhou) {
			LOG.warning(() -> "auditoria: consulta falhou em " + troca.getRequestURI() + ": " + falhou.getMessage());
			bytes = GSON.toJson(Map.of("erro", String.valueOf(falhou.getMessage()))).getBytes(StandardCharsets.UTF_8);
			status = 500;
		}
		troca.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		troca.sendResponseHeaders(status, bytes.length);
		try (OutputStream saida = troca.getResponseBody()) {
			saida.write(bytes);
		}
	}

	private static int limite(String consulta) {
		if (consulta == null) {
			return 10;
		}
		for (String par : consulta.split("&")) {
			if (par.startsWith("n=")) {
				try {
					return Math.max(1, Math.min(LIMITE_MAXIMO, Integer.parseInt(par.substring(2))));
				} catch (NumberFormatException e) {
					return 10;
				}
			}
		}
		return 10;
	}
}
