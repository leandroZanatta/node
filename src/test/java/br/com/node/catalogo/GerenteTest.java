package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.EstadoV3;
import br.com.node.nucleo.PoolV3;
import br.com.node.vo.Log;

// As pools de dentro dos GERENTES (Uniswap V4, PancakeSwap Infinity CL), provadas com logs REAIS da BSC
// colhidos em 25/09 — não com payload montado à mão, porque o que se quer provar aqui é a leitura do
// protocolo (sinal, taxa, onde moram os ticks), e isso só a cadeia responde.
//
// A prova mais forte é a do par de swaps: o estado do primeiro, com a taxa que o segundo cobrou, faz o
// `PoolV3` que já temos prever a saída do segundo. Se ela bate, a matemática do V3 serve aos gerentes e
// a peça seguinte (o `PoolKey`, para ligar no grafo) pode confiar neste estado.
class GerenteTest {

	// bloco 123972063, tx 37
	private static final Log ML_DENTRO_ANTES = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"f43fdb854021ddeb41e06ac1d6e5df475197038ba5d3cba147f469a56870cd1b",
				"00000000000000000000000034bf78ccce09ade81499cbec7c06804ba1d1559e"),
			"0000000000000000000000000000000000000000000000047f4e469dcbdbac23" +
				"ffffffffffffffffffffffffffffffffffffffffffffffc79446a849ecf20000" +
				"00000000000000000000000000000000000000038ab2bff3f60c0bff685c482c" +
				"000000000000000000000000000000000000000003aeda5860775b0d63154a27" +
				"00000000000000000000000000000000000000000000000000000000000062cd" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972063, tx 51
	private static final Log ML_DENTRO = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("f208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec",
				"f43fdb854021ddeb41e06ac1d6e5df475197038ba5d3cba147f469a56870cd1b",
				"00000000000000000000000055f4c8aba71a1e923edc303eb4feff14608cc226"),
			"00000000000000000000000000000000000000000000000000000000000062cd" +
				"00000000000000000000000000000000000000000000000000000000000062ce" +
				"fffffffffffffffffffffffffffffffffffffffffe2bb304a4995ee2a40f9f0c" +
				"00000000000000000000000000000000000000000000000000000000001542cc");
	// bloco 123972063, tx 51
	private static final Log ML_DENTRO_DEPOIS = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"f43fdb854021ddeb41e06ac1d6e5df475197038ba5d3cba147f469a56870cd1b",
				"000000000000000000000000d9c500dff816a1da21a48a732d3498bf09dc9aeb"),
			"00000000000000000000000000000000000000000000000897678ab20bf3d926" +
				"ffffffffffffffffffffffffffffffffffffffffffffff94374dc3b5722e29f3" +
				"00000000000000000000000000000000000000038ab2fa1787fba9485c342a6d" +
				"000000000000000000000000000000000000000001da8d5d0510b9f00724e933" +
				"00000000000000000000000000000000000000000000000000000000000062cd" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972037, tx 20
	private static final Log ML_FORA_ANTES = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"3323d6033ecec1c7c3f737d475f9cee29848bd16bc4ab0e7a11938e105c121eb",
				"00000000000000000000000077b8a058fbb5594b40f8fb75f531ccc7bfc740b0"),
			"fffffffffffffffffffffffffffffffffffffffffffffff6851229feb91f9b70" +
				"0000000000000000000000000000000000000000000006c33a6cb300e2bdbb9b" +
				"000000000000000000000000000000000000000d830b2fc09aa6d736281a1804" +
				"00000000000000000000000000000000000000000005bc034edb19b8145250a1" +
				"000000000000000000000000000000000000000000000000000000000000cb6a" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972037, tx 20
	private static final Log ML_FORA = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("f208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec",
				"3323d6033ecec1c7c3f737d475f9cee29848bd16bc4ab0e7a11938e105c121eb",
				"000000000000000000000000982f8a06dcce09f5819179b3645690919733fda9"),
			"000000000000000000000000000000000000000000000000000000000000cb68" +
				"000000000000000000000000000000000000000000000000000000000000cb69" +
				"000000000000000000000000000000000000000000271b1e8013dd10995c8561" +
				"34936e75672377990068fa4abfd20d59f301bd464aa1655b4b98b05135ad7f98");
	// bloco 123972037, tx 21
	private static final Log ML_FORA_DEPOIS = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"3323d6033ecec1c7c3f737d475f9cee29848bd16bc4ab0e7a11938e105c121eb",
				"000000000000000000000000d9c500dff816a1da21a48a732d3498bf09dc9aeb"),
			"fffffffffffffffffffffffffffffffffffffffffffffff28d7325780c5a6a08" +
				"00000000000000000000000000000000000000000000099683233be1b5050c0e" +
				"000000000000000000000000000000000000000d828d0e6e117152cd2a8157ad" +
				"00000000000000000000000000000000000000000005bc034edb19b8145250a1" +
				"000000000000000000000000000000000000000000000000000000000000cb67" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972017, tx 21
	private static final Log V4_Z1_ANTES = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df", List.of("40e9cecb9f5f1f1c5b9c97dec2917b7ee92e57ba5563708daca94dd84ad7112f",
				"a151adaa80cec5e94f942cb8f4c57c684b81c72b656e06214cada954eb205950",
				"000000000000000000000000b03f42644ed3804b45af4b3264a89bcd93d01ab7"),
			"0000000000000000000000000000000000000000000000daabc585f2bd099a9d" +
				"fffffffffffffffffffffffffffffffffffffffffffffff8672151ec57ac8000" +
				"00000000000000000000000000000000000000002fb82e0dffe3a8f78528d8a2" +
				"00000000000000000000000000000000000000000002e8ed48f03a1ab952511f" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7cc1" +
				"000000000000000000000000000000000000000000000000000000000000005f");
	// bloco 123972018, tx 19
	private static final Log V4_Z1 = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df", List.of("40e9cecb9f5f1f1c5b9c97dec2917b7ee92e57ba5563708daca94dd84ad7112f",
				"a151adaa80cec5e94f942cb8f4c57c684b81c72b656e06214cada954eb205950",
				"00000000000000000000000062d27aee19b32fa63b6d6c18fbbdcbe870b083e3"),
			"ffffffffffffffffffffffffffffffffffffffffffffffb0a9c4467495d06fce" +
				"000000000000000000000000000000000000000000000002c1966ea2dd5b2b39" +
				"00000000000000000000000000000000000000002fb73b92ed7134bcb8922404" +
				"00000000000000000000000000000000000000000002e8ed48f03a1ab952511f" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7cc0" +
				"000000000000000000000000000000000000000000000000000000000000005f");
	// bloco 123972018, tx 26
	private static final Log V4_1Z_ANTES = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df", List.of("40e9cecb9f5f1f1c5b9c97dec2917b7ee92e57ba5563708daca94dd84ad7112f",
				"a151adaa80cec5e94f942cb8f4c57c684b81c72b656e06214cada954eb205950",
				"00000000000000000000000062d27aee19b32fa63b6d6c18fbbdcbe870b083e3"),
			"ffffffffffffffffffffffffffffffffffffffffffffffb235e76813a51ba8ac" +
				"000000000000000000000000000000000000000000000002b3825570d9136253" +
				"00000000000000000000000000000000000000002fb472926ef3f0356fa439da" +
				"00000000000000000000000000000000000000000002e8ed48f03a1ab952511f" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7cbb" +
				"000000000000000000000000000000000000000000000000000000000000005f");
	// bloco 123972018, tx 27
	private static final Log V4_1Z = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df", List.of("40e9cecb9f5f1f1c5b9c97dec2917b7ee92e57ba5563708daca94dd84ad7112f",
				"a151adaa80cec5e94f942cb8f4c57c684b81c72b656e06214cada954eb205950",
				"0000000000000000000000009999b0cdd35d7f3b281ba02efc0d228486940515"),
			"000000000000000000000000000000000000000000000091f1afd2f0867e3958" +
				"fffffffffffffffffffffffffffffffffffffffffffffffaee4c163381f57731" +
				"00000000000000000000000000000000000000002fb6307ed4a19311c7d455bd" +
				"00000000000000000000000000000000000000000002e8ed48f03a1ab952511f" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7cbe" +
				"000000000000000000000000000000000000000000000000000000000000005f");
	// bloco 123972017, tx 20
	private static final Log INF_Z1_ANTES = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"00e88ebcbe5cfb5406efbcae21c3dd8389583346bc47935116b926100e9c4c3b",
				"0000000000000000000000007e59e1fb5cb8c809227c65d546ec0881e3e2d2b5"),
			"ffffffffffffffffffffffffffffffffffffffffffffffa9c7d71639e10d68de" +
				"000000000000000000000000000000000000000000000000e09911200d87aa66" +
				"000000000000000000000000000000000000000019d3294f96d377b06165f3d7" +
				"00000000000000000000000000000000000000000006ff6c7f35cb8c7de37eca" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff4cc8" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972017, tx 35
	private static final Log INF_Z1 = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"00e88ebcbe5cfb5406efbcae21c3dd8389583346bc47935116b926100e9c4c3b",
				"0000000000000000000000005836abdf71efa0e5dd5a590aba36ed5ae75730ac"),
			"fffffffffffffffffffffffffffffffffffffffffffffdfefc096eb7c2d80aed" +
				"0000000000000000000000000000000000000000000000053835447563a3c7eb" +
				"000000000000000000000000000000000000000019d26a5cd568cd81f6a729d3" +
				"00000000000000000000000000000000000000000006ff6c7f35cb8c7de37eca" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff4cc6" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972018, tx 28
	private static final Log INF_1Z_ANTES = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"a27e05a64e6e5b1d967438c6ce6a2796bb95c7396ca7c40ae35373f37d29b77f",
				"00000000000000000000000034bf78ccce09ade81499cbec7c06804ba1d1559e"),
			"fffffffffffffffffffffffffffffffffffffffffffffffe028d336bc3a84fe0" +
				"0000000000000000000000000000000000000000000000000000000000f2c4f8" +
				"000000000000000000000000000000000000000000000b0b2d5ebbc69ec80a54" +
				"00000000000000000000000000000000000000000000000000f7a69d142b942d" +
				"fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffba7f0" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");
	// bloco 123972018, tx 29
	private static final Log INF_1Z = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", List.of("04206ad2b7c0f463bff3dd4f33c5735b0f2957a351e4f79763a4fa9e775dd237",
				"a27e05a64e6e5b1d967438c6ce6a2796bb95c7396ca7c40ae35373f37d29b77f",
				"0000000000000000000000009999b0cdd35d7f3b281ba02efc0d228486940515"),
			"000000000000000000000000000000000000000000000000b801440feef0d640" +
				"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffa85146" +
				"000000000000000000000000000000000000000000000b0b87ffeb51b00896dd" +
				"00000000000000000000000000000000000000000000000000f7a69d142b942d" +
				"fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffba7f2" +
				"0000000000000000000000000000000000000000000000000000000000000063" +
				"0000000000000000000000000000000000000000000000000000000000000020");

	@Test
	@DisplayName("o Swap da V4 vira estado: preço, liquidez, tick e a taxa cobrada, fora do grafo")
	void swapDaV4ViraEstado() {

		Catalogo catalogo = new Catalogo();

		assertTrue(Eventos.ehSwapDeGerente(V4_Z1));
		catalogo.aplicarSwapDeGerente(V4_Z1, 7L);

		EstadoNoGerente estado = catalogo.estadoNoGerente(Eventos.GERENTE_V4, V4_Z1.topicos().get(1)).orElseThrow();

		assertEquals(Eventos.palavra(V4_Z1, 2), estado.estado().sqrtPrecoX96());
		assertEquals(Eventos.palavra(V4_Z1, 3), estado.estado().liquidez());
		assertEquals(Eventos.palavraComSinal(V4_Z1, 4), estado.estado().tick());
		assertEquals(Eventos.palavra(V4_Z1, 5).intValueExact(), estado.taxa());
		assertEquals(7L, estado.estado().altura());
		assertEquals(1, catalogo.poolsNoGerente(Eventos.GERENTE_V4));
		assertEquals(0, catalogo.poolsNoGerente(Eventos.GERENTE_INFINITY_CL));
		assertTrue(catalogo.pools().isEmpty(), "sem PoolKey não se liga no grafo");
	}

	@Test
	@DisplayName("o PoolV3 prevê o swap seguinte, nos dois gerentes e nas duas direções")
	void poolV3PreveOSwapSeguinte() {

		preve(V4_Z1_ANTES, V4_Z1, Direcao.ZERO_PARA_UM);
		preve(V4_1Z_ANTES, V4_1Z, Direcao.UM_PARA_ZERO);
		preve(INF_Z1_ANTES, INF_Z1, Direcao.ZERO_PARA_UM);
		preve(INF_1Z_ANTES, INF_1Z, Direcao.UM_PARA_ZERO);
	}

	@Test
	@DisplayName("ModifyLiquidity com faixa cobrindo o tick soma o delta — e o swap seguinte confirma")
	void liquidezDentroDaFaixaSoma() {

		Catalogo catalogo = new Catalogo();
		byte[] poolId = ML_DENTRO.topicos().get(1);

		catalogo.aplicarSwapDeGerente(ML_DENTRO_ANTES, 1L);

		assertTrue(Eventos.ehLiquidezDeGerente(ML_DENTRO));
		assertTrue(Eventos.palavraComSinalGrande(ML_DENTRO, 2).signum() < 0, "o caso real é de RETIRADA");

		catalogo.aplicarLiquidezDeGerente(ML_DENTRO, 1L);

		assertEquals(Eventos.palavra(ML_DENTRO_DEPOIS, 3),
				catalogo.estadoNoGerente(Eventos.GERENTE_INFINITY_CL, poolId).orElseThrow().estado().liquidez(),
				"a liquidez que o próximo Swap da cadeia trouxe");
	}

	@Test
	@DisplayName("ModifyLiquidity com faixa fora do tick não mexe — e o swap seguinte confirma")
	void liquidezForaDaFaixaNaoMexe() {

		Catalogo catalogo = new Catalogo();
		byte[] poolId = ML_FORA.topicos().get(1);

		catalogo.aplicarSwapDeGerente(ML_FORA_ANTES, 1L);
		catalogo.aplicarLiquidezDeGerente(ML_FORA, 1L);

		assertEquals(Eventos.palavra(ML_FORA_DEPOIS, 3),
				catalogo.estadoNoGerente(Eventos.GERENTE_INFINITY_CL, poolId).orElseThrow().estado().liquidez());
		assertEquals(Eventos.palavra(ML_FORA_ANTES, 3), Eventos.palavra(ML_FORA_DEPOIS, 3));
	}

	@Test
	@DisplayName("o topic0 certo vindo do contrato errado não conta — log não é autenticado")
	void emissorFalsoNaoConta() {

		byte[] impostor = HexFormat.of().parseHex("11".repeat(20));

		assertFalse(Eventos.ehSwapDeGerente(new Log(impostor, V4_Z1.topicos(), V4_Z1.dados())));
		assertFalse(Eventos.ehLiquidezDeGerente(new Log(impostor, ML_DENTRO.topicos(), ML_DENTRO.dados())));

		// A forma de um gerente vinda do outro também não: cada um tem o seu Swap.
		assertFalse(Eventos.ehSwapDeGerente(new Log(Eventos.GERENTE_INFINITY_CL, V4_Z1.topicos(), V4_Z1.dados())));
		assertFalse(Eventos.ehSwapDeGerente(new Log(Eventos.GERENTE_V4, INF_Z1.topicos(), INF_Z1.dados())));
	}

	@Test
	@DisplayName("taxa de 100% ou mais é descartada e contada, não vira estado")
	void taxaImpossivelEDescartada() {

		byte[] dados = V4_Z1.dados().clone();
		dados[5 * 32 + 28] = 0x01; // palavra 5 ≥ 0x01000000, acima de 1.000.000

		Catalogo catalogo = new Catalogo();
		catalogo.aplicarSwapDeGerente(new Log(Eventos.GERENTE_V4, V4_Z1.topicos(), dados), 1L);

		assertEquals(0, catalogo.poolsNoGerente(Eventos.GERENTE_V4));
		assertEquals(1, catalogo.poolsDescartadas());
	}

	// Entrada = o NEGATIVO do evento (visão de quem chama); saída = o positivo. A taxa é a que o
	// SEGUNDO swap cobrou: em pool de taxa dinâmica ela muda de um para o outro.
	private static void preve(Log antes, Log depois, Direcao direcao) {

		EstadoV3 estado = new EstadoV3(Eventos.palavra(antes, 2), Eventos.palavra(antes, 3),
				(int) Eventos.palavraComSinal(antes, 4), 0L);
		int taxa = Eventos.palavra(depois, 5).intValueExact();

		BigInteger quantia0 = Eventos.palavraComSinalGrande(depois, 0);
		BigInteger quantia1 = Eventos.palavraComSinalGrande(depois, 1);
		BigInteger entrada = (direcao == Direcao.ZERO_PARA_UM ? quantia0 : quantia1).negate();
		BigInteger saida = direcao == Direcao.ZERO_PARA_UM ? quantia1 : quantia0;

		// O espaçamento só limita até onde a faixa vai; a liquidez igual nos dois eventos já prova que o
		// swap não cruzou tick inicializado, então um espaçamento largo não aceita nada que a cadeia recusou.
		PoolV3 pool = new PoolV3(estado.sqrtPrecoX96(), estado.liquidez(), estado.tick(), taxa, 1 << 14);
		BigInteger prevista = pool.aplicar(direcao, entrada).saida();

		assertTrue(prevista.compareTo(saida) <= 0, "o modelo nunca promete mais que a cadeia: " + prevista + " > " + saida);
		assertTrue(saida.subtract(prevista).compareTo(BigInteger.ONE) <= 0,
				"a diferença é arredondamento, não modelo: " + prevista + " contra " + saida);
	}

	// O swap real da pool V4 sem hook, para outros testes do pacote.
	static Log swapV4() {
		return V4_Z1;
	}

	private static Log log(String emissor, List<String> topicos, String dados) {

		List<byte[]> t = new ArrayList<>();
		topicos.forEach(topico -> t.add(HexFormat.of().parseHex(topico)));

		return new Log(HexFormat.of().parseHex(emissor), t, HexFormat.of().parseHex(dados));
	}
}
