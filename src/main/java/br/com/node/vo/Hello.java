package br.com.node.vo;

import java.util.List;

// Primeira mensagem enquadrada dos dois lados, e a única que NÃO vai comprimida:
// é ela que acorda o snappy.
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
