package br.com.node.vo;

import java.util.List;

public record Receipts(long requisicaoId, List<byte[]> crusPorBloco) implements Correlacionada {

	public Receipts {
		crusPorBloco = List.copyOf(crusPorBloco);
	}

	@Override
	public int tipo() {
		return RECEIPTS;
	}
}