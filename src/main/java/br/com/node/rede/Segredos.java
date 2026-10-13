package br.com.node.rede;

import org.bouncycastle.crypto.digests.KeccakDigest;

import br.com.node.util.Keccak;

public final class Segredos {

	private final byte[] aes;
	private final byte[] mac;

	private final KeccakDigest egresso;
	private final KeccakDigest ingresso;

	Segredos(byte[] segredoEfemero, byte[] nonceLocal, byte[] nonceRemoto, boolean souIniciador,
			byte[] pacoteEnviado, byte[] pacoteRecebido) {

		byte[] nonceIniciador = souIniciador ? nonceLocal : nonceRemoto;
		byte[] nonceReceptor = souIniciador ? nonceRemoto : nonceLocal;

		byte[] compartilhado = Keccak.hash(segredoEfemero, Keccak.hash(nonceReceptor, nonceIniciador));

		this.aes = Keccak.hash(segredoEfemero, compartilhado);
		this.mac = Keccak.hash(segredoEfemero, aes);

		this.egresso = novoMac(nonceRemoto, pacoteEnviado);
		this.ingresso = novoMac(nonceLocal, pacoteRecebido);
	}

	private KeccakDigest novoMac(byte[] nonce, byte[] pacote) {

		KeccakDigest digest = new KeccakDigest(256);

		byte[] semente = new byte[Keccak.TAMANHO];

		for (int i = 0; i < semente.length; i++) {
			semente[i] = (byte) (mac[i] ^ nonce[i]);
		}

		digest.update(semente, 0, semente.length);
		digest.update(pacote, 0, pacote.length);

		return digest;
	}

	public byte[] aes() {
		return aes.clone();
	}

	public byte[] mac() {
		return mac.clone();
	}

	KeccakDigest egresso() {
		return egresso;
	}

	KeccakDigest ingresso() {
		return ingresso;
	}

	static byte[] resumo(KeccakDigest digest) {

		KeccakDigest copia = new KeccakDigest(digest);

		byte[] saida = new byte[copia.getDigestSize()];

		copia.doFinal(saida, 0);

		return saida;
	}
}
