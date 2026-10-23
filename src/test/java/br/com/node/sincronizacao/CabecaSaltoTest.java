package br.com.node.sincronizacao;
import br.com.node.baixa.sync.*;
import br.com.node.baixa.p2p.*;


import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import br.com.node.vo.AnuncioBloco;

class CabecaSaltoTest {

	private static AnuncioBloco anuncio(long numero) {
		return new AnuncioBloco(new byte[32], numero);
	}

	@Test
	void saltoIsoladoSegueIgnorado() {

		Cabeca cabeca = new Cabeca();
		cabeca.anunciar(anuncio(1_000));
		cabeca.anunciar(anuncio(1_000 + 5_000));

		assertEquals(1_000, cabeca.alvo());
		assertEquals(0, cabeca.reancoragens());
	}

	@Test
	void rajadaCoerenteReancora() {

		Cabeca cabeca = new Cabeca();
		cabeca.anunciar(anuncio(1_000));

		for (int i = 0; i < 8; i++) {
			cabeca.anunciar(anuncio(9_000 + i));
		}

		assertEquals(1, cabeca.reancoragens());
		assertEquals(9_007, cabeca.alvo());
	}

	@Test
	void rajadaIncoerenteNaoReancora() {

		Cabeca cabeca = new Cabeca();
		cabeca.anunciar(anuncio(1_000));

		for (int i = 0; i < 8; i++) {
			cabeca.anunciar(anuncio(i % 2 == 0 ? 9_000 : 90_000));
		}

		assertEquals(1_000, cabeca.alvo());
	}
}