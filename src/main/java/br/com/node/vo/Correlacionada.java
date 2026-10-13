package br.com.node.vo;

// Mensagem eth que carrega um id de requisição.
//
// O eth/66 pôs o id em todo par pedido/resposta justamente para que a resposta não
// precise ser "a próxima mensagem que se pareça com o que eu quero". `Status`,
// `UpgradeStatus` e `NewBlockHashes` não são pedido nem resposta e ficam de fora — é o
// tipo dizendo quem pode ser correlacionado, em vez de um método que devolve zero.
public sealed interface Correlacionada extends MensagemEth
		permits GetBlockHeaders, GetBlockHeadersDeHash, BlockHeaders, GetReceipts, Receipts,
		GetPooledTransactions, PooledTransactions {

	long requisicaoId();
}
