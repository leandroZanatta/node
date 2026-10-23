package br.com.node.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.logging.Logger;

public final class Identidade {

	private static final Logger LOG = Logger.getLogger(Identidade.class.getName());

	private Identidade() {
	}

	public static NodeKey carregar() {

		Path arquivo = Path.of(Resources.texto("node.chave.arquivo"));

		if (Files.isReadable(arquivo)) {
			return NodeKey.carregar(ler(arquivo));
		}

		NodeKey nova = NodeKey.gerar();

		gravar(arquivo, nova);

		LOG.warning(() -> "identidade nova gerada em " + arquivo.toAbsolutePath()
				+ " — os peers que conheciam a anterior não nos reconhecem mais");

		return nova;
	}

	private static String ler(Path arquivo) {

		try {
			return Files.readString(arquivo, StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			throw new IllegalStateException("Falha lendo a identidade em " + arquivo + ": " + e.getMessage());
		}
	}

	private static void gravar(Path arquivo, NodeKey chave) {

		String hexadecimal = HexFormat.of().formatHex(paraBytes(chave));

		try {

			Files.writeString(arquivo, hexadecimal, StandardCharsets.UTF_8);
			Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));

		} catch (IOException e) {
			throw new IllegalStateException("Falha gravando a identidade em " + arquivo + ": " + e.getMessage());
		}
	}

	private static byte[] paraBytes(NodeKey chave) {

		byte[] completo = new byte[32];
		byte[] cru = chave.privada().toByteArray();

		int origem = Math.max(0, cru.length - 32);
		int destino = Math.max(0, 32 - cru.length);

		System.arraycopy(cru, origem, completo, destino, Math.min(cru.length, 32));

		return completo;
	}
}