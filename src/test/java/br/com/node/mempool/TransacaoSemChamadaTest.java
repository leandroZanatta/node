package br.com.node.mempool;
import br.com.node.transformacao.mempool.*;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.execucao.bundle.AssinadorDeTransacao;
import br.com.node.vo.Transactions;

class TransacaoSemChamadaTest {

	private static final BigInteger CHAVE = new BigInteger(
			"1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16);

	
	
	
	@Test
	void tipo1559DevolveDestinoValorEDados() {

		byte[] destino = HexFormat.of().parseHex("10ed43c718714eb63d5aa57b78b54704e256024e");
		byte[] dados = HexFormat.of().parseHex("38ed1739" + "11".repeat(32));
		byte[] crua = AssinadorDeTransacao.assinar(3, BigInteger.ONE, BigInteger.TEN, 250_000, destino,
				BigInteger.valueOf(777), dados, CHAVE);

		TransacaoPendente tx = TransacaoPendente.decodificar(crua).orElseThrow();

		org.junit.jupiter.api.Assertions.assertArrayEquals(destino, tx.destino());
		org.junit.jupiter.api.Assertions.assertArrayEquals(dados, tx.entrada());
		assertEquals(BigInteger.valueOf(777), tx.valor());
	}

	@Test
	void transferenciaDeBnbNaoDerrubaNadaNemPerdeOLote() {

		byte[] destino = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
		byte[] transferencia = AssinadorDeTransacao.assinar(0, BigInteger.ONE, BigInteger.TEN, 21_000, destino,
				BigInteger.ONE, new byte[0], CHAVE);
		byte[] aprovacao = AssinadorDeTransacao.assinar(1, BigInteger.ONE, BigInteger.TEN, 60_000, destino,
				BigInteger.ZERO, HexFormat.of().parseHex("095ea7b3" + "00".repeat(64)), CHAVE);

		ObservatorioMempool mempool = new ObservatorioMempool(new Catalogo(), pool -> Optional.empty());

		assertDoesNotThrow(() -> mempool.observar(null, new Transactions(List.of(transferencia, aprovacao))));

		
		mempool.aguardarLotes();

		assertEquals(1L, mempool.resumo().get("transacoes_sem_chamada"));
		assertEquals(0L, mempool.resumo().get("transacoes_ilegiveis"));
		assertTrue((Long) mempool.resumo().get("transacoes_decodificadas") >= 1L,
				"a aprovação do mesmo lote se perdeu");
	}
}