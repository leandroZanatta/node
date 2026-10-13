package br.com.node.vo;

import java.util.List;

// Os recibos vêm crus de propósito: decodificar log é trabalho de quem constrói
// o catálogo, e ainda não existe quem leia. O que existe hoje é medir a volta.
public record Receipts(long requisicaoId, List<byte[]> crusPorBloco) implements Correlacionada {

	public Receipts {
		crusPorBloco = List.copyOf(crusPorBloco);
	}

	@Override
	public int tipo() {
		return RECEIPTS;
	}
}
