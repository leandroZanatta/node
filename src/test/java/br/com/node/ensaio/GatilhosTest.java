package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import br.com.node.mempool.ObservatorioMempool;

class GatilhosTest {

	// A etapa é a MAIS ADIANTADA que o hash alcançou: projetado vence swap, que vence corpo.
	@Test
	void etapaMaisAdiantadaVence() {
		assertEquals("nunca anunciado", Gatilhos.etapa(0));
		assertEquals("anunciado sem corpo", Gatilhos.etapa(ObservatorioMempool.ANUNCIADO));
		assertEquals("pulado por tamanho", Gatilhos.etapa(ObservatorioMempool.ANUNCIADO | ObservatorioMempool.PULADO_PEQUENO));
		assertEquals("corpo sem swap", Gatilhos.etapa(ObservatorioMempool.ANUNCIADO | ObservatorioMempool.CORPO));
		assertEquals("corpo sem swap", Gatilhos.etapa(ObservatorioMempool.CORPO));
		assertEquals("projetado", Gatilhos.etapa(ObservatorioMempool.CORPO | ObservatorioMempool.SWAP
				| ObservatorioMempool.PROJETADO));
	}

	// Contra a BSC: o bloco vem com as txs completas — é daí que sai o hash do gatilho pela posição.
	@Test
	void blocoTrazAsTransacoesCompletas() {

		RpcDoEnsaio rpc = new RpcDoEnsaio();
		JsonObject bloco = rpc.bloco(rpc.cabeca() - 5).orElseThrow();

		assertTrue(bloco.getAsJsonArray("transactions").size() > 1);
		JsonObject tx = bloco.getAsJsonArray("transactions").get(0).getAsJsonObject();
		assertTrue(tx.has("hash") && tx.has("input"), "tx sem hash/input: veio só o hash?");
	}

	// O instante do bloco na BSC: segundos do `timestamp` + o milissegundo guardado no `mixHash` (0–999).
	@Test
	void instanteDoBlocoLeOMilissegundoDoMixHash() {
		JsonObject bloco = new JsonObject();
		bloco.addProperty("timestamp", "0x" + Long.toHexString(1_790_430_737L));
		bloco.addProperty("mixHash", "0x" + "0".repeat(61) + "15e");
		assertEquals(1_790_430_737_350L, Gatilhos.instanteMs(bloco));

		bloco.addProperty("mixHash", "0x" + "f".repeat(64));
		assertEquals(1_790_430_737_000L, Gatilhos.instanteMs(bloco), "mixHash fora de 0–999 não é milissegundo");
		assertEquals(0, Gatilhos.instanteMs(new JsonObject()));
	}
}
