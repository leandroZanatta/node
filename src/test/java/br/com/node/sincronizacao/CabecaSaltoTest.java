package br.com.node.sincronizacao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import br.com.node.vo.AnuncioBloco;

// A queda de rede de 24/09: depois dela a cadeia tinha andado mais que o teto de salto, todo anúncio
// verdadeiro virou "salto", e a cabeça ficou presa por mais de uma hora. O teto continua valendo para o
// anúncio isolado — e cede à rajada coerente, que é a rede.
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
