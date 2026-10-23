package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuentesTest {

	@TempDir
	Path pasta;

	@Test
	@DisplayName("pool vira quente na 3ª arbitragem confirmada; o organizador promove direto; a fila entrega as novas uma vez")
	void promocao() {

		Quentes q = new Quentes(null);

		q.vistaEmArbitragem(List.of("aa", "bb"));
		q.vistaEmArbitragem(List.of("aa"));
		assertFalse(q.contem("aa"), "duas arbitragens ainda não esquentam");

		q.vistaEmArbitragem(List.of("aa"));
		assertTrue(q.contem("aa"), "na terceira, esquenta");

		assertEquals(1, q.promover(List.of("cc", "aa")), "o organizador promove só quem não era quente");
		assertTrue(q.contem("cc"));

		assertEquals(List.of("aa", "cc"), q.drenarNovas());
		assertEquals(List.of(), q.drenarNovas(), "a fila de novas se esvazia ao ser lida");
	}

	@Test
	@DisplayName("quentes.json volta igual no reinício, contagens incluídas")
	void persiste() {

		Path arquivo = pasta.resolve("quentes.json");
		Quentes q = new Quentes(arquivo);
		q.promover(List.of("aa"));
		q.vistaEmArbitragem(List.of("bb"));
		q.salvar();

		Quentes depois = new Quentes(arquivo);
		assertTrue(depois.contem("aa"));
		assertFalse(depois.contem("bb"));
		depois.vistaEmArbitragem(List.of("bb"));
		depois.vistaEmArbitragem(List.of("bb"));
		assertTrue(depois.contem("bb"), "a contagem de antes do reinício se perdeu");
	}

	@Test
	@DisplayName("quem passa 24 h sem aparecer esfria e perde a contagem")
	void esfria() {
		Quentes q = new Quentes(null);
		q.promover(List.of("aa"));
		q.esfriar(System.currentTimeMillis() + Quentes.ESQUECIMENTO_MS + 1);
		assertFalse(q.contem("aa"));
	}

	@Test
	@DisplayName("quentes.json ilegível estoura na subida, não recomeça calado")
	void ilegivelEstoura() throws Exception {
		Path arquivo = pasta.resolve("quentes.json");
		Files.writeString(arquivo, "[ não é o formato");
		assertThrows(IllegalStateException.class, () -> new Quentes(arquivo));
	}
}