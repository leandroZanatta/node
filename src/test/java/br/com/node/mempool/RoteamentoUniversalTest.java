package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// O decodificador do Universal Router, contra calldata REAL da BSC: um execute(bytes,bytes[]) com
// PERMIT2 (0x0a) + V3_SWAP_EXACT_IN (0x00) vendendo 10000 do token 0xd270…ffff por USDT numa pool V3
// de fee 2500 — justo o fluxo que abre as arbs que os bots vencem. Decodificar errado projeta fenda
// falsa, então se prova contra o real, como o RoteamentoTest.
class RoteamentoUniversalTest {

	private static final String CALLDATA = "24856bc30000000000000000000000000000000000000000000000000000000000000040000000000000000000000000000000000000000000000000000000000000008000000000000000000000000000000000000000000000000000000000000000020a000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000001c00000000000000000000000000000000000000000000000000000000000000160000000000000000000000000d270d4e1ec6e6e0d28c0ecb8be966ec75997ffff00000000000000000000000000000000000000000000021e19e0c9bab2400000000000000000000000000000000000000000000000000000000000006ab1636b00000000000000000000000000000000000000000000000000000000000000f1000000000000000000000000d9c500dff816a1da21a48a732d3498bf09dc9aeb000000000000000000000000000000000000000000000000000000006ab1636b00000000000000000000000000000000000000000000000000000000000000e000000000000000000000000000000000000000000000000000000000000000414a109da2684d7bc5040ab48d15ff1104fd5c8083c773e6bc11f9c47bd31b39c47bf1f0b35d449ab934ef037ab3a54588125ff5d8f10190631d9c986b232c52a61b000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000000000000000e3581b98a72583a3556451b3e5281d971c32aecf00000000000000000000000000000000000000000000021e19e0c9bab2400000000000000000000000000000000000000000000000000004fa667a01cd35a81400000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000001000000000000000000000000000000000000000000000000000000000000002bd270d4e1ec6e6e0d28c0ecb8be966ec75997ffff0009c455d398326f99059ff775485246999027b3197955000000000000000000000000000000000000000000";

	private static final byte[] D270 = hex("d270d4e1ec6e6e0d28c0ecb8be966ec75997ffff");
	private static final byte[] USDT = hex("55d398326f99059ff775485246999027b3197955");

	@Test
	@DisplayName("decodifica o V3_SWAP_EXACT_IN do Universal Router: entrada, tokens e fee do path")
	void decodificaUniversalV3() {

		Optional<RoteamentoUniversal.SwapV3> swap = RoteamentoUniversal.decodificar(hex(CALLDATA));

		assertTrue(swap.isPresent(), "não decodificou o execute com V3_SWAP_EXACT_IN");
		assertEquals(new BigInteger("10000000000000000000000"), swap.get().entra(), "amountIn errado");
		assertEquals(2, swap.get().tokens().size(), "path de 1 hop tem 2 tokens");
		assertEquals(hex(D270), hex(swap.get().tokens().get(0)), "token de entrada errado");
		assertEquals(hex(USDT), hex(swap.get().tokens().get(1)), "token de saída errado");
		assertEquals(1, swap.get().fees().size(), "1 hop tem 1 fee");
		assertEquals(2500, swap.get().fees().get(0), "fee do path (0x0009c4 = 2500) errada");
	}

	// execute(bytes,bytes[],uint256) com comando 0x08 (V2_SWAP_EXACT_IN):
	// recipient=0x01, amountIn=1000, amountOutMin=900, path=[WBNB, USDT], payerIsUser=false
	private static final String CALLDATA_V2 = "3593564c"
			+ "0000000000000000000000000000000000000000000000000000000000000060"  // off_commands = 96
			+ "00000000000000000000000000000000000000000000000000000000000000a0"  // off_inputs = 160
			+ "000000000000000000000000000000000000000000000000000000000001869f"  // deadline
			+ "0000000000000000000000000000000000000000000000000000000000000001"  // commands length = 1
			+ "0800000000000000000000000000000000000000000000000000000000000000"  // command 0x08
			+ "0000000000000000000000000000000000000000000000000000000000000001"  // inputs count = 1
			+ "0000000000000000000000000000000000000000000000000000000000000020"  // offset to elem 0
			+ "0000000000000000000000000000000000000000000000000000000000000100"  // elem 0 length = 256
			+ "0000000000000000000000000000000000000000000000000000000000000001"  // recipient
			+ "00000000000000000000000000000000000000000000000000000000000003e8"  // amountIn = 1000
			+ "0000000000000000000000000000000000000000000000000000000000000384"  // amountOutMin = 900
			+ "00000000000000000000000000000000000000000000000000000000000000a0"  // offset to path = 5*32
			+ "0000000000000000000000000000000000000000000000000000000000000000"  // payerIsUser = false
			+ "0000000000000000000000000000000000000000000000000000000000000002"  // path count = 2
			+ "000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"  // WBNB
			+ "00000000000000000000000055d398326f99059ff775485246999027b3197955"; // USDT

	@Test
	@DisplayName("decodifica V2_SWAP_EXACT_IN do Universal Router: amountIn e caminho de tokens")
	void decodificaUniversalV2() {

		byte[] calldata = hex(CALLDATA_V2);

		assertTrue(RoteamentoUniversal.decodificar(calldata).isEmpty(), "V3 decode deve dar vazio para comando 0x08");

		Optional<br.com.node.vo.SwapPendente> swap = RoteamentoUniversal.decodificarComoV2(calldata);

		assertTrue(swap.isPresent(), "decodificarComoV2 deve reconhecer comando 0x08");
		assertEquals(BigInteger.valueOf(1000), swap.get().entra(), "amountIn errado");
		assertEquals(2, swap.get().caminho().size(), "path de 1 hop tem 2 tokens");
		assertEquals("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", hex(swap.get().caminho().get(0)), "token de entrada errado");
		assertEquals("55d398326f99059ff775485246999027b3197955", hex(swap.get().caminho().get(1)), "token de saída errado");
	}

	@Test
	@DisplayName("recusa o que não é Universal Router e não estoura com calldata torta")
	void recusaOQueNaoEntende() {

		assertTrue(RoteamentoUniversal.decodificar(new byte[0]).isEmpty(), "vazio dá vazio");
		assertTrue(RoteamentoUniversal.decodificar(hex("38ed1739")).isEmpty(), "outro seletor dá vazio");
		assertTrue(RoteamentoUniversal.decodificar(hex("24856bc3deadbeef")).isEmpty(), "truncado dá vazio, não exceção");
	}

	private static byte[] hex(String s) {
		return HexFormat.of().parseHex(s);
	}

	private static String hex(byte[] b) {
		return HexFormat.of().formatHex(b);
	}
}
