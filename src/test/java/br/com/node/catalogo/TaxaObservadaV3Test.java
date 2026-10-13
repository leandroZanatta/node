package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.nucleo.EstadoV3;
import br.com.node.vo.Log;

// A taxa de uma V3 de factory DESCONHECIDA, deduzida do que ela cobrou: dois swaps reais consecutivos da pool
// Slipstream 0xe7a0… (WBNB/0xc269…, factory 0x73dc… que a tabela não conhece), com a mesma liquidez — blocos
// 124043469 e 124069649. A pool declara 3000, e 218 de 218 pares medidos em 25/09 deram 3000.
class TaxaObservadaV3Test {

	private static final byte[] POOL = HexFormat.of().parseHex("e7a0861bff85e83394ca6a3c01ef4c30b19a8d28");

	private static final Log PRIMEIRO = swap(
			"fffffffffffffffffffffffffffffffffffffffffffffffffff667e8e80f2899" +
			"000000000000000000000000000000000000000000000082c9d17e17e3aadd24" +
			"00000000000000000000000000000000000003b27f165476acc8eec049e1f38d" +
			"00000000000000000000000000000000000000000000001804eb6f98461cae1b" +
			"0000000000000000000000000000000000000000000000000000000000021766");

	private static final Log SEGUNDO = swap(
			"00000000000000000000000000000000000000000000000000096c979740bd0b" +
			"ffffffffffffffffffffffffffffffffffffffffffffff80485140ea824b9c8a" +
			"00000000000000000000000000000000000003ad2ddb6a6c6a092db3bbc1136f" +
			"00000000000000000000000000000000000000000000001804eb6f98461cae1b" +
			"00000000000000000000000000000000000000000000000000000000000216f5");

	@Test
	@DisplayName("o par de swaps reais dá a taxa exata que a pool cobra: 3000")
	void taxaExata() {

		EstadoV3 antes = estado(PRIMEIRO);
		EstadoV3 depois = estado(SEGUNDO);

		assertEquals(3000, EstadoV3.taxaEntre(antes, depois, Eventos.palavraComSinalGrande(SEGUNDO, 0),
				Eventos.palavraComSinalGrande(SEGUNDO, 1)).orElseThrow());
	}

	@Test
	@DisplayName("o catálogo só entrega a taxa depois de DUAS deduções que batem")
	void catalogoExigeDuasQueBatem() {

		Catalogo catalogo = new Catalogo();
		catalogo.aplicarSwapV3(PRIMEIRO, 1L);
		catalogo.aplicarSwapV3(SEGUNDO, 2L);

		assertTrue(catalogo.taxaObservadaV3(POOL).isEmpty(), "uma dedução só não prova estabilidade");
		assertTrue(catalogo.taxaV3JaVista(POOL));
	}

	@Test
	@DisplayName("liquidez diferente (tick cruzado ou aporte) não deduz nada")
	void liquidezDiferenteNaoDeduz() {

		EstadoV3 antes = estado(PRIMEIRO);
		EstadoV3 outraLiquidez = new EstadoV3(estado(SEGUNDO).sqrtPrecoX96(), antes.liquidez().add(java.math.BigInteger.ONE),
				estado(SEGUNDO).tick(), 2L);

		assertTrue(EstadoV3.taxaEntre(antes, outraLiquidez, Eventos.palavraComSinalGrande(SEGUNDO, 0),
				Eventos.palavraComSinalGrande(SEGUNDO, 1)).isEmpty());
	}

	private static EstadoV3 estado(Log log) {
		return new EstadoV3(Eventos.palavra(log, 2), Eventos.palavra(log, 3), (int) Eventos.palavraComSinal(log, 4), 1L);
	}

	private static Log swap(String dados) {
		return new Log(POOL, List.of(Eventos.SWAP_V3, new byte[32], new byte[32]), HexFormat.of().parseHex(dados));
	}
}
