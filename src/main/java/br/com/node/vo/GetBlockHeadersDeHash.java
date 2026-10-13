package br.com.node.vo;

// Mesmo código de fio do GetBlockHeaders, origem diferente: o devp2p aceita altura
// OU hash, e são dois pedidos distintos. Um record com campo nulo para escolher
// entre os dois seria a assinatura mentindo sobre o que ela faz.
//
// É por aqui que se pergunta a cabeça de um par: o Status dele traz o HASH dela, não
// a altura.
public record GetBlockHeadersDeHash(long requisicaoId, byte[] hash, int quantos, int pulo, boolean reverso)
		implements Correlacionada {

	@Override
	public int tipo() {
		return GET_BLOCK_HEADERS;
	}
}
