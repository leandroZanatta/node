package br.com.node.vo;

import java.util.Arrays;
import java.util.List;

public record Transactions(List<byte[]> transacoes) implements MensagemEth {

	public Transactions {
		transacoes = transacoes.stream().map(tx -> Arrays.copyOf(tx, tx.length)).toList();
	}

	@Override
	public int tipo() {
		return 0x02;
	}
}