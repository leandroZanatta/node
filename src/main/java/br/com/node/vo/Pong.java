package br.com.node.vo;

import java.util.Arrays;

public record Pong(Endpoint para, byte[] pingHash, long expiracao) implements MensagemDiscovery {

	@Override
	public int tipo() {
		return PONG;
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof Pong outro && para.equals(outro.para) && Arrays.equals(pingHash, outro.pingHash)
				&& expiracao == outro.expiracao;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(pingHash);
	}
}
