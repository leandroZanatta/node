package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarentenaTest {

	private static final byte[] POOL = HexFormat.of().parseHex("7a3eb6e7ffbf9f8b65afd40c0ab0ac2bab0c5d8e");
	private static final byte[] OUTRA = HexFormat.of().parseHex("11".repeat(20));

	@Test
	void sobreviveAoRestart(@TempDir Path pasta) {

		Path arquivo = pasta.resolve("quarentena.json");
		Quarentena antes = new Quarentena(arquivo);

		antes.condenar(POOL, Duration.ofHours(1), "REVERTEU");
		antes.proibirEmprestimo(OUTRA, "0456f747");
		antes.medirTaxa(POOL, 100_000);

		assertTrue(Files.exists(arquivo));

		Quarentena depois = new Quarentena(arquivo);

		assertFalse(depois.livre(POOL), "a pena se perdeu no restart");
		assertTrue(depois.podeEmprestar(OUTRA), "proibição por callback é descartada: o Executor tem fallback");
		assertEquals(Optional.of(100_000), depois.taxaMedida(POOL), "a taxa medida se perdeu no restart");
		assertTrue(depois.livre(OUTRA));
	}

	@Test
	void reincidenciaDobraAPena() {

		Quarentena quarentena = new Quarentena();

		quarentena.condenar(POOL, Duration.ofMillis(1), "primeira");
		quarentena.condenar(POOL, Duration.ofMinutes(30), "segunda");

		assertTrue(quarentena.presas().get(HexFormat.of().formatHex(POOL)).contains("reincidência 1"));
	}
}