package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.Optional;

import br.com.node.nucleo.Direcao;

// Uma troca da pool sob suspeita, com o que o invariante permite concluir dela.
//
// `teto` é o maior degrau de taxa, em milionésimos, cuja saída máxima ainda cobre a saída que
// o par aceitou. Não é a taxa: é o limite de cima dela para ESTE chamador — o chamador pode ter
// pedido menos, e pedir menos só sobe o teto. -100 quer dizer que nem a taxa zero cobre a
// saída, e aí quem está errado é o estado anterior, não a taxa.
public record TrocaObservada(long altura, String chamador, Direcao direcao, BigInteger entrada, BigInteger saida,
		Optional<Integer> exata, int teto) {
}
