package br.com.node.transformacao.mempool;

import java.math.BigInteger;
import java.util.List;

public record ExemploDeMovimento(TransacaoAnunciada anunciada, List<byte[]> tokensMovidos, BigInteger entradaObservada,
		List<br.com.node.transformacao.nucleo.Perna> pernas) {

	public ExemploDeMovimento(TransacaoAnunciada anunciada, List<byte[]> tokensMovidos, BigInteger entradaObservada) {
		this(anunciada, tokensMovidos, entradaObservada, List.of());
	}

	public ExemploDeMovimento {

		if (anunciada == null) {
			throw new IllegalArgumentException("exemplo sem transação anunciada");
		}

		if (tokensMovidos == null || tokensMovidos.size() < 2) {
			throw new IllegalArgumentException("exemplo sem caminho: menos de dois tokens movidos");
		}

		if (entradaObservada == null || entradaObservada.signum() <= 0) {
			throw new IllegalArgumentException("exemplo sem montante de entrada");
		}

		tokensMovidos = List.copyOf(tokensMovidos);
		pernas = pernas == null ? List.of() : List.copyOf(pernas);
	}
}