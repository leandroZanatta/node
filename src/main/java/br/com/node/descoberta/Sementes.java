package br.com.node.descoberta;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.node.exception.PeerException;
import br.com.node.vo.NoRemoto;

public final class Sementes {

	private static final String ARQUIVO = "/peers.json";

	private static final Pattern ENODE = Pattern.compile("enode://([0-9a-fA-F]{128})@([0-9.]+):(\\d+)");

	private Sementes() {
	}

	public static List<NoRemoto> carregar() {

		Matcher enodes = ENODE.matcher(texto());

		List<NoRemoto> nos = new ArrayList<>();

		while (enodes.find()) {

			int porta = Integer.parseInt(enodes.group(3));

			nos.add(new NoRemoto(HexFormat.of().parseHex(enodes.group(1)), endereco(enodes.group(2)), porta, porta));
		}

		if (nos.isEmpty()) {
			throw new PeerException("Nenhum enode em " + ARQUIVO + " — sem semente não há por onde começar");
		}

		return List.copyOf(nos);
	}

	private static String texto() {

		try (InputStream entrada = Sementes.class.getResourceAsStream(ARQUIVO)) {

			if (entrada == null) {
				throw new PeerException("Arquivo de sementes ausente: " + ARQUIVO);
			}

			return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);

		} catch (IOException e) {
			throw new PeerException("Falha lendo " + ARQUIVO + ": " + e.getMessage());
		}
	}

	private static InetAddress endereco(String ip) {

		try {
			return InetAddress.getByName(ip);
		} catch (UnknownHostException e) {
			throw new PeerException("IP inválido na semente: " + ip);
		}
	}
}
