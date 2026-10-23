package br.com.node.vo;

public record UpgradeStatus(boolean desligarBroadcastDeTx) implements MensagemEth {

	@Override
	public int tipo() {
		return UPGRADE_STATUS;
	}
}