package br.com.node.transformacao.nucleo;

import java.util.Arrays;

public record Par(byte[] token0, byte[] token1) {

	public Par {

		if (Arrays.compareUnsigned(token0, token1) > 0) {
			byte[] troca = token0;
			token0 = token1;
			token1 = troca;
		}
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof Par outro && Arrays.equals(token0, outro.token0)
				&& Arrays.equals(token1, outro.token1);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(token0) * 31 + Arrays.hashCode(token1);
	}
}