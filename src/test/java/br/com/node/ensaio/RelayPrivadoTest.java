package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.List;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

class RelayPrivadoTest {

	// A recusa real do primeiro bundle N+1 (23/09 20:55): o alvo já tinha sido minerado. Normalizada,
	// toda corrida perdida vira a MESMA chave e pode ser contada.
	@Test
	void recusaViraChaveContavel() {
		assertEquals("nonce too low: address …, tx: N state: N", RelayPrivado.normalizar(
				"[0x457d..268a] nonce too low: address 0x4D8Dc7A9bB55FE8df3AbC81e6e20322dd6a88Ca3, tx: 17 state: 18"));
		assertEquals("execution reverted", RelayPrivado.normalizar("[0xd7c5..9247] execution reverted"));
	}

	@Test
	void preservaErroOriginalEIdentificaTransacaoRevertidaNoBundle() {
		String hashAlvo = "0x" + "11".repeat(32);
		String hashArb = "0x" + "22".repeat(32);
		String frase = "Pancake: TRANSFER_FAILED";
		String data = erroComTexto(frase);
		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"[" + hashArb
				+ "] execution reverted: " + frase + "\",\"data\":\"0x" + data + "\"}}";

		RelayPrivado.Resposta resposta = RelayPrivado.interpretarResposta(200, corpo, List.of(hashAlvo, hashArb), 7);

		assertFalse(resposta.aceito());
		assertEquals("execution reverted: Pancake: TRANSFER_FAILED", resposta.motivo());
		assertEquals("3", resposta.codigoErro());
		assertTrue(resposta.mensagemErro().contains(hashArb));
		assertEquals("0x" + data, resposta.dadosErro());
		assertEquals(hashArb, resposta.hashTransacaoFalha());
		assertEquals(1, resposta.indiceTransacaoFalha());
		assertEquals("08c379a0", resposta.seletorRevert());
		assertEquals(frase, resposta.textoRevert());
		assertEquals(corpo, resposta.respostaBruta());
		assertEquals(List.of(hashAlvo, hashArb), resposta.hashesTransacoes());
	}

	@Test
	void preservaContadoresReaisDeNonceELimitaRespostaBruta() {
		String hashArb = "0x" + "ab".repeat(32);
		String corpo = "{\"error\":{\"code\":-32000,\"message\":\"[" + hashArb
				+ "] nonce too low: address 0x1234567890abcdef1234567890abcdef12345678, tx: 17 state: 18\"}}";
		RelayPrivado.Resposta resposta = RelayPrivado.interpretarResposta(200, corpo, List.of(hashArb), 2);

		assertEquals("nonce too low: address …, tx: N state: N", resposta.motivo());
		assertTrue(resposta.mensagemErro().contains("tx: 17 state: 18"));
		assertEquals(hashArb, resposta.hashTransacaoFalha());
		assertEquals(0, resposta.indiceTransacaoFalha());
		assertEquals(corpo, resposta.respostaBruta());

		RelayPrivado.Resposta grande = RelayPrivado.interpretarResposta(502, "x".repeat(9_000), List.of(), 1);
		assertTrue(grande.respostaTruncada());
		assertEquals(8_192, grande.respostaBruta().length());
	}

	// A v2 (puissant-builder) chama a lista de revert de `revertingTxHashes`; a 1ª geração, de
	// `acceptReverting`. Vazia nas duas: só entra se tudo suceder.
	@Test
	void corpoDaV2EDaAntiga() {

		JsonObject v2 = RelayPrivado.corpo("eth_sendBundle", List.of("aa", "0xbb"), 123).getAsJsonArray("params")
				.get(0).getAsJsonObject();

		assertEquals("0xaa", v2.getAsJsonArray("txs").get(0).getAsString());
		assertEquals(2, v2.getAsJsonArray("txs").size());
		assertTrue(v2.has("revertingTxHashes") && v2.getAsJsonArray("revertingTxHashes").isEmpty());
		assertFalse(v2.has("acceptReverting"));
		assertEquals(123, v2.get("maxTimestamp").getAsLong());

		JsonObject antiga = RelayPrivado.corpo("eth_sendPuissant", List.of("aa"), 1).getAsJsonArray("params").get(0)
				.getAsJsonObject();
		assertTrue(antiga.has("acceptReverting") && !antiga.has("revertingTxHashes"));
	}

	// Builders eth_sendBundle recebem o pagamento via `montar(destino)` — um por builder, cada um ao seu endereço.
	@Test
	void cadaBuilderEthSendBundleRecebeOSeuPagamento() {
		// Dois builders com eth_sendBundle (mevSendBid=false): pagamento vai dentro do bundle.
		RelayPrivado relay = new RelayPrivado(List.of(
				new RelayPrivado.Builder("a", "http://127.0.0.1:1/", "eth_sendBundle", java.util.Optional.empty(),
						RelayPrivado.PAGAMENTO_48CLUB, false, false, null, null),
				new RelayPrivado.Builder("b", "http://127.0.0.1:1/", "eth_sendBundle", java.util.Optional.empty(),
						RelayPrivado.PAGAMENTO_BLOCKRAZOR, false, false, null, null)));
		java.util.List<String> destinos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		// bid=null: sem builders mevSendBid, o parâmetro é ignorado.
		RelayPrivado.Envio envio = relay.enviar(null, destino -> {
			destinos.add(java.util.HexFormat.of().formatHex(destino));
			return List.of("0xaa");
		});

		assertEquals(List.of(HexFormat.of().formatHex(RelayPrivado.PAGAMENTO_48CLUB),
				"1266c6be60392a8ff346e8d5eccd3e69dd9c5f20"), destinos);
		assertEquals(List.of("a", "b"), List.copyOf(envio.porBuilder().keySet()));
		assertTrue(envio.porBuilder().values().stream().allMatch(r -> !r.aceito() && r.motivo().startsWith("transporte")),
				"porta fechada: recusa por transporte em cada um, sem derrubar o envio");
		String hash = "0x" + HexFormat.of().formatHex(
				br.com.node.util.Keccak.hash(HexFormat.of().parseHex("aa")));
		assertTrue(envio.porBuilder().values().stream().allMatch(r -> r.hashesTransacoes().equals(List.of(hash))),
				"até a falha de transporte conserva os hashes calculados para o bundle tentado");
	}

	// Builder mevSendBid=true usa mev_sendBid com BidArgsUpgrade; mevSendBid=false usa eth_sendBundle.
	@Test
	void builderMevSendBidUsaProtocolo48Club() {
		java.math.BigInteger zero = java.math.BigInteger.ZERO;
		byte[] tx = HexFormat.of().parseHex("abcd");
		RelayPrivado.BidArgs bid = new RelayPrivado.BidArgs(100L, new byte[32], List.of(tx),
				21_000L, zero, zero, new byte[65], new byte[0], zero);

		// Valida a estrutura JSON do corpo do mev_sendBid.
		com.google.gson.JsonObject corpo = RelayPrivado.corpo48Club(bid, "mev_sendBid");
		assertEquals("mev_sendBid", corpo.get("method").getAsString());
		com.google.gson.JsonObject args = corpo.getAsJsonArray("params").get(0).getAsJsonObject();
		com.google.gson.JsonObject rawBid = args.getAsJsonObject("RawBid");
		assertEquals(100L, rawBid.get("blockNumber").getAsLong());
		assertEquals("0x" + "00".repeat(32), rawBid.get("parentHash").getAsString());
		assertEquals("0xabcd", rawBid.getAsJsonArray("txs").get(0).getAsString());
		assertEquals(1, rawBid.getAsJsonArray("unRevertible").size());
		assertEquals("0x" + HexFormat.of().formatHex(br.com.node.util.Keccak.hash(tx)),
				rawBid.getAsJsonArray("unRevertible").get(0).getAsString());
		assertEquals(21_000L, rawBid.get("gasUsed").getAsLong());
		assertEquals("0", rawBid.get("gasFee").getAsString());
		assertEquals("0", rawBid.get("builderFee").getAsString());
		assertTrue(args.has("signature"));
		assertEquals("0x", args.get("payBidTx").getAsString());
		assertEquals(0L, args.get("payBidTxGasUsed").getAsLong());
		assertEquals("0", args.get("nontaxableFee").getAsString());
	}

	@Test
	void latenciaAteAceiteUsaOPrimeiroBuilderQueRespondeuSim() {
		RelayPrivado.Envio envio = new RelayPrivado.Envio(java.util.Map.of(
				"lento", new RelayPrivado.Resposta(true, null, "bundle-lento", 90),
				"rapido", new RelayPrivado.Resposta(true, null, "bundle-rapido", 25)));

		assertEquals(25, envio.latenciaAtePrimeiraAceitacaoMs());
	}

	@org.junit.jupiter.api.Test
	@org.junit.jupiter.api.DisplayName("positionFirst sai no corpo só quando pedido (48club e BlockRazor), e não sai no padrão")
	void posicaoPelaPrioridade() {
		com.google.gson.JsonObject com = RelayPrivado.corpo("eth_sendBundle", java.util.List.of("0x01"), 1L, true);
		com.google.gson.JsonObject sem = RelayPrivado.corpo("eth_sendBundle", java.util.List.of("0x01"), 1L, false);
		org.junit.jupiter.api.Assertions.assertTrue(
				com.getAsJsonArray("params").get(0).getAsJsonObject().get("positionFirst").getAsBoolean());
		org.junit.jupiter.api.Assertions.assertFalse(sem.getAsJsonArray("params").get(0).getAsJsonObject().has("positionFirst"));
	}

	private static String erroComTexto(String frase) {
		String texto = HexFormat.of().formatHex(frase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		texto += "0".repeat((64 - texto.length() % 64) % 64);
		String tamanho = String.format("%064x", frase.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
		return "08c379a0" + String.format("%064x", 32) + tamanho + texto;
	}
}
