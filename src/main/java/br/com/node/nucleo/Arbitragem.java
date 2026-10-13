package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.List;

// Uma arbitragem observada: as pools por onde passou, o token em que sobrou e quanto.
//
// `pools` vem na ordem em que apareceram na transação, e o tamanho dela é o número de
// pernas — que no histórico do projeto anterior tinha média 2,93 e nunca passou de 3.
//
// `executor` é a conta que o detector reconheceu como quem lucrou (nulo quando não se sabe) — é por ela que a
// autópsia do mercado confere na cadeia se a sobra estimada pelos logs é o que ficou de fato.
public record Arbitragem(List<byte[]> pools, byte[] token, BigInteger sobra, int v2, int v3, byte[] executor) {

	public Arbitragem {
		pools = List.copyOf(pools);
	}

	public Arbitragem(List<byte[]> pools, byte[] token, BigInteger sobra, int v2, int v3) {
		this(pools, token, sobra, v2, v3, null);
	}

	public int pernas() {
		return pools.size();
	}
}
