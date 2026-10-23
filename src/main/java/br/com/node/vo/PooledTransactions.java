package br.com.node.vo;

import java.util.Arrays;
import java.util.List;

public record PooledTransactions(long requisicaoId, List<byte[]> transacoes) implements Correlacionada {

	public PooledTransactions {
		transacoes = transacoes.stream().map(tx -> Arrays.copyOf(tx, tx.length)).toList();
	}

	@Override
	public int tipo() {
		return 0x0a;
	}
}