package br.com.node.vo;

public record PongP2p() implements MensagemP2p {

	@Override
	public int tipo() {
		return PONG;
	}
}