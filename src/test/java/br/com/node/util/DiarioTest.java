package br.com.node.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O diário existe para responder "o que aconteceu às 14:26?" dias depois.
 * Garante: ligar instala arquivo, log emitido cai no arquivo, console continua.
 */
class DiarioTest {

	@TempDir
	Path temporario;

	@Test
	void ligarInstalaArquivoEmantemConsole() throws IOException {
		System.setProperty("node.log.dir", temporario.toString());

		try {
			Diario.ligar();

			assertTrue(Diario.ligado(), "ligar marca ligado");
			assertTrue(Diario.temArquivo(), "root tem FileHandler depois de ligar");

			Logger aqui = Logger.getLogger(DiarioTest.class.getName());
			aqui.info("prova-do-diario");

			boolean consoleVivo = Stream.of(Logger.getLogger("").getHandlers())
					.anyMatch(h -> h instanceof java.util.logging.ConsoleHandler);

			assertTrue(consoleVivo, "console continua instalado ao lado do arquivo");

			fecharArquivos();

			boolean achou = false;

			try (Stream<Path> arquivos = Files.list(temporario)) {
				for (Path arquivo : (Iterable<Path>) arquivos::iterator) {
					String conteudo = Files.readString(arquivo);

					if (conteudo.contains("prova-do-diario")) {
						achou = true;
					}
				}
			}

			assertTrue(achou, "mensagem emitida depois de ligar está no arquivo de log");
		} finally {
			System.clearProperty("node.log.dir");
			Diario.ligar();
		}
	}

	private static void fecharArquivos() {
		for (var handler : Logger.getLogger("").getHandlers()) {
			if (handler instanceof java.util.logging.FileHandler arquivo) {
				arquivo.flush();
				arquivo.close();
			}
		}
	}
}
