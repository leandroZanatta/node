package br.com.node.vo;

public record GetBlockHeadersDeHash(long requisicaoId, byte[] hash, int quantos, int pulo, boolean reverso)
		implements Correlacionada {

	@Override
	public int tipo() {
		return GET_BLOCK_HEADERS;
	}
}