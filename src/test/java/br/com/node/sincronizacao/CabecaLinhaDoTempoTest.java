package br.com.node.sincronizacao;
import br.com.node.baixa.sync.*;
import br.com.node.baixa.p2p.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.vo.AnuncioBloco;

class CabecaLinhaDoTempoTest {

	private static final byte[] HASH = HexFormat.of().parseHex("ab".repeat(32));

	@Test
	@DisplayName("os marcos do bloco: feito, anunciado, aplicado — e a propagação no resumo")
	@SuppressWarnings("unchecked")
	void marcos() {

		Cabeca cabeca = new Cabeca();
		long feito = System.currentTimeMillis() - 300;

		cabeca.anunciar(new AnuncioBloco(HASH, 100));
		cabeca.aplicada(100, feito);

		Cabeca.Momentos m = cabeca.momentos(100).orElseThrow();

		assertEquals(feito, m.blocoMs());
		assertTrue(m.anuncioMs() >= feito + 300 && m.anuncioMs() <= m.aplicadoMs());

		Map<String, Object> propagacao = (Map<String, Object>) cabeca.linhaDoTempo().get("bloco_ate_anuncio_ms");
		assertEquals(1, ((Number) propagacao.get("amostras")).intValue());
	}

	@Test
	@DisplayName("bloco sem instante (0) não entra na linha do tempo")
	void semInstante() {

		Cabeca cabeca = new Cabeca();
		cabeca.anunciar(new AnuncioBloco(HASH, 100));
		cabeca.aplicada(100);

		assertTrue(cabeca.momentos(100).isEmpty());
	}
}