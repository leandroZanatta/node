package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.ensaio.RotaDoModelo;
import br.com.node.ensaio.Salto;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Perna;
import br.com.node.nucleo.Travessia;
import br.com.node.vo.Log;

class CotadorDeGerenteTest {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] POOL = HexFormat.of()
			.parseHex("d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944");

	
	private static final Log NATIVO_BNB_ENTRA_ANTES = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b",
			List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
					"d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944",
					"0000000000000000000000002d9d6e538bd3f22323932782aaf89446cacaf9d3"),
			"fffffffffffffffffffffffffffffffffffffffffffffffffe64eb94a66c1cc0"
					+ "000000000000000000000000000000000000000000000004dcd8a9c062d628ad"
					+ "000000000000000000000000000000000000001bd7dbfe0abfc5838e9d476c67"
					+ "0000000000000000000000000000000000000000000008a33086d3254112bbeb"
					+ "00000000000000000000000000000000000000000000000000000000000103e7"
					+ "0000000000000000000000000000000000000000000000000000000000000063"
					+ "0000000000000000000000000000000000000000000000000000000000000020");
	
	private static final Log NATIVO_BNB_ENTRA = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b",
			List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
					"d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944",
					"00000000000000000000000068d04638eb46cd37c7b4ae316ac1da4a330c75c6"),
			"fffffffffffffffffffffffffffffffffffffffffffffffffeeda240bb605582"
					+ "0000000000000000000000000000000000000000000000033ebc6ed002402560"
					+ "000000000000000000000000000000000000001bd77bd06643e33c168ae66f1d"
					+ "0000000000000000000000000000000000000000000008a33086d3254112bbeb"
					+ "00000000000000000000000000000000000000000000000000000000000103e6"
					+ "0000000000000000000000000000000000000000000000000000000000000063"
					+ "0000000000000000000000000000000000000000000000000000000000000020");
	
	private static final Log NATIVO_USDT_ENTRA_ANTES = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b",
			List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
					"d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944",
					"00000000000000000000000068d04638eb46cd37c7b4ae316ac1da4a330c75c6"),
			"fffffffffffffffffffffffffffffffffffffffffffffffffeeda240bb605582"
					+ "0000000000000000000000000000000000000000000000033ebc6ed002402560"
					+ "000000000000000000000000000000000000001bd77bd06643e33c168ae66f1d"
					+ "0000000000000000000000000000000000000000000008a33086d3254112bbeb"
					+ "00000000000000000000000000000000000000000000000000000000000103e6"
					+ "0000000000000000000000000000000000000000000000000000000000000063"
					+ "0000000000000000000000000000000000000000000000000000000000000020");
	
	private static final Log NATIVO_USDT_ENTRA = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b",
			List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
					"d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944",
					"000000000000000000000000070f82aee1abb53860143968def1e054e407af93"),
			"0000000000000000000000000000000000000000000000000025573d0763fc47"
					+ "ffffffffffffffffffffffffffffffffffffffffffffffff8eebc5e65f15e831"
					+ "000000000000000000000000000000000000001bd788e78ae06ade7e5113567f"
					+ "0000000000000000000000000000000000000000000008a33086d3254112bbeb"
					+ "00000000000000000000000000000000000000000000000000000000000103e6"
					+ "0000000000000000000000000000000000000000000000000000000000000063"
					+ "0000000000000000000000000000000000000000000000000000000000000020");

	private static final ChaveDaPool CHAVE = new ChaveDaPool(Eventos.GERENTE_INFINITY_CL, POOL, new byte[20], USDT, 67,
			1, new byte[20], 0, 1L);

	@Test
	@DisplayName("o Par diz USDT primeiro; os LADOS da pool dizem BNB (como WBNB) primeiro")
	void parELadosDiscordam() {

		Catalogo catalogo = catalogoCom(NATIVO_BNB_ENTRA_ANTES);

		assertArrayEquals(USDT, catalogo.par(POOL).orElseThrow().token0());
		assertArrayEquals(WBNB, catalogo.lados(POOL).orElseThrow().token0());
		assertTrue(CHAVE.confere());
	}

	@Test
	@DisplayName("WBNB entrando: a direção é a da POOL (zero para um), e sai ≈ entrada × preço em USDT")
	void wbnbEntrando() {
		cotarNoSentido(NATIVO_BNB_ENTRA_ANTES, WBNB, USDT, Direcao.ZERO_PARA_UM);
	}

	@Test
	@DisplayName("USDT entrando: um para zero, e sai ≈ entrada ÷ preço em WBNB")
	void usdtEntrando() {
		cotarNoSentido(NATIVO_USDT_ENTRA_ANTES, USDT, WBNB, Direcao.UM_PARA_ZERO);
	}

	@Test
	@DisplayName("a rota traduzida vai ao gerente certo, com o tipo Infinity, WBNB na borda e a chave que dá o poolId")
	void rotaTraduzida() {

		Catalogo catalogo = catalogoCom(NATIVO_BNB_ENTRA_ANTES);
		Tarifario tarifario = new Tarifario(catalogo, new Taxas(), new Fabricas());
		Cotador cotador = new Cotador(catalogo, new Impostos(), tarifario, new Quarentena());

		Ciclo ciclo = new Ciclo(List.of(new Perna(POOL, WBNB, USDT)));
		List<Travessia> travessias = cotador.travessias(ciclo).orElseThrow();
		Achado achado = new Achado(1L, ciclo, new Oportunidade(BigInteger.TEN, BigInteger.valueOf(20)), travessias);

		Salto salto = RotaDoModelo.traduzir(achado, catalogo, tarifario, BigInteger.ONE).orElseThrow().rota().get(0);

		assertEquals(HexFormat.of().formatHex(Eventos.GERENTE_INFINITY_CL), salto.pool());
		assertEquals(Salto.TIPO_INFINITY_CL, salto.tipo());
		assertEquals(HexFormat.of().formatHex(WBNB), salto.entra());
		assertEquals(HexFormat.of().formatHex(USDT), salto.sai());
		assertArrayEquals(CHAVE.abi(), salto.chave());
	}

	private static void cotarNoSentido(Log estado, byte[] entra, byte[] sai, Direcao esperada) {

		Catalogo catalogo = catalogoCom(estado);
		Cotador cotador = new Cotador(catalogo, new Impostos(), new Tarifario(catalogo, new Taxas(), new Fabricas()),
				new Quarentena());

		Travessia travessia = cotador.travessias(new Ciclo(List.of(new Perna(POOL, entra, sai)))).orElseThrow().get(0);

		assertEquals(esperada, travessia.direcao());

		
		
		BigDecimal raiz = new BigDecimal(Eventos.palavra(estado, 2)).divide(new BigDecimal(BigInteger.TWO.pow(96)),
				MathContext.DECIMAL128);
		BigDecimal preco = raiz.multiply(raiz);
		BigInteger entrada = BigInteger.TEN.pow(12);
		BigDecimal saida = new BigDecimal(travessia.aplicar(entrada).saida());
		BigDecimal ideal = esperada == Direcao.ZERO_PARA_UM ? new BigDecimal(entrada).multiply(preco)
				: new BigDecimal(entrada).divide(preco, MathContext.DECIMAL128);

		double razao = saida.divide(ideal, MathContext.DECIMAL128).doubleValue();

		assertTrue(preco.doubleValue() > 100 && preco.doubleValue() < 10_000,
				"USDT por BNB fora do plausível: " + preco);
		assertTrue(razao > 0.99 && razao <= 1.0, "saída / (entrada × preço) = " + razao + " — direção ou taxa errada");
	}

	private static Catalogo catalogoCom(Log swap) {

		Catalogo catalogo = new Catalogo();
		catalogo.aprenderChave(CHAVE);
		catalogo.aplicarSwapDeGerente(swap, 1L);

		return catalogo;
	}

	private static Log log(String emissor, List<String> topicos, String dados) {

		List<byte[]> t = new ArrayList<>();
		topicos.forEach(topico -> t.add(HexFormat.of().parseHex(topico)));

		return new Log(HexFormat.of().parseHex(emissor), t, HexFormat.of().parseHex(dados));
	}
}