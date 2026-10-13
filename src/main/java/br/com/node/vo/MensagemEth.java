package br.com.node.vo;

public sealed interface MensagemEth extends MensagemRlpx permits Status, UpgradeStatus, NewBlockHashes, NewBlock,
		NewPooledTransactionHashes, Transactions, Correlacionada {

	int STATUS = 0x00;
	int NEW_BLOCK_HASHES = 0x01;
	int GET_BLOCK_HEADERS = 0x03;
	int BLOCK_HEADERS = 0x04;
	int NEW_BLOCK = 0x07;
	int UPGRADE_STATUS = 0x0b;
	int GET_RECEIPTS = 0x0f;
	int RECEIPTS = 0x10;
}
