package br.com.node.vo;

import java.util.Arrays;
import java.util.List;

public record NewBlock(Cabecalho cabecalho, List<byte[]> transacoes) implements MensagemEth {

	public NewBlock {
		transacoes = transacoes.stream().map(tx -> Arrays.copyOf(tx, tx.length)).toList();
	}

	@Override
	public int tipo() {
		return NEW_BLOCK;
	}
}