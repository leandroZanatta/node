package br.com.node.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Liga o JUL para console + arquivo rotativo {@code logs/node-%g.log}.
 *
 * <p>
 * CICATRIZ: todo log deste projeto nasce de {@code Logger.getLogger(...)} e,
 * sem configuração, ia só para o console — o que se via na tela morria com o
 * processo. O {@code snapshot} do RelatorioExecucao ia para
 * {@code target/*.jsonl}, mas os LOG.info/warning/severe (quedas de peer,
 * recusas do contrato, nonces, erros de RPC) não ficavam em lugar nenhum.
 *
 * <p>
 * CICATRIZ: esta classe NÃO pode usar {@link Resources}. Ela roda no
 * inicializador estático do Main, ANTES de qualquer campo estático — e
 * {@code Resources.inteiro(...)} num campo estático do Main dispara o
 * carregamento de Resources, que loga. Se Diario dependesse de Resources,
 * haveria inicialização circular (JLS 12.4.1: a classe em inicialização é
 * observada pela metade). Por isso só {@code System.getProperty} + literais.
 *
 * <p>
 * Ordem de aplicação: {@code logging.properties} embutido no classpath, depois
 * {@code ./logging.properties} ao lado do binário (mesmo override externo que
 * Resources faz com node.properties), depois {@code -Dnode.log.dir} e
 * {@code -Dnode.log.nivel}. Idempotente: chamar duas vezes não duplica
 * handlers, só reaplica.
 */
public final class Diario {

	private static final String EMBUTIDO = "/logging.properties";

	private static final String EXTERNO = "logging.properties";

	private static volatile boolean ligado = false;

	private Diario() {
	}

	/**
	 * Instala console + arquivo no logger raiz. Chamar como PRIMEIRA coisa do
	 * processo (bloco estático do Main), antes de qualquer Logger do projeto
	 * emitir.
	 */
	public static synchronized void ligar() {
		Properties propriedades = new Properties();

		try (InputStream embutido = Diario.class.getResourceAsStream(EMBUTIDO)) {
			if (embutido != null) {
				propriedades.load(embutido);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Falha lendo " + EMBUTIDO + " do classpath: " + e.getMessage(), e);
		}

		Path externo = Path.of(EXTERNO);

		if (Files.isReadable(externo)) {
			try (InputStream entrada = Files.newInputStream(externo)) {
				propriedades.load(entrada);
			} catch (IOException e) {
				throw new IllegalStateException("Falha lendo " + externo.toAbsolutePath() + ": " + e.getMessage(),
						e);
			}
		}

		String diretorio = System.getProperty("node.log.dir", "logs").trim();

		if (!diretorio.isEmpty()) {
			propriedades.setProperty("java.util.logging.FileHandler.pattern",
					diretorio + "/node-%g.log");
		}

		String nivel = System.getProperty("node.log.nivel", "").trim();

		if (!nivel.isEmpty()) {
			try {
				Level.parse(nivel.toUpperCase());
				propriedades.setProperty(".level", nivel.toUpperCase());
				propriedades.setProperty("java.util.logging.ConsoleHandler.level", nivel.toUpperCase());
			} catch (IllegalArgumentException e) {
				Logger.getLogger(Diario.class.getName())
						.warning(() -> "node.log.nivel inválido: " + nivel + " — mantido o do arquivo");
			}
		}

		try {
			Files.createDirectories(Path.of(diretorio.isEmpty() ? "logs" : diretorio));
		} catch (IOException e) {
			throw new IllegalStateException("Não foi possível criar diretório de logs: " + e.getMessage(), e);
		}

		// Os HANDLERS são criados aqui, e não pelo LogManager. CICATRIZ de 24/09: com `handlers=...` na
		// configuração, o LogManager instancia as classes por REFLEXÃO — e no binário nativo o FileHandler
		// não estava registrado: "Can't load log handler java.util.logging.FileHandler", o console seguia
		// e o arquivo de log nunca nascia. Construídos à mão, não há reflexão, e JVM e nativo são iguais.
		// O LogManager recebe o resto (níveis por logger), sem `handlers` e sem formatter por nome.
		Properties paraOLogManager = new Properties();
		paraOLogManager.putAll(propriedades);
		paraOLogManager.remove("handlers");
		paraOLogManager.remove("java.util.logging.ConsoleHandler.formatter");
		paraOLogManager.remove("java.util.logging.FileHandler.formatter");

		try (var saida = new java.io.ByteArrayOutputStream()) {
			paraOLogManager.store(saida, null);

			try (var entrada = new java.io.ByteArrayInputStream(saida.toByteArray())) {
				LogManager.getLogManager().readConfiguration(entrada);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Falha aplicando configuração de log: " + e.getMessage(), e);
		}

		Logger raiz = Logger.getLogger("");

		for (Handler velho : raiz.getHandlers()) {
			raiz.removeHandler(velho);
			velho.close();
		}

		java.util.logging.ConsoleHandler console = new java.util.logging.ConsoleHandler();
		console.setLevel(nivelDe(propriedades, "java.util.logging.ConsoleHandler.level", Level.INFO));
		console.setFormatter(new java.util.logging.SimpleFormatter());
		raiz.addHandler(console);

		try {
			FileHandler arquivo = new FileHandler(
					propriedades.getProperty("java.util.logging.FileHandler.pattern", "logs/node-%g.log"),
					Integer.parseInt(propriedades.getProperty("java.util.logging.FileHandler.limit", "10485760").trim()),
					Integer.parseInt(propriedades.getProperty("java.util.logging.FileHandler.count", "5").trim()),
					Boolean.parseBoolean(propriedades.getProperty("java.util.logging.FileHandler.append", "true").trim()));
			arquivo.setLevel(nivelDe(propriedades, "java.util.logging.FileHandler.level", Level.ALL));
			arquivo.setFormatter(new java.util.logging.SimpleFormatter());
			raiz.addHandler(arquivo);
		} catch (IOException e) {
			throw new IllegalStateException("Não foi possível abrir o arquivo de log: " + e.getMessage(), e);
		}

		ligado = true;

		Logger.getLogger(Diario.class.getName())
				.info(() -> "diário ligado: console + " + propriedades
						.getProperty("java.util.logging.FileHandler.pattern"));
	}

	private static Level nivelDe(Properties propriedades, String chave, Level padrao) {
		try {
			return Level.parse(propriedades.getProperty(chave, padrao.getName()).trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return padrao;
		}
	}

	/** Verdade depois do primeiro {@link #ligar} bem-sucedido. */
	public static boolean ligado() {
		return ligado;
	}

	/** Onde o FileHandler desta configuração escreve (para diagnóstico/teste). */
	public static boolean temArquivo() {
		for (Handler handler : Logger.getLogger("").getHandlers()) {
			if (handler instanceof FileHandler) {
				return true;
			}
		}
		return false;
	}
}
