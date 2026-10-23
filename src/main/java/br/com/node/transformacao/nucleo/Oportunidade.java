package br.com.node.transformacao.nucleo;

import java.math.BigInteger;

public record Oportunidade(BigInteger entrada, BigInteger saida) {

	public BigInteger lucro() {
		return saida.subtract(entrada);
	}
}