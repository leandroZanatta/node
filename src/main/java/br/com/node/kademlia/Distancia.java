package br.com.node.kademlia;

import br.com.node.util.Keccak;

public final class Distancia {

	public static final int BITS = 256;

	private Distancia() {
	}

	public static byte[] chave(byte[] id) {
		return Keccak.hash(id);
	}

	public static int log(byte[] chaveA, byte[] chaveB) {

		for (int i = 0; i < chaveA.length; i++) {

			int diferenca = (chaveA[i] ^ chaveB[i]) & 0xff;

			if (diferenca != 0) {

				int zerosDoByte = Integer.numberOfLeadingZeros(diferenca) - 24;

				return (chaveA.length - i) * 8 - zerosDoByte;
			}
		}

		return 0;
	}

	public static int comparar(byte[] chaveAlvo, byte[] chaveA, byte[] chaveB) {

		for (int i = 0; i < chaveAlvo.length; i++) {

			int distanciaA = (chaveA[i] ^ chaveAlvo[i]) & 0xff;
			int distanciaB = (chaveB[i] ^ chaveAlvo[i]) & 0xff;

			if (distanciaA != distanciaB) {
				return Integer.compare(distanciaA, distanciaB);
			}
		}

		return 0;
	}
}