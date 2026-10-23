package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import java.math.BigInteger;
import java.util.Optional;

import br.com.node.transformacao.nucleo.Direcao;

public record TrocaObservada(long altura, String chamador, Direcao direcao, BigInteger entrada, BigInteger saida,
		Optional<Integer> exata, int teto) {
}