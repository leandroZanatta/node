package br.com.node.mempool;

import java.math.BigInteger;
import java.util.List;

// UM exemplo rotulado: a transação que vimos anunciada (X) e o que o bloco provou que ela moveu (Y).
//
// `tokensMovidos` são os tokens das POOLS que ela mexeu, colhidos do catálogo pelo endereço da pool — e
// não do que a calldata diz. É essa diferença que faz o exemplo valer: o rótulo vem da cadeia, então
// aprender com ele não é aprender a nossa própria suposição de volta.
//
// `pernas` é o que a cadeia fez EM ORDEM: pool, token que entrou, token que saiu, uma por troca. É o que
// deixa aprender DIREÇÃO e POOL, e não só "quais tokens": o conjunto de tokens não diz qual entrou, e o
// projetor que chuta a direção projeta o swap ao contrário. Vazio nos exemplos antigos (só conjunto).
public record ExemploDeMovimento(TransacaoAnunciada anunciada, List<byte[]> tokensMovidos,
		BigInteger entradaObservada, List<br.com.node.nucleo.Perna> pernas) {

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
