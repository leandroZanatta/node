package br.com.node.vo;

import java.math.BigInteger;
import java.util.List;

public record SwapPendente(BigInteger entra, List<byte[]> caminho, List<byte[]> pools) {

	public SwapPendente {
		caminho = List.copyOf(caminho);
		pools = pools == null ? List.of() : List.copyOf(pools);
	}

	public SwapPendente(BigInteger entra, List<byte[]> caminho) {
		this(entra, caminho, List.of());
	}
}