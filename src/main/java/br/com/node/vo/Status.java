package br.com.node.vo;

import java.math.BigInteger;

public record Status(int versao, long rede, BigInteger dificuldadeTotal, byte[] melhorBloco, byte[] genesis,
		ForkId fork) implements MensagemEth {

	@Override
	public int tipo() {
		return STATUS;
	}
}