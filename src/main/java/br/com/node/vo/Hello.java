package br.com.node.vo;

import java.util.List;

public record Hello(int versao, String cliente, List<Capacidade> capacidades, int porta, byte[] id)
		implements MensagemP2p {

	public Hello {
		capacidades = List.copyOf(capacidades);
	}

	@Override
	public int tipo() {
		return HELLO;
	}
}