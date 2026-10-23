package br.com.node.vo;

import java.util.Arrays;

public record FindNode(byte[] alvo, long expiracao) implements MensagemDiscovery {

	@Override
	public int tipo() {
		return FIND_NODE;
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof FindNode outro && Arrays.equals(alvo, outro.alvo) && expiracao == outro.expiracao;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(alvo);
	}
}