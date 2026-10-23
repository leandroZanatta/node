package br.com.node;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.logging.Logger;

import br.com.node.util.Keccak;
import br.com.node.util.Keystore;
import br.com.node.util.Secp256k1;

public final class CriarKeystore {

	private static final Logger LOG = Logger.getLogger(CriarKeystore.class.getName());

	public static void main(String[] argumentos) throws Exception {

		Path entrada = Path.of(argumentos.length > 0 ? argumentos[0] : "../node-java-old/operador.chave");
		Path saida = Path.of(argumentos.length > 1 ? argumentos[1] : "operador.keystore");

		String senha = System.getenv("OPERADOR_SENHA");

		if (senha == null || senha.isBlank()) {
			LOG.severe("Defina OPERADOR_SENHA no ambiente (a senha do keystore). Nada gravado.");
			return;
		}

		String hex = Files.readString(entrada).trim();
		if (hex.startsWith("0x")) {
			hex = hex.substring(2);
		}

		byte[] chave = HexFormat.of().parseHex(hex);
		byte[] endereco = Arrays.copyOfRange(Keccak.hash(Secp256k1.id(new BigInteger(1, chave))), 12, 32);

		Files.writeString(saida, Keystore.cifrar(chave, senha.toCharArray()));
		Arrays.fill(chave, (byte) 0);

		LOG.info("keystore gravado: " + saida.toAbsolutePath());
		LOG.info("endereço da chave: 0x" + HexFormat.of().formatHex(endereco));
		LOG.info("confira que é 0xe5c933480364616cbc0319a5a5841bc3d0e3eb94 (operador autorizado)");
		LOG.info("agora apague o operador.chave em texto puro; o node lê só o keystore + OPERADOR_SENHA");
	}
}