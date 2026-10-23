package br.com.node.vo;

import java.util.Arrays;

public record AnuncioBloco(byte[] hash, long numero) {

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof AnuncioBloco outro && numero == outro.numero && Arrays.equals(hash, outro.hash);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(hash);
	}
}