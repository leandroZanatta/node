package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.transformacao.nucleo.Par;

class FabricasTest {

	private static final String WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
	private static final String USDT = "55d398326f99059ff775485246999027b3197955";

	private final Fabricas fabricas = new Fabricas();

	@Test
	@DisplayName("V2 de taxa fixa: a identidade prova a factory e a taxa do bytecode")
	void v2DeTaxaFixa() {

		identifica("16b9a82891338f9ba80e2d6970fdda79d1eb0dae", WBNB, USDT, "PancakeSwap V2", OptionalInt.of(2500),
				OptionalInt.empty());

		
		identifica("9361eb3333d96697de09cc616b93e02ba6cdc57c", WBNB, "f0effed6865b0d871e2301af24762620d9f9744c",
				"PancakeSwap V1", OptionalInt.of(2000), OptionalInt.empty());
	}

	@Test
	@DisplayName("V3: a taxa está dentro do endereço, e o espaçamento é o da tabela DAQUELA factory")
	void v3ProvaTaxaEEspacamento() {

		identifica("47a90a2d92a8367a91efa1906bfc8c1e05bf10c4", WBNB, USDT, "Uniswap V3", OptionalInt.of(100),
				OptionalInt.of(1));
		identifica("7862d9b4be2156b15d54f41ee4ede2d5b0b455e4", WBNB, USDT, "Uniswap V3", OptionalInt.of(3000),
				OptionalInt.of(60));
		identifica("172fcd41e0913e95784454622d1c3724f546f849", WBNB, USDT, "PancakeSwap V3", OptionalInt.of(100),
				OptionalInt.of(1));
		identifica("1401ff943d08a7e098328c1d3a9d388923b115d2", WBNB, USDT, "PancakeSwap V3", OptionalInt.of(2500),
				OptionalInt.of(50));
	}

	@Test
	@DisplayName("pool de factory desconhecida não é identificada — nem confundida com a primeira da lista")
	void desconhecidaFicaVazia() {

		
		
		assertTrue(
				fabricas.de(bytes("bfb3ffdc057744bf29d3554f9764f400354e2d76"),
						new Par(bytes("40b8129b786d766267a7a118cf8c07e31cdb6fde"), bytes(WBNB))).isEmpty(),
				"a pool de fork desconhecido foi identificada como factory conhecida");
	}

	private void identifica(String pool, String tokenA, String tokenB, String fabrica, OptionalInt taxa,
			OptionalInt espacamento) {

		Origem origem = fabricas.de(bytes(pool), new Par(bytes(tokenA), bytes(tokenB)))
				.orElseThrow(() -> new AssertionError("pool real " + pool + " não foi identificada"));

		assertEquals(fabrica, origem.fabrica().nome(), "factory errada para " + pool);
		assertEquals(taxa, origem.taxa(), "taxa errada para " + pool);
		assertEquals(espacamento, origem.espacamento(), "espaçamento errado para " + pool);
	}

	private static byte[] bytes(String hex) {
		return HexFormat.of().parseHex(hex);
	}
}