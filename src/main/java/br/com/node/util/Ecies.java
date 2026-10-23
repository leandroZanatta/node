package br.com.node.util;

import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import br.com.node.exception.PeerException;

public final class Ecies {

	private static final int TAMANHO_PONTO = 65;
	private static final int TAMANHO_IV = 16;
	private static final int TAMANHO_MAC = 32;
	private static final int TAMANHO_CHAVE = 16;

	public static final int SOBRECARGA = TAMANHO_PONTO + TAMANHO_IV + TAMANHO_MAC;

	private static final SecureRandom ALEATORIO = new SecureRandom();

	private Ecies() {
	}

	public static byte[] cifrar(byte[] idRemoto, byte[] mensagem, byte[] dadosMac) {

		NodeKey efemera = NodeKey.gerar();

		byte[] z = efemera.acordar(idRemoto);

		byte[] k = concatKdf(z);

		byte[] iv = new byte[TAMANHO_IV];

		ALEATORIO.nextBytes(iv);

		byte[] cifrado = aesCtr(true, chaveAes(k), iv, mensagem);

		byte[] ponto = Secp256k1.paraPonto(efemera.id()).getEncoded(false);

		byte[] mac = hmac(chaveMac(k), iv, cifrado, dadosMac);

		byte[] pacote = new byte[ponto.length + iv.length + cifrado.length + mac.length];

		int pos = 0;

		pos = copiar(ponto, pacote, pos);
		pos = copiar(iv, pacote, pos);
		pos = copiar(cifrado, pacote, pos);

		copiar(mac, pacote, pos);

		return pacote;
	}

	public static byte[] decifrar(NodeKey chave, byte[] pacote, byte[] dadosMac) {

		if (pacote.length < SOBRECARGA) {
			throw new PeerException("Pacote ECIES com " + pacote.length + " bytes, mínimo " + SOBRECARGA);
		}

		byte[] ponto = Arrays.copyOfRange(pacote, 0, TAMANHO_PONTO);

		if (ponto[0] != 0x04) {
			throw new PeerException("Ponto efêmero não está na forma não-comprimida");
		}

		byte[] iv = Arrays.copyOfRange(pacote, TAMANHO_PONTO, TAMANHO_PONTO + TAMANHO_IV);
		byte[] cifrado = Arrays.copyOfRange(pacote, TAMANHO_PONTO + TAMANHO_IV, pacote.length - TAMANHO_MAC);
		byte[] mac = Arrays.copyOfRange(pacote, pacote.length - TAMANHO_MAC, pacote.length);

		byte[] z = chave.acordar(Arrays.copyOfRange(ponto, 1, TAMANHO_PONTO));

		byte[] k = concatKdf(z);

		byte[] esperado = hmac(chaveMac(k), iv, cifrado, dadosMac);

		if (!java.security.MessageDigest.isEqual(mac, esperado)) {
			throw new PeerException("MAC do ECIES não confere");
		}

		return aesCtr(false, chaveAes(k), iv, cifrado);
	}

	private static byte[] concatKdf(byte[] z) {

		SHA256Digest digest = new SHA256Digest();

		byte[] saida = new byte[2 * TAMANHO_CHAVE];
		byte[] bloco = new byte[digest.getDigestSize()];

		int escrito = 0;

		for (int contador = 1; escrito < saida.length; contador++) {

			digest.update((byte) (contador >>> 24));
			digest.update((byte) (contador >>> 16));
			digest.update((byte) (contador >>> 8));
			digest.update((byte) contador);

			digest.update(z, 0, z.length);
			digest.doFinal(bloco, 0);

			int quantos = Math.min(bloco.length, saida.length - escrito);

			System.arraycopy(bloco, 0, saida, escrito, quantos);

			escrito += quantos;
		}

		return saida;
	}

	private static byte[] chaveAes(byte[] k) {
		return Arrays.copyOfRange(k, 0, TAMANHO_CHAVE);
	}

	private static byte[] chaveMac(byte[] k) {

		SHA256Digest digest = new SHA256Digest();

		byte[] saida = new byte[digest.getDigestSize()];

		digest.update(k, TAMANHO_CHAVE, TAMANHO_CHAVE);
		digest.doFinal(saida, 0);

		return saida;
	}

	private static byte[] aesCtr(boolean cifrando, byte[] chave, byte[] iv, byte[] dados) {

		SICBlockCipher cifra = new SICBlockCipher(new AESEngine());

		cifra.init(cifrando, new ParametersWithIV(new KeyParameter(chave), iv));

		byte[] saida = new byte[dados.length];

		cifra.processBytes(dados, 0, dados.length, saida, 0);

		return saida;
	}

	private static byte[] hmac(byte[] chave, byte[] iv, byte[] cifrado, byte[] dadosMac) {

		HMac hmac = new HMac(new SHA256Digest());

		hmac.init(new KeyParameter(chave));

		hmac.update(iv, 0, iv.length);
		hmac.update(cifrado, 0, cifrado.length);

		if (dadosMac != null) {
			hmac.update(dadosMac, 0, dadosMac.length);
		}

		byte[] saida = new byte[hmac.getMacSize()];

		hmac.doFinal(saida, 0);

		return saida;
	}

	private static int copiar(byte[] origem, byte[] destino, int pos) {

		System.arraycopy(origem, 0, destino, pos, origem.length);

		return pos + origem.length;
	}
}