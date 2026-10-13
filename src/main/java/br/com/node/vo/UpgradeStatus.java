package br.com.node.vo;

// Mensagem própria da BSC, trocada logo depois do Status no eth/68. O eth/70 NÃO
// a troca — o fonte da BSC diz isso em uma linha, e mandá-la ali é quebra de
// protocolo. Não responder no eth/68 é ser derrubado por erro de subprotocolo.
public record UpgradeStatus(boolean desligarBroadcastDeTx) implements MensagemEth {

	@Override
	public int tipo() {
		return UPGRADE_STATUS;
	}
}
