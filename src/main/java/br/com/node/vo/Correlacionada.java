package br.com.node.vo;

public sealed interface Correlacionada extends MensagemEth permits GetBlockHeaders, GetBlockHeadersDeHash, BlockHeaders,
		GetReceipts, Receipts, GetPooledTransactions, PooledTransactions {

	long requisicaoId();
}