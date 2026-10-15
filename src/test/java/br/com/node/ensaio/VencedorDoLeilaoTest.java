package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class VencedorDoLeilaoTest {

	private static final String BUILDER = "4848489f0b2bedd788c696e2d79b6b69d7484848";
	private static final String VENCEDOR = "aa00000000000000000000000000000000000001";

	private static JsonObject bloco() {
		return JsonParser.parseString("{\"number\":\"0x7c5a3f0\",\"miner\":\"0x" + BUILDER + "\",\"transactions\":["
				+ "{\"hash\":\"0x01\",\"from\":\"0x" + VENCEDOR + "\",\"to\":\"0xdd00000000000000000000000000000000000004\",\"value\":\"0x0\",\"gasPrice\":\"0x2faf080\",\"transactionIndex\":\"0x0\"},"
				+ "{\"hash\":\"0x02\",\"from\":\"0x" + VENCEDOR + "\",\"to\":\"0x" + BUILDER + "\",\"value\":\"0x1\",\"gasPrice\":\"0x2faf080\",\"transactionIndex\":\"0x1\"}"
				+ "]}").getAsJsonObject();
	}

	@Test
	void extraiTransacaoVencedoraEPagamentosDoBloco() {
		JsonObject v = VencedorDoLeilao.extrair("0xnossa", "rival_n1", 101, 0, bloco(), Set.of(BUILDER));
		assertEquals("0x01", v.get("hash_vencedor").getAsString());
		assertEquals(VENCEDOR, v.get("de").getAsString());
		assertEquals("0", v.get("valor_wei").getAsString());
		assertFalse(v.get("direto_a_builder").getAsBoolean());
		assertEquals(BUILDER, v.get("minerador").getAsString());
		assertEquals(1, v.getAsJsonArray("pagamentos_a_builder_no_bloco").size());
	}

	@Test
	void posicaoForaDoBlocoEMarcadaComoErro() {
		JsonObject v = VencedorDoLeilao.extrair("0xnossa", "rival_n1", 101, 9, bloco(), Set.of(BUILDER));
		assertTrue(v.has("erro"));
		assertFalse(v.has("hash_vencedor"));
	}
}
