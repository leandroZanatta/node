package br.com.node.util;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

public final class NodeKey {

	private final BigInteger privada;
	private final byte[] id;

	private NodeKey(BigInteger privada, byte[] id) {
		this.privada = privada;
		this.id = id;
	}

	public static NodeKey de(BigInteger privada) {

		byte[] id = Secp256k1.paraId(Secp256k1.DOMINIO.getG().multiply(privada).normalize());

		return new NodeKey(privada, id);
	}

	public static NodeKey carregar(String nodeKey) {

		return de(new BigInteger(1, HexFormat.of().parseHex(nodeKey)));

	}

	public static NodeKey gerar() {

		ECKeyPairGenerator gerador = new ECKeyPairGenerator();

		gerador.init(new ECKeyGenerationParameters(Secp256k1.DOMINIO, new SecureRandom()));

		AsymmetricCipherKeyPair par = gerador.generateKeyPair();

		BigInteger privada = ((ECPrivateKeyParameters) par.getPrivate()).getD();

		byte[] id = Secp256k1.paraId(((ECPublicKeyParameters) par.getPublic()).getQ());

		return new NodeKey(privada, id);
	}

	public byte[] id() {
		return id.clone();
	}

	BigInteger privada() {
		return privada;
	}

	public byte[] acordar(byte[] idRemoto) {
		return Secp256k1.acordar(privada, idRemoto);
	}

	public String idHex() {
		return HexFormat.of().formatHex(id);
	}

	public byte[] assinar(byte[] hash) {
		return Secp256k1.assinar(hash, privada, id);
	}

}