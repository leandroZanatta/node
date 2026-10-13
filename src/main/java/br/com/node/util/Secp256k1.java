package br.com.node.util;

import java.math.BigInteger;
import java.util.Arrays;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;

import br.com.node.exception.PeerException;

public final class Secp256k1 {

	public static final int TAMANHO_ASSINATURA = 65;
	public static final int TAMANHO_ID = 64;

	private static final X9ECParameters CURVA = CustomNamedCurves.getByName("secp256k1");

	static final ECDomainParameters DOMINIO = new ECDomainParameters(CURVA.getCurve(), CURVA.getG(), CURVA.getN(),
			CURVA.getH());

	private static final BigInteger METADE_N = DOMINIO.getN().shiftRight(1);

	private Secp256k1() {
	}

	public static byte[] assinar(byte[] hash, BigInteger privada, byte[] idEsperado) {

		ECDSASigner assinador = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));

		assinador.init(true, new ECPrivateKeyParameters(privada, DOMINIO));

		BigInteger[] componentes = assinador.generateSignature(hash);

		BigInteger r = componentes[0];
		BigInteger s = componentes[1];

		if (s.compareTo(METADE_N) > 0) {
			s = DOMINIO.getN().subtract(s);
		}

		int recuperacao = encontrarRecuperacao(r, s, hash, idEsperado);

		byte[] assinatura = new byte[TAMANHO_ASSINATURA];

		copiarAlinhado(r, assinatura, 0);
		copiarAlinhado(s, assinatura, 32);

		assinatura[64] = (byte) recuperacao;

		return assinatura;
	}

	public static byte[] recuperarId(byte[] hash, byte[] assinatura) {

		if (assinatura.length != TAMANHO_ASSINATURA) {
			throw new PeerException("Assinatura com " + assinatura.length + " bytes, esperado " + TAMANHO_ASSINATURA);
		}

		BigInteger r = new BigInteger(1, Arrays.copyOfRange(assinatura, 0, 32));
		BigInteger s = new BigInteger(1, Arrays.copyOfRange(assinatura, 32, 64));

		int recuperacao = assinatura[64] & 0xff;

		if (recuperacao > 3) {
			throw new PeerException("Recovery id inválido: " + recuperacao);
		}

		ECPoint ponto = recuperar(recuperacao, r, s, hash);

		if (ponto == null) {
			throw new PeerException("Não foi possível recuperar a chave pública da assinatura");
		}

		return paraId(ponto);
	}

	public static byte[] acordar(BigInteger privada, byte[] idRemoto) {

		ECPoint compartilhado = paraPonto(idRemoto).multiply(privada).normalize();

		if (compartilhado.isInfinity()) {
			throw new PeerException("ECDH degenerou no ponto no infinito");
		}

		byte[] alinhado = new byte[32];

		copiarAlinhado(compartilhado.getAffineXCoord().toBigInteger(), alinhado, 0);

		return alinhado;
	}

	public static byte[] paraId(ECPoint ponto) {

		byte[] codificado = ponto.getEncoded(false);

		return Arrays.copyOfRange(codificado, 1, codificado.length);
	}

	// A chave pública (64 bytes, X||Y) de uma chave privada: G·privada. É o que dá o endereço
	// (keccak dela, últimos 20 bytes) — usado para conferir de quem é a chave sem expô-la.
	public static byte[] id(BigInteger privada) {
		return paraId(DOMINIO.getG().multiply(privada).normalize());
	}

	static ECPoint paraPonto(byte[] id) {

		byte[] codificado = new byte[1 + TAMANHO_ID];

		codificado[0] = 0x04;

		System.arraycopy(id, 0, codificado, 1, TAMANHO_ID);

		return DOMINIO.getCurve().decodePoint(codificado);
	}

	private static int encontrarRecuperacao(BigInteger r, BigInteger s, byte[] hash, byte[] idEsperado) {

		for (int recuperacao = 0; recuperacao < 4; recuperacao++) {

			ECPoint ponto = recuperar(recuperacao, r, s, hash);

			if (ponto != null && Arrays.equals(paraId(ponto), idEsperado)) {
				return recuperacao;
			}
		}

		throw new PeerException("Nenhum recovery id reproduz a chave pública");
	}

	private static ECPoint recuperar(int recuperacao, BigInteger r, BigInteger s, byte[] hash) {

		BigInteger n = DOMINIO.getN();

		BigInteger x = r.add(BigInteger.valueOf(recuperacao / 2L).multiply(n));

		BigInteger primo = DOMINIO.getCurve().getField().getCharacteristic();

		if (x.compareTo(primo) >= 0) {
			return null;
		}

		ECPoint ponto = descomprimir(x, (recuperacao & 1) == 1);

		if (ponto == null || !ponto.multiply(n).isInfinity()) {
			return null;
		}

		BigInteger e = new BigInteger(1, hash);

		BigInteger eInverso = BigInteger.ZERO.subtract(e).mod(n);
		BigInteger rInverso = r.modInverse(n);

		BigInteger srInverso = rInverso.multiply(s).mod(n);
		BigInteger eInversorInverso = rInverso.multiply(eInverso).mod(n);

		return ECAlgorithms.sumOfTwoMultiplies(DOMINIO.getG(), eInversorInverso, ponto, srInverso);
	}

	private static ECPoint descomprimir(BigInteger x, boolean yImpar) {

		X9IntegerConverter conversor = new X9IntegerConverter();

		byte[] comprimido = conversor.integerToBytes(x, 1 + conversor.getByteLength(DOMINIO.getCurve()));

		comprimido[0] = (byte) (yImpar ? 0x03 : 0x02);

		try {
			return DOMINIO.getCurve().decodePoint(comprimido);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void copiarAlinhado(BigInteger valor, byte[] destino, int offset) {

		byte[] bytes = valor.toByteArray();

		int inicio = bytes.length > 32 ? bytes.length - 32 : 0;
		int tamanho = Math.min(32, bytes.length);

		System.arraycopy(bytes, inicio, destino, offset + 32 - tamanho, tamanho);
	}
}
