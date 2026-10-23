package br.com.node.transformacao.nucleo;

import java.math.BigInteger;
import java.util.List;

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