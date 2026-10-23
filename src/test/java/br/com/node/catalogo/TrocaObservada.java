package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.Optional;

import br.com.node.nucleo.Direcao;

public record TrocaObservada(long altura, String chamador, Direcao direcao, BigInteger entrada, BigInteger saida,
		Optional<Integer> exata, int teto) {
}