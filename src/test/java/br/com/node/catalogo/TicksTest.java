package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.transformacao.nucleo.MapaDeTicks;

class TicksTest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@Test
	@DisplayName("V3 (PancakeSwap 0xee6f…, espaçamento 1): a foto tem os ticks e liquidityNet da leitura direta")
	void v3() throws Exception {
		conferir(HexFormat.of().parseHex("ee6ff918a1f68b5d2fdecb14b367fa2eb5c6951c"), null, 1);
	}

	@Test
	@DisplayName("V4 (0x0b2b…, espaçamento 1) pelo StateView")
	void v4() throws Exception {
		conferir(HexFormat.of().parseHex("0b2b734ec640e292244dedae1cedbd85de845dee61f5a0b2c3a85d9cbd2ac1e7"),
				Eventos.GERENTE_V4, 1);
	}

	@Test
	@DisplayName("Infinity CL (nativo/USDT 0xd37a…, espaçamento 1) pelo gerente")
	void infinity() throws Exception {
		conferir(HexFormat.of().parseHex("d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944"),
				Eventos.GERENTE_INFINITY_CL, 1);
	}

	private void conferir(byte[] pool, byte[] gerente, int espacamento) throws Exception {

		Optional<String> url = VarredorDeGerentes.urlDoAmbiente();
		assumeTrue(url.isPresent(), "sem RPC_LOGS");

		String bloco = "0x"
				+ Long.toHexString(Long.decode(rpc(url.get(), "eth_blockNumber", new JsonArray()).getAsString()) - 3);
		int tick = tickCorrente(url.get(), pool, gerente, bloco);

		Ticks ticks = new Ticks((para, dados, b) -> ethCall(url.get(), para, dados, b));
		MapaDeTicks mapa = ticks.fotografarAgora(pool, tick, espacamento, gerente, bloco).orElseThrow();

		assertTrue(mapa.tamanho() > 0, "a foto não achou tick inicializado nenhum");
		assertTrue(mapa.tickMinimo() <= tick && tick <= mapa.tickMaximo(), "a faixa tem de cobrir o tick corrente");

		
		
		Integer[] chaves = mapa.liquidas().keySet().toArray(new Integer[0]);
		for (int indice : new int[] { 0, chaves.length / 2, chaves.length - 1 }) {
			int t = chaves[indice];
			assertEquals(liquidaDireta(url.get(), pool, gerente, t, bloco), mapa.liquida(t),
					"liquidityNet do tick " + t);
		}
	}

	private static int tickCorrente(String url, byte[] pool, byte[] gerente, String bloco) throws Exception {

		String resposta = gerente == null
				? ethCall(url, HexFormat.of().formatHex(pool), "3850c7bd", bloco).orElseThrow()
				: ethCall(url,
						gerente == Eventos.GERENTE_V4 ? "d13dd3d6e93f276fafc9db9e6bb47c1180aee0c4"
								: HexFormat.of().formatHex(Eventos.GERENTE_INFINITY_CL),
						"c815641c" + HexFormat.of().formatHex(pool), bloco).orElseThrow();
		String semPrefixo = resposta.substring(2);

		return new BigInteger(HexFormat.of().parseHex(semPrefixo.substring(64, 128))).intValue();
	}

	private static BigInteger liquidaDireta(String url, byte[] pool, byte[] gerente, int tick, String bloco)
			throws Exception {

		String palavra = String.format("%064x",
				BigInteger.valueOf(tick).and(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)));
		String resposta;

		if (gerente == null) {
			resposta = ethCall(url, HexFormat.of().formatHex(pool), "f30dba93" + palavra, bloco).orElseThrow();
		} else if (gerente == Eventos.GERENTE_V4) {
			resposta = ethCall(url, "d13dd3d6e93f276fafc9db9e6bb47c1180aee0c4",
					"caedab54" + HexFormat.of().formatHex(pool) + palavra, bloco).orElseThrow();
		} else {
			resposta = ethCall(url, HexFormat.of().formatHex(Eventos.GERENTE_INFINITY_CL),
					"5aa208a4" + HexFormat.of().formatHex(pool) + palavra, bloco).orElseThrow();
		}

		return new BigInteger(HexFormat.of().parseHex(resposta.substring(2).substring(64, 128)));
	}

	private static Optional<String> ethCall(String url, String para, String dados, String bloco) {

		try {
			JsonObject chamada = new JsonObject();
			chamada.addProperty("to", "0x" + para);
			chamada.addProperty("data", "0x" + dados);
			JsonArray parametros = new JsonArray();
			parametros.add(chamada);
			parametros.add(bloco == null ? "latest" : bloco);
			return Optional.of(rpc(url, "eth_call", parametros).getAsString());
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private static com.google.gson.JsonElement rpc(String url, String metodo, JsonArray parametros) throws Exception {

		JsonObject corpo = new JsonObject();
		corpo.addProperty("jsonrpc", "2.0");
		corpo.addProperty("id", 1);
		corpo.addProperty("method", metodo);
		corpo.add("params", parametros);

		HttpResponse<String> resposta = HTTP.send(
				HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build(),
				HttpResponse.BodyHandlers.ofString());

		JsonObject json = JsonParser.parseString(resposta.body()).getAsJsonObject();
		if (!json.has("result")) {
			throw new IllegalStateException(String.valueOf(json.get("error")));
		}
		return json.get("result");
	}
}