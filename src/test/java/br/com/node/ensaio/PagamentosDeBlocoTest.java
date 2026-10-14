package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

class PagamentosDeBlocoTest {

	private static final String BUILDER = "4848489f0b2bedd788c696e2d79b6b69d7484848";
	private static final String OUTRO = "1266c6be60392a8ff346e8d5eccd3e69dd9c5f20";
	private static final String VENCEDOR = "aa00000000000000000000000000000000000001";
	private static final String OUTRO_REMETENTE = "bb00000000000000000000000000000000000002";

	private static JsonObject tx(String de, String para, String valorHex, int indice) {
		JsonObject t = new JsonObject();
		t.addProperty("from", "0x" + de);
		t.addProperty("to", para == null ? null : "0x" + para);
		t.addProperty("value", valorHex);
		t.addProperty("hash", "0x" + String.format("%064x", indice + 1));
		t.addProperty("transactionIndex", "0x" + Integer.toHexString(indice));
		return t;
	}

	private static JsonObject bloco(JsonArray txs) {
		JsonObject b = new JsonObject();
		b.addProperty("number", "0x7c5a3f0");
		b.addProperty("miner", "0x" + BUILDER);
		b.add("transactions", txs);
		return b;
	}

	@Test
	void registraSoPagamentoParaAlvoEComValorPositivo() {
		JsonArray txs = new JsonArray();
		txs.add(tx(VENCEDOR, "dd00000000000000000000000000000000000004", "0x0", 0));
		txs.add(tx(VENCEDOR, BUILDER, "0x" + Long.toHexString(5_000_000_000_000_000L), 1));
		txs.add(tx(OUTRO_REMETENTE, OUTRO, "0x" + Long.toHexString(7L), 2));
		txs.add(tx(OUTRO_REMETENTE, "ee00000000000000000000000000000000000005", "0x" + Long.toHexString(9L), 3));
		txs.add(tx(VENCEDOR, OUTRO, "0x0", 4));

		List<PagamentosDeBloco.Pagamento> achados = PagamentosDeBloco.interpretar(bloco(txs),
				Set.of(BUILDER, OUTRO));

		assertEquals(2, achados.size());
		assertEquals(new BigInteger("5000000000000000"), achados.get(0).valorWei());
		assertEquals(BUILDER, achados.get(0).para());
		assertEquals(VENCEDOR, achados.get(0).de());
		assertEquals(1, achados.get(0).posicao());
		assertEquals(OUTRO, achados.get(1).para());
		assertEquals(BigInteger.valueOf(7), achados.get(1).valorWei());
		assertEquals(Long.decode("0x7c5a3f0"), achados.get(0).altura());
	}

	@Test
	void lookupExigeDestinoDoBuilderENaoQualquerTransferencia() {
		JsonArray txs = new JsonArray();
		txs.add(tx(VENCEDOR, "dd00000000000000000000000000000000000004", "0x0", 0));
		txs.add(tx(VENCEDOR, "dd00000000000000000000000000000000000004", "0x" + Long.toHexString(999L), 1));

		assertFalse(PagamentosDeBloco.pagamentoAoBuilder(bloco(txs), 0, Set.of(BUILDER)).isPresent(),
				"transferência do mesmo remetente para outro destino não é lance");
	}

	@Test
	void lookupAcharPagamentoProximoDaPosicaoDoVencedor() {
		JsonArray txs = new JsonArray();
		txs.add(tx(OUTRO_REMETENTE, "dd00000000000000000000000000000000000004", "0x0", 0));
		txs.add(tx(VENCEDOR, "dd00000000000000000000000000000000000004", "0x0", 1));
		txs.add(tx(VENCEDOR, BUILDER, "0x" + Long.toHexString(123_456L), 2));

		BigInteger pago = PagamentosDeBloco.pagamentoAoBuilder(bloco(txs), 1, Set.of(BUILDER)).orElseThrow();

		assertEquals(BigInteger.valueOf(123_456L), pago);
		assertTrue(PagamentosDeBloco.pagamentoAoBuilder(bloco(txs), 0, Set.of(BUILDER)).isEmpty());
	}

	@Test
	void blocoSemTransacoesNaoQuebra() {
		JsonObject b = JsonParser.parseString("{\"number\":\"0x1\",\"miner\":\"0x00\"}").getAsJsonObject();

		assertTrue(PagamentosDeBloco.interpretar(b, Set.of(BUILDER)).isEmpty());
		assertTrue(PagamentosDeBloco.pagamentoAoBuilder(b, 0, Set.of(BUILDER)).isEmpty());
	}
}
