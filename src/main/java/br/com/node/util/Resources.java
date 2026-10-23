package br.com.node.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

public final class Resources {

	private static final Logger LOG = Logger.getLogger(Resources.class.getName());

	private static final String ARQUIVO = "node.properties";

	private static final Properties PROPRIEDADES = carregar();

	private Resources() {
	}

	public static String texto(String chave) {

		String valor = resolver(chave);

		if (valor == null || valor.isBlank()) {
			throw new IllegalStateException("Configuração ausente: " + chave + " — defina " + chaveDeAmbiente(chave)
					+ " no ambiente, " + "-D" + chave + " na linha de comando, ou a chave em " + ARQUIVO);
		}

		return valor.trim();
	}

	public static int inteiro(String chave) {

		String valor = texto(chave);

		try {
			return Integer.parseInt(valor);
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Configuração inválida: " + chave + "=" + valor + " não é inteiro");
		}
	}

	private static String resolver(String chave) {

		String doAmbiente = System.getenv(chaveDeAmbiente(chave));

		if (doAmbiente != null && !doAmbiente.isBlank()) {
			return doAmbiente;
		}

		String daLinhaDeComando = System.getProperty(chave);

		if (daLinhaDeComando != null && !daLinhaDeComando.isBlank()) {
			return daLinhaDeComando;
		}

		return PROPRIEDADES.getProperty(chave);
	}

	private static String chaveDeAmbiente(String chave) {
		return chave.toUpperCase().replace('.', '_');
	}

	private static Properties carregar() {

		Properties propriedades = new Properties();

		try (InputStream embutido = Resources.class.getResourceAsStream("/" + ARQUIVO)) {

			if (embutido != null) {
				propriedades.load(embutido);
			}

		} catch (IOException e) {
			throw new IllegalStateException("Falha lendo " + ARQUIVO + " do classpath: " + e.getMessage());
		}

		Path externo = Path.of(ARQUIVO);

		if (Files.isReadable(externo)) {

			try (InputStream entrada = Files.newInputStream(externo)) {

				propriedades.load(entrada);

				LOG.info(() -> "configuração sobrescrita por " + externo.toAbsolutePath());

			} catch (IOException e) {
				throw new IllegalStateException("Falha lendo " + externo.toAbsolutePath() + ": " + e.getMessage());
			}
		}

		return propriedades;
	}
}