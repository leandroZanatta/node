package br.com.node;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArquiteturaTest {

	private static final Path FONTES = Path.of("src/main/java/br/com/node");
	private static final Path CONTRATO = Path.of("dependencias.txt");

	private static final String RAIZ = "raiz";

	private static final Pattern IMPORTE = Pattern.compile("^import\\s+br\\.com\\.node\\.([a-z][A-Za-z0-9_]*)\\.");

	@Test
	@DisplayName("nenhum pacote importa quem não foi declarado em dependencias.txt")
	void importaSomenteODeclarado() throws IOException {

		Map<String, Set<String>> permitido = contrato();
		List<String> violacoes = new ArrayList<>();

		for (Path fonte : fontes()) {

			String de = pacoteDe(fonte);

			for (String para : importes(fonte)) {

				if (para.equals(de) || permitido.getOrDefault(de, Set.of()).contains(para)) {
					continue;
				}

				violacoes.add(de + " -> " + para + "   (" + fonte + ")");
			}
		}

		assertTrue(violacoes.isEmpty(), () -> "dependência não declarada — acrescente a aresta em " + CONTRATO
				+ " se ela for intencional:\n  " + String.join("\n  ", violacoes));
	}

	@Test
	@DisplayName("o grafo declarado é acíclico")
	void grafoSemCiclo() throws IOException {

		Map<String, Set<String>> permitido = contrato();
		List<String> ciclos = new ArrayList<>();

		for (String pacote : permitido.keySet()) {
			procurarCiclo(pacote, permitido, new LinkedHashSet<>(), ciclos);
		}

		assertTrue(ciclos.isEmpty(), () -> "ciclo entre pacotes:\n  " + String.join("\n  ", ciclos));
	}

	private static void procurarCiclo(String atual, Map<String, Set<String>> grafo, Set<String> caminho,
			List<String> achados) {

		if (!caminho.add(atual)) {
			achados.add(String.join(" -> ", caminho) + " -> " + atual);
			return;
		}

		for (String vizinho : grafo.getOrDefault(atual, Set.of())) {
			procurarCiclo(vizinho, grafo, new LinkedHashSet<>(caminho), achados);
		}
	}

	private static Map<String, Set<String>> contrato() throws IOException {

		Map<String, Set<String>> permitido = new LinkedHashMap<>();

		for (String linha : Files.readAllLines(CONTRATO)) {

			String limpa = linha.strip();

			if (limpa.isEmpty() || limpa.startsWith("#")) {
				continue;
			}

			String[] partes = limpa.split(":", 2);
			Set<String> destinos = new HashSet<>();

			for (String destino : partes[1].split(",")) {

				String nome = destino.strip();

				if (!nome.isEmpty()) {
					destinos.add(nome);
				}
			}

			permitido.put(partes[0].strip(), destinos);
		}

		return permitido;
	}

	private static List<Path> fontes() throws IOException {

		try (Stream<Path> arquivos = Files.walk(FONTES)) {
			return arquivos.filter(caminho -> caminho.toString().endsWith(".java")).toList();
		}
	}

	private static String pacoteDe(Path fonte) {

		Path relativo = FONTES.relativize(fonte);

		return relativo.getNameCount() == 1 ? RAIZ : relativo.getName(0).toString();
	}

	private static Set<String> importes(Path fonte) throws IOException {

		Set<String> pacotes = new LinkedHashSet<>();

		for (String linha : Files.readAllLines(fonte)) {

			Matcher importe = IMPORTE.matcher(linha);

			if (importe.find()) {
				pacotes.add(importe.group(1));
			}
		}

		return pacotes;
	}
}
