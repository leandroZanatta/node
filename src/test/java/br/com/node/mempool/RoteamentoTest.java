package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.vo.SwapPendente;

// O decodificador de swap pendente, contra calldata REAL da cadeia — não inventada.
//
// A calldata abaixo é da transação 0x6d171372… (bloco 122.067.730), um
// swapExactTokensForTokensSupportingFeeOnTransferTokens do roteador da PancakeSwap. Os valores
// esperados foram lidos da própria transação: 56,910872 tokens de entrada, caminho USDT -> 0xeb2c…b90.
// Decodificar errado projeta fenda que não existe, então o instrumento se prova contra o real antes de
// alimentar qualquer decisão — a mesma disciplina da conferência e da sonda.
class RoteamentoTest {

	private static final String CALLDATA_REAL = "0x5c11d79500000000000000000000000000000000000000000000000315cc088ea9b980000000000000000000000000000000000000000000041bfaa4ca47ee53212ba25a00000000000000000000000000000000000000000000000000000000000000a000000000000000000000000051728200771b45aff5f1f191a043fc28f7769a4a000000000000000000000000000000000000000000000000000001a0a6154079000000000000000000000000000000000000000000000000000000000000000200000000000000000000000055d398326f99059ff775485246999027b3197955000000000000000000000000eb2c8c10910c7fe129a4c23b7487113266552b90";

	private static final byte[] USDT = hex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] ALVO = hex("eb2c8c10910c7fe129a4c23b7487113266552b90");
	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	@Test
	@DisplayName("decodifica a intenção de um swap real: entrada e caminho batem com a transação")
	void decodificaSwapReal() {

		Optional<SwapPendente> pendente = Roteamento.decodificar(HexFormat.of().parseHex(semPrefixo(CALLDATA_REAL)));

		assertTrue(pendente.isPresent(), "não decodificou um swap de roteador conhecido");

		SwapPendente swap = pendente.get();

		assertEquals(new BigInteger("56910872000000000000"), swap.entra(), "a entrada não bate com a transação");
		assertEquals(2, swap.caminho().size(), "o caminho tinha duas pernas");
		assertEquals(HexFormat.of().formatHex(USDT), HexFormat.of().formatHex(swap.caminho().get(0)), "token de entrada errado");
		assertEquals(HexFormat.of().formatHex(ALVO), HexFormat.of().formatHex(swap.caminho().get(1)), "token de saída errado");
	}

	@Test
	@DisplayName("ETH-in: amountIn vem do value da tx, e o path começa no word 1 (não no 2)")
	void decodificaEthIn() {

		// swapExactETHForTokens(amountOutMin=0, path offset=0x80, to, deadline, [len=2, WBNB, ALVO]).
		String calldata = "7ff36ab5" + palavra("0") + palavra("80") + palavra("11".repeat(20)) + palavra("0")
				+ palavra("2") + palavraEndereco(WBNB) + palavraEndereco(ALVO);

		BigInteger valor = new BigInteger("1000000000000000000"); // 1 BNB no `value`

		Optional<SwapPendente> pendente = Roteamento.decodificar(hex(calldata), valor);

		assertTrue(pendente.isPresent(), "não decodificou um swapExactETHForTokens");
		assertEquals(valor, pendente.get().entra(), "a entrada ETH-in tem de vir do value da tx");
		assertEquals(HexFormat.of().formatHex(WBNB), HexFormat.of().formatHex(pendente.get().caminho().get(0)),
				"path[0] de um ETH-in é o WBNB");
		assertEquals(HexFormat.of().formatHex(ALVO), HexFormat.of().formatHex(pendente.get().caminho().get(1)),
				"token de saída errado");

		assertTrue(Roteamento.decodificar(hex(calldata), null).isEmpty(), "sem value, um ETH-in não decodifica");
	}

	@Test
	@DisplayName("recusa o que não é swap de entrada exata, e não estoura com calldata torta")
	void recusaOQueNaoEntende() {

		assertTrue(Roteamento.decodificar(new byte[0]).isEmpty(), "calldata vazia devia dar vazio");
		assertTrue(Roteamento.decodificar(hex("a9059cbb")).isEmpty(), "um transfer() não é swap de roteador");
		assertTrue(Roteamento.decodificar(hex("38ed1739deadbeef")).isEmpty(), "seletor certo mas calldata truncada devia dar vazio, não exceção");
	}

	private static byte[] hex(String s) {
		return HexFormat.of().parseHex(s);
	}

	// Uma palavra ABI de 32 bytes com o hex dado alinhado à direita.
	private static String palavra(String hex) {
		return "0".repeat(64 - hex.length()) + hex;
	}

	private static String palavraEndereco(byte[] endereco) {
		return palavra(HexFormat.of().formatHex(endereco));
	}

	private static String semPrefixo(String s) {
		return s.startsWith("0x") ? s.substring(2) : s;
	}
}
