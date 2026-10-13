package br.com.node.vo;

import java.util.List;

public record GetPooledTransactions(long requisicaoId, List<byte[]> hashes) implements Correlacionada {

	public GetPooledTransactions {
		hashes = List.copyOf(hashes);
	}

	@Override
	public int tipo() {
		return 0x09;
	}
}
