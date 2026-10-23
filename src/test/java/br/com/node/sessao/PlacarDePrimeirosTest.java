package br.com.node.sessao;
import br.com.node.baixa.p2p.*;
import br.com.node.baixa.protocolo.*;


import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlacarDePrimeirosTest {

	private static final long MEIA_VIDA = 600_000L;

	@Test
	void semEntregasValeZero() {
		assertEquals(0.0, new PlacarDePrimeiros(MEIA_VIDA).valor(123L), 0.0);
	}

	@Test
	void cadaEntregaSoma() {
		PlacarDePrimeiros p = new PlacarDePrimeiros(MEIA_VIDA);
		p.anotar(1_000L);
		p.anotar(1_000L);
		assertEquals(2.0, p.valor(1_000L), 1e-9);
	}

	@Test
	void meiaVidaCortaOPesoPelaMetade() {
		PlacarDePrimeiros p = new PlacarDePrimeiros(MEIA_VIDA);
		p.anotar(0L);
		assertEquals(0.5, p.valor(MEIA_VIDA), 1e-9);
		assertEquals(0.25, p.valor(2 * MEIA_VIDA), 1e-9);
	}

	@Test
	void novaEntregaSomaSobreOQueJaDecaiu() {
		PlacarDePrimeiros p = new PlacarDePrimeiros(MEIA_VIDA);
		p.anotar(0L);
		p.anotar(MEIA_VIDA);
		
		assertEquals(1.5, p.valor(MEIA_VIDA), 1e-9);
	}
}