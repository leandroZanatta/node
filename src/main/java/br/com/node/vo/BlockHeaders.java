package br.com.node.vo;

import java.util.List;

public record BlockHeaders(long requisicaoId, List<Cabecalho> cabecalhos) implements Correlacionada {

	public BlockHeaders {
		cabecalhos = List.copyOf(cabecalhos);
	}

	@Override
	public int tipo() {
		return BLOCK_HEADERS;
	}
}