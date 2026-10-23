package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import br.com.node.catalogo.Catalogo;
import br.com.node.mempool.ObservatorioMempool;
import br.com.node.nucleo.Arbitragem;

class GatilhosTest {

	
	
	@Test
	void etapaMaisAdiantadaVence() {
		assertEquals("nunca anunciado", Gatilhos.etapa(0));
		assertEquals("anunciado sem corpo", Gatilhos.etapa(ObservatorioMempool.ANUNCIADO));
		assertEquals("pulado por tamanho",
				Gatilhos.etapa(ObservatorioMempool.ANUNCIADO | ObservatorioMempool.PULADO_PEQUENO));
		assertEquals("corpo sem swap", Gatilhos.etapa(ObservatorioMempool.ANUNCIADO | ObservatorioMempool.CORPO));
		assertEquals("corpo sem swap", Gatilhos.etapa(ObservatorioMempool.CORPO));
		assertEquals("projetado",
				Gatilhos.etapa(ObservatorioMempool.CORPO | ObservatorioMempool.SWAP | ObservatorioMempool.PROJETADO));
	}

	
	
	@Test
	void blocoTrazAsTransacoesCompletas() {

		RpcDoEnsaio rpc = new RpcDoEnsaio();
		JsonObject bloco = rpc.bloco(rpc.cabeca() - 5).orElseThrow();

		assertTrue(bloco.getAsJsonArray("transactions").size() > 1);
		JsonObject tx = bloco.getAsJsonArray("transactions").get(0).getAsJsonObject();
		assertTrue(tx.has("hash") && tx.has("input"), "tx sem hash/input: veio só o hash?");
	}

	
	
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

	@Test
	void descricaoDaMargemRespeitaOSinal() {
		assertEquals(" (visto 40 ms antes do bloco)", Gatilhos.descricaoMargem(40L));
		assertEquals(" (visto 40 ms depois do bloco)", Gatilhos.descricaoMargem(-40L));
		assertEquals(" (visto no instante do bloco)", Gatilhos.descricaoMargem(0L));
		assertEquals(" (nunca visto)", Gatilhos.descricaoMargem(null));
	}

	@Test
	void ausenciaTemporariaDoCambioNaoInterrompeObservacaoDoBloco() {

		CustoDeGas custo = new CustoDeGas(null, new Catalogo()).comMercadoMedido(BigInteger.ONE);
		Gatilhos gatilhos = new Gatilhos(null, hash -> 0, hash -> java.util.OptionalLong.empty(),
				arbitragem -> custo.paraBnbSeDisponivel(arbitragem.token(), arbitragem.sobra()));
		byte[] usdt = java.util.HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");

		gatilhos.investigar(new Arbitragem(List.of(), usdt, BigInteger.TEN, 0, 0), 1, 0, -1);

		assertEquals(1L, gatilhos.resumo().get("sem_cotacao"));
		assertTrue(((java.util.Map<?, ?>) gatilhos.resumo().get("gatilho_por_etapa"))
				.containsKey("sem gatilho no bloco (fenda de bloco anterior)"));
		gatilhos.close();
	}
}