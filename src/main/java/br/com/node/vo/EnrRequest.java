package br.com.node.vo;

public record EnrRequest(long expiracao) implements MensagemDiscovery {

	@Override
	public int tipo() {
		return ENR_REQUEST;
	}
}