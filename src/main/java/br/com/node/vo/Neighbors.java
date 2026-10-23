package br.com.node.vo;

import java.util.List;

public record Neighbors(List<NoRemoto> nos, long expiracao) implements MensagemDiscovery {

	@Override
	public int tipo() {
		return NEIGHBORS;
	}
}