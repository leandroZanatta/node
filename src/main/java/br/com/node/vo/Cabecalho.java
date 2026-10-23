package br.com.node.vo;

import java.util.Arrays;
import java.util.HexFormat;

public record Cabecalho(long numero, byte[] hash, byte[] hashDoPai, long instanteMs) {

	public Cabecalho(long numero, byte[] hash, byte[] hashDoPai) {
		this(numero, hash, hashDoPai, 0);
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof Cabecalho outro && numero == outro.numero && Arrays.equals(hash, outro.hash)
				&& Arrays.equals(hashDoPai, outro.hashDoPai);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(hash);
	}

	@Override
	public String toString() {
		return "Cabecalho[" + numero + "@" + HexFormat.of().formatHex(hash).substring(0, 12) + "…]";
	}
}