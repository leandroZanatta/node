package br.com.node.ensaio;
import br.com.node.execucao.disparo.*;
import br.com.node.execucao.bundle.*;
import br.com.node.auditoria.registro.*;
import br.com.node.baixa.rpc.*;
import br.com.node.auditoria.pagamentos.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

class TransferenciasInternasTest {

	@Test
	void achaTransferenciaFeitaPeloContratoDentroDaTransacao() {
		
		
		
		String trace = """
				{"type":"CALL","from":"0x2222","to":"0x5894","value":"0x0",
				 "calls":[
				  {"type":"CALL","from":"0x5894","to":"0x238a","value":"0x0","calls":[]},
				  {"type":"CALL","from":"0x5894","to":"0x1266c6be60392a8ff346e8d5eccd3e69dd9c5f20","value":"0x1019e0ef1c0c9"}
				 ]}
				""";
		List<TransferenciasInternas.Transferencia> achadas = TransferenciasInternas
				.doTrace(JsonParser.parseString(trace).getAsJsonObject());

		assertEquals(1, achadas.size());
		assertEquals("5894", achadas.get(0).de());
		assertEquals("1266c6be60392a8ff346e8d5eccd3e69dd9c5f20", achadas.get(0).para());
		assertEquals(new BigInteger("1019e0ef1c0c9", 16), achadas.get(0).valorWei());
	}

	@Test
	void preservaErroJsonRpcDoTrace() {
		TransferenciasInternas.ResultadoTrace resultado = TransferenciasInternas.interpretarRespostaTrace("""
				{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"missing trie node"}}
				""");

		assertTrue(resultado.transferencias().isEmpty());
		assertEquals("JSON-RPC code=-32000: missing trie node", resultado.erro());
	}

	@Test
	void naoConfundeTraceSemPagamentosComFalha() {
		TransferenciasInternas.ResultadoTrace resultado = TransferenciasInternas.interpretarRespostaTrace("""
				{"jsonrpc":"2.0","id":1,"result":{"type":"CALL","from":"0x1","to":"0x2","value":"0x0","calls":[]}}
				""");

		assertTrue(resultado.transferencias().isPresent());
		assertTrue(resultado.transferencias().get().isEmpty());
		assertNull(resultado.erro());
	}

	@Test
	void expoeHostsDoRpcSemVazarCredenciaisOuCaminho() {
		RpcDoEnsaio rpc = new RpcDoEnsaio(List.of("https://usuario:senha@rpc.example/v1/token-secreto"));

		assertEquals(List.of("rpc.example"), rpc.hosts());
	}

	@Test
	void tentaProximoRpcQuandoAnteriorRespondeErroJsonRpc() throws IOException {
		AtomicInteger chamadasPrimeiro = new AtomicInteger();
		AtomicInteger chamadasSegundo = new AtomicInteger();
		HttpServer primeiro = servidor(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"missing trie node\"}}",
				chamadasPrimeiro);
		HttpServer segundo = servidor("""
				{"jsonrpc":"2.0","id":1,"result":{"type":"CALL","from":"0x1","to":"0x2","value":"0x1","calls":[]}}
				""", chamadasSegundo);
		primeiro.start();
		segundo.start();
		try {
			RpcDoEnsaio rpc = new RpcDoEnsaio(List.of(url(primeiro), url(segundo)));
			TransferenciasInternas.ResultadoTrace resultado = TransferenciasInternas.consultarTraceDetalhado(rpc,
					"0xabc");

			assertTrue(resultado.erro() == null);
			assertEquals(1, resultado.transferencias().orElseThrow().size());
			assertEquals(1, chamadasPrimeiro.get());
			assertEquals(1, chamadasSegundo.get());
		} finally {
			primeiro.stop(0);
			segundo.stop(0);
		}
	}

	private static HttpServer servidor(String corpo, AtomicInteger chamadas) throws IOException {
		HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/", troca -> {
			chamadas.incrementAndGet();
			byte[] bytes = corpo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			troca.sendResponseHeaders(200, bytes.length);
			try (var saida = troca.getResponseBody()) {
				saida.write(bytes);
			}
		});
		return servidor;
	}

	private static String url(HttpServer servidor) {
		return "http://127.0.0.1:" + servidor.getAddress().getPort();
	}
}