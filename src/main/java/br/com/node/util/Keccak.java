package br.com.node.util;

import org.bouncycastle.crypto.digests.KeccakDigest;

public final class Keccak {

	public static final int TAMANHO = 32;

	private Keccak() {
	}

	public static byte[] hash(byte[]... partes) {

		KeccakDigest digest = new KeccakDigest(256);

		for (byte[] parte : partes) {
			digest.update(parte, 0, parte.length);
		}

		byte[] saida = new byte[TAMANHO];

		digest.doFinal(saida, 0);

		return saida;
	}

	public static byte[] hash(byte[] dados, int offset, int tamanho) {

		KeccakDigest digest = new KeccakDigest(256);

		digest.update(dados, offset, tamanho);

		byte[] saida = new byte[TAMANHO];

		digest.doFinal(saida, 0);

		return saida;
	}
}
