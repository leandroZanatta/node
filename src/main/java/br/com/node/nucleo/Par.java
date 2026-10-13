package br.com.node.nucleo;

import java.util.Arrays;
import java.util.HexFormat;

// Os dois tokens de uma pool, por ENDEREÇO.
//
// Nunca por símbolo: o catálogo do projeto anterior tinha cerca de 180 tokens
// chamados "USDT", um real e o resto impostor — muitos com endereço terminando em
// 8888 ou 7777, imposto nulo e zero negociação. Um "USDT" falso pareado contra WBNB
// real produz arbitragem aparente enorme numa pool de pó, e é assim que se fabrica
// oportunidade que não existe.
//
// token0 é o menor endereço, que é como o par os ordena no construtor.
public record Par(byte[] token0, byte[] token1) {

	public Par {

		if (HexFormat.of().formatHex(token0).compareTo(HexFormat.of().formatHex(token1)) > 0) {
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
