package br.com.node.vo;

import java.util.Arrays;
import java.util.HexFormat;

public record ForkId(byte[] hash, long proximo) {

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof ForkId outro && Arrays.equals(hash, outro.hash) && proximo == outro.proximo;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(hash);
	}

	@Override
	public String toString() {
		return "ForkId[0x" + HexFormat.of().formatHex(hash) + "→" + proximo + "]";
	}
}
