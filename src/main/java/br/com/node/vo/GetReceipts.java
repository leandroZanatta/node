package br.com.node.vo;

import java.util.List;

public record GetReceipts(long requisicaoId, List<byte[]> hashes) implements Correlacionada {

	public GetReceipts {
		hashes = List.copyOf(hashes);
	}

	@Override
	public int tipo() {
		return GET_RECEIPTS;
	}
}
