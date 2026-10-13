package br.com.node.vo;

import java.math.BigInteger;
import java.util.HexFormat;

// O que o par confere antes de aceitar conversar. Genesis ou fork id divergente
// não produz mensagem de erro do outro lado: produz silêncio, e a sessão cai
// cinco perguntas depois por surdez.
public record Status(int versao, long rede, BigInteger dificuldadeTotal, byte[] melhorBloco, byte[] genesis,
		ForkId fork) implements MensagemEth {

	@Override
	public int tipo() {
		return STATUS;
	}
}
