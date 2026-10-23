package br.com.node.vo;

public record Ping(int versao, Endpoint de, Endpoint para, long expiracao) implements MensagemDiscovery {

	@Override
	public int tipo() {
		return PING;
	}
}