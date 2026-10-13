package br.com.node.util;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.generators.SCrypt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// Guarda um segredo (a chave privada do operador) CIFRADO com senha, para ele não ficar em texto
// puro no disco. A senha nunca é gravada — entra em runtime (variável de ambiente) e some com o
// processo.
//
// scrypt (memória-dura, cara de forçar) deriva a chave de cifra da senha; AES-GCM cifra E autentica
// — senha errada falha na verificação do tag, não devolve lixo silencioso. Cada peça (sal, iv) é
// aleatória por keystore, então dois keystores da mesma chave+senha não se parecem.
public final class Keystore {

	private static final int N = 1 << 15;
	private static final int R = 8;
	private static final int P = 1;
	private static final int BYTES_CHAVE = 32;
	private static final int BYTES_SAL = 16;
	private static final int BYTES_IV = 12;
	private static final int BITS_TAG = 128;

	private static final SecureRandom ALEATORIO = new SecureRandom();

	private Keystore() {
	}

	public static String cifrar(byte[] segredo, char[] senha) {

		byte[] sal = aleatorio(BYTES_SAL);
		byte[] iv = aleatorio(BYTES_IV);
		byte[] chave = SCrypt.generate(bytes(senha), sal, N, R, P, BYTES_CHAVE);
		byte[] cifrado = gcm(Cipher.ENCRYPT_MODE, chave, iv, segredo);

		JsonObject json = new JsonObject();
		json.addProperty("n", N);
		json.addProperty("r", R);
		json.addProperty("p", P);
		json.addProperty("sal", hex(sal));
		json.addProperty("iv", hex(iv));
		json.addProperty("cifrado", hex(cifrado));

		return json.toString();
	}

	// Decifra, ou lança se a senha estiver errada (o tag do GCM não fecha) — nunca devolve chave
	// corrompida em silêncio.
	public static byte[] decifrar(String json, char[] senha) {

		JsonObject o = JsonParser.parseString(json).getAsJsonObject();

		byte[] sal = HexFormat.of().parseHex(o.get("sal").getAsString());
		byte[] iv = HexFormat.of().parseHex(o.get("iv").getAsString());
		byte[] cifrado = HexFormat.of().parseHex(o.get("cifrado").getAsString());
		byte[] chave = SCrypt.generate(bytes(senha), sal, o.get("n").getAsInt(), o.get("r").getAsInt(),
				o.get("p").getAsInt(), BYTES_CHAVE);

		return gcm(Cipher.DECRYPT_MODE, chave, iv, cifrado);
	}

	private static byte[] gcm(int modo, byte[] chave, byte[] iv, byte[] dados) {

		try {

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(modo, new SecretKeySpec(chave, "AES"), new GCMParameterSpec(BITS_TAG, iv));

			return cipher.doFinal(dados);

		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("keystore: senha errada ou dado corrompido (" + e.getMessage() + ")", e);
		}
	}

	private static byte[] aleatorio(int tamanho) {
		byte[] bytes = new byte[tamanho];
		ALEATORIO.nextBytes(bytes);
		return bytes;
	}

	private static byte[] bytes(char[] senha) {
		byte[] bytes = new byte[senha.length];
		for (int i = 0; i < senha.length; i++) {
			bytes[i] = (byte) senha[i];
		}
		return bytes;
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
