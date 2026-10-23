package br.com.node.vo;

public record GetBlockHeaders(long requisicaoId, long deAltura, int quantos, int pulo, boolean reverso)
		implements Correlacionada {

	@Override
	public int tipo() {
		return GET_BLOCK_HEADERS;
	}
}