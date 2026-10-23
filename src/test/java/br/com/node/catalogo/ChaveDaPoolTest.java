package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.vo.Log;

class ChaveDaPoolTest {

	
	private static final Log V4_SEM_HOOK = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df",
			List.of("dd466e674ea557f56295e2d0218a125ea4b4f0f6f3307b95f85e6110838d6438",
					"a151adaa80cec5e94f942cb8f4c57c684b81c72b656e06214cada954eb205950",
					"0000000000000000000000003aee7602b612de36088f3ffed8c8f10e86ebf2bf",
					"00000000000000000000000055d398326f99059ff775485246999027b3197955"),
			"000000000000000000000000000000000000000000000000000000000000004b"
					+ "0000000000000000000000000000000000000000000000000000000000000001"
					+ "0000000000000000000000000000000000000000000000000000000000000000"
					+ "00000000000000000000000000000000000000002f3405d17d71115ccaf66f10"
					+ "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7be8");
	
	private static final Log V4_DINAMICA = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df",
			List.of("dd466e674ea557f56295e2d0218a125ea4b4f0f6f3307b95f85e6110838d6438",
					"ee496d8c1f9aa17683afce5c54ec00d5706929b5bb4d00b4757882f74174f365",
					"00000000000000000000000055d398326f99059ff775485246999027b3197955",
					"0000000000000000000000008ac76a51cc950d9822d68b83fe1ad97b32cd580d"),
			"0000000000000000000000000000000000000000000000000000000000800000"
					+ "0000000000000000000000000000000000000000000000000000000000000001"
					+ "00000000000000000000000055b0af59f7f64140f198018996219800c9178080"
					+ "0000000000000000000000000000000000000001000000000000000000000000"
					+ "0000000000000000000000000000000000000000000000000000000000000000");
	
	private static final Log V4_HOOK_DE_SWAP = log("28e2ea090877bf75740558f6bfb36a5ffee9e9df",
			List.of("dd466e674ea557f56295e2d0218a125ea4b4f0f6f3307b95f85e6110838d6438",
					"5ecfdfe0c473d84036069af1d31917c4ff45bb1b77c2873a9ccafd24e1095de3",
					"0000000000000000000000000000000000000000000000000000000000000000",
					"000000000000000000000000e2a59d5e33c6540e18aaa46bf98917ac3158db0d"),
			"0000000000000000000000000000000000000000000000000000000000002710"
					+ "00000000000000000000000000000000000000000000000000000000000000c8"
					+ "000000000000000000000000650c40e697f882267d770db82a0b567d6da7cfe0"
					+ "00000000000000000000000000000000000000a1dc18d861c248000000000000"
					+ "0000000000000000000000000000000000000000000000000000000000018d6b");
	
	private static final Log INF_SEM_HOOK = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b",
			List.of("426cc62fe6a33a40ba2788c2c87a9c34ee4582b95bc9fa5a7bb7ae70b750b99c",
					"8b5edf627d73d575e561211229983fb072ba5a89cc8bc5bcce9f22d271c681d8",
					"0000000000000000000000002d81ed416d0ffda992008231aa07d1de5066170c",
					"00000000000000000000000055d398326f99059ff775485246999027b3197955"),
			"0000000000000000000000000000000000000000000000000000000000000000"
					+ "00000000000000000000000000000000000000000000000000000000000009c4"
					+ "00000000000000000000000000000000000000000000000000000000000a0000"
					+ "0000000000000000000000000000000000000001000000000000000000000000"
					+ "0000000000000000000000000000000000000000000000000000000000000000");
	
	private static final Log INF_HOOK_B0BB = log("a0ffb9c1ce1fe56963b0321b32e7a0302114058b",
			List.of("426cc62fe6a33a40ba2788c2c87a9c34ee4582b95bc9fa5a7bb7ae70b750b99c",
					"6269b885749c07185098594658a543f10c10167356695dbfe6919d403bb4281f",
					"00000000000000000000000065180ae2eaf0b55dda5335041a47ad1170c7d25e",
					"0000000000000000000000006eb2cbdb4fca0058cb63baafd4f50227f0bf5956"),
			"000000000000000000000000b0bb171d333569cfd28a37f5c5dddaaa90ad46af"
					+ "000000000000000000000000000000000000000000000000000000000000014f"
					+ "00000000000000000000000000000000000000000000000000000000000a0045"
					+ "0000000000000000000000000000000000000001186f174f884725a02e34c78c"
					+ "000000000000000000000000000000000000000000000000000000000000071f");

	@Test
	@DisplayName("V4 sem hook e taxa fixa: modelável — é a pool dos pares de swap do GerenteTest")
	void v4SemHook() {

		ChaveDaPool chave = ChaveDaPool.doInitialize(V4_SEM_HOOK, 9L).orElseThrow();

		assertArrayEquals(Eventos.GERENTE_V4, chave.gerente());
		assertEquals("3aee7602b612de36088f3ffed8c8f10e86ebf2bf", HexFormat.of().formatHex(chave.token0()));
		assertEquals("55d398326f99059ff775485246999027b3197955", HexFormat.of().formatHex(chave.token1()));
		assertEquals(75, chave.taxaLp());
		assertEquals(1, chave.espacamento());
		assertEquals(0, chave.permissoes());
		assertEquals(9L, chave.altura());
		assertTrue(chave.modelavel());
		assertFalse(chave.hookForaDoSwap());
	}

	@Test
	@DisplayName("V4 de taxa dinâmica não é modelável: a taxa é do hook, swap a swap")
	void v4Dinamica() {

		ChaveDaPool chave = ChaveDaPool.doInitialize(V4_DINAMICA, 1L).orElseThrow();

		assertEquals(ChaveDaPool.TAXA_DINAMICA, chave.taxaLp());
		assertFalse(chave.modelavel());
	}

	@Test
	@DisplayName("V4 com hook de swap: as permissões saem dos 14 bits baixos do endereço do hook")
	void v4HookDeSwap() {

		ChaveDaPool chave = ChaveDaPool.doInitialize(V4_HOOK_DE_SWAP, 1L).orElseThrow();

		assertEquals(10000, chave.taxaLp());
		assertEquals(200, chave.espacamento());
		assertEquals(0x0fe0, chave.permissoes(), "hook 0x…cfe0");
		assertTrue((chave.permissoes() & 1 << 7) != 0, "beforeSwap ligado");
		assertEquals("0000000000000000000000000000000000000000", HexFormat.of().formatHex(chave.token0()),
				"token zero é o BNB nativo");
		assertFalse(chave.modelavel());
		assertFalse(chave.hookForaDoSwap());
	}

	@Test
	@DisplayName("Infinity sem hook: espaçamento e permissões saem de `parameters`")
	void infinitySemHook() {

		ChaveDaPool chave = ChaveDaPool.doInitialize(INF_SEM_HOOK, 1L).orElseThrow();

		assertArrayEquals(Eventos.GERENTE_INFINITY_CL, chave.gerente());
		assertEquals(2500, chave.taxaLp());
		assertEquals(10, chave.espacamento());
		assertEquals(0, chave.permissoes());
		assertTrue(chave.modelavel());
	}

	@Test
	@DisplayName("Infinity com o hook 0xb0bb… (beforeSwap sem delta): lido, e fora até provar que não reverte")
	void infinityHookB0bb() {

		ChaveDaPool chave = ChaveDaPool.doInitialize(INF_HOOK_B0BB, 1L).orElseThrow();

		assertEquals(335, chave.taxaLp());
		assertEquals(10, chave.espacamento());
		assertEquals(0b1000101, chave.permissoes(), "beforeInitialize, beforeAddLiquidity, beforeSwap");
		assertFalse(chave.modelavel());
		assertFalse(chave.hookForaDoSwap(), "beforeSwap é permissão de swap");
	}

	@Test
	@DisplayName("a chave remontada em ABI dá o poolId real — nas cinco formas, com e sem hook")
	void chaveRemontadaDaOPoolId() {

		for (Log log : List.of(V4_SEM_HOOK, V4_DINAMICA, V4_HOOK_DE_SWAP, INF_SEM_HOOK, INF_HOOK_B0BB)) {
			assertTrue(ChaveDaPool.doInitialize(log, 1L).orElseThrow().confere(),
					"keccak(abi(chave)) ≠ poolId em " + HexFormat.of().formatHex(log.topicos().get(1)));
		}
	}

	@Test
	@DisplayName("Initialize de um gerente com a forma do outro, ou de impostor, não vira chave")
	void formaOuEmissorErrado() {

		assertTrue(ChaveDaPool
				.doInitialize(new Log(Eventos.GERENTE_INFINITY_CL, V4_SEM_HOOK.topicos(), V4_SEM_HOOK.dados()), 1L)
				.isEmpty());
		assertTrue(ChaveDaPool.doInitialize(
				new Log(HexFormat.of().parseHex("11".repeat(20)), INF_SEM_HOOK.topicos(), INF_SEM_HOOK.dados()), 1L)
				.isEmpty());
	}

	@Test
	@DisplayName("catálogo: pede a chave UMA vez por pool, aprende, e só repede depois de a varredura avançar")
	void catalogoPedeUmaVez() {

		Catalogo catalogo = new Catalogo();
		List<String> pedidos = new ArrayList<>();
		catalogo.aoFaltarChave((gerente, poolId) -> pedidos.add(HexFormat.of().formatHex(poolId)));

		catalogo.aplicarSwapDeGerente(GerenteTest.swapV4(), 1L);
		catalogo.aplicarSwapDeGerente(GerenteTest.swapV4(), 2L);

		assertEquals(1, pedidos.size(), "o segundo swap não pede de novo");

		catalogo.liberarPedidas();
		catalogo.aplicarSwapDeGerente(GerenteTest.swapV4(), 3L);

		assertEquals(2, pedidos.size(), "a varredura avançou: a sem resposta pode ser pedida outra vez");

		catalogo.aprenderChave(ChaveDaPool.doInitialize(V4_SEM_HOOK, 1L).orElseThrow());
		catalogo.liberarPedidas();
		catalogo.aplicarSwapDeGerente(GerenteTest.swapV4(), 4L);

		assertEquals(2, pedidos.size(), "com a chave conhecida não há o que pedir");
		assertEquals(1L, catalogo.resumoDoGerente(Eventos.GERENTE_V4).get("modelaveis"));
	}

	@Test
	@DisplayName("catálogo: a pool que nasce ao vivo entra na memória e segue para o banco")
	void catalogoAprendeNoNascimento() {

		Catalogo catalogo = new Catalogo();
		List<ChaveDaPool> nascidas = new ArrayList<>();
		catalogo.aoNascer(nascidas::add);

		catalogo.aplicarInicializacao(INF_SEM_HOOK, 5L);

		assertEquals(1, nascidas.size());
		assertTrue(catalogo.chave(Eventos.GERENTE_INFINITY_CL, INF_SEM_HOOK.topicos().get(1)).isPresent());
	}

	static Log initializeV4SemHook() {
		return V4_SEM_HOOK;
	}

	private static Log log(String emissor, List<String> topicos, String dados) {

		List<byte[]> t = new ArrayList<>();
		topicos.forEach(topico -> t.add(HexFormat.of().parseHex(topico)));

		return new Log(HexFormat.of().parseHex(emissor), t, HexFormat.of().parseHex(dados));
	}
}