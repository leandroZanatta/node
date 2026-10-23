package br.com.node.vo;

public record PingP2p() implements MensagemP2p {

	@Override
	public int tipo() {
		return PING;
	}
}