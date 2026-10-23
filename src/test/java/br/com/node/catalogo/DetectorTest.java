package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.transformacao.nucleo.Arbitragem;
import br.com.node.vo.Log;

class DetectorTest {

	private static final byte[] A = end("aa");
	private static final byte[] B = end("bb");
	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] BOT = end("b0");
	private static final byte[] USUARIO = end("05");
	private static final byte[] ROTEADOR = end("70");
	private static final byte[] PAR1 = end("01");
	private static final byte[] PAR2 = end("02");
	private static final byte[] GERENTE_V4 = end("04");

	private static final byte[] SWAP_V4 = Eventos
			.assinatura("Swap(bytes32,address,int128,int128,uint160,uint128,int24,uint24)");
	private static final byte[] SETTLE = Eventos.assinatura("Settle(address,uint256,uint256)");

	private final Detector detector = new Detector();

	@Test
	@DisplayName("ciclo V2 com empréstimo: o bot termina com A a mais e nada a menos")
	void cicloV2() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(B, PAR2, PAR1, 50)); 
		logs.add(transfer(A, PAR1, BOT, 100));
		logs.addAll(par(PAR1));
		logs.add(transfer(A, BOT, PAR2, 95)); 
		logs.addAll(par(PAR2));

		Arbitragem arb = detector.em(logs).orElseThrow();

		assertArrayEquals(A, arb.token());
		assertEquals(BigInteger.valueOf(5), arb.sobra());
		assertEquals(2, arb.pernas());
		assertEquals(2, arb.v2());
	}

	@Test
	@DisplayName("arb que passa pelo contrato único do V4: vista, com o gerente como pool")
	void cicloComV4() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(A, BOT, PAR1, 100));
		logs.add(transfer(B, PAR1, GERENTE_V4, 50));
		logs.addAll(par(PAR1));
		logs.add(log(GERENTE_V4, List.of(SWAP_V4, palavra(1)), new byte[64]));
		logs.add(transfer(A, GERENTE_V4, BOT, 103));

		Arbitragem arb = detector.em(logs).orElseThrow();

		assertEquals(BigInteger.valueOf(3), arb.sobra());
		assertEquals(2, arb.pernas());
		assertArrayEquals(PAR1, arb.pools().get(0));
		assertArrayEquals(GERENTE_V4, arb.pools().get(1));
	}

	@Test
	@DisplayName("swap comum: o usuário gasta A e recebe B — tem negativo, não é arbitragem")
	void swapComum() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(A, USUARIO, PAR1, 100));
		logs.add(transfer(B, PAR1, PAR2, 50));
		logs.addAll(par(PAR1));
		logs.add(transfer(A, PAR2, USUARIO, 0)); 
		logs.add(transfer(WBNB, PAR2, USUARIO, 10));
		logs.addAll(par(PAR2));

		assertTrue(detector.em(logs).isEmpty());
	}

	@Test
	@DisplayName("compra com BNB nativo: o token comprado nunca saiu do usuário, e a taxa do roteador também não")
	void compraComNativo() {

		List<Log> logs = new ArrayList<>();
		logs.add(log(WBNB, List.of(Eventos.assinatura("Deposit(address,uint256)"), palavra(ROTEADOR)), valor(10)));
		logs.add(transfer(WBNB, ROTEADOR, PAR1, 10));
		logs.add(transfer(A, PAR1, PAR2, 100));
		logs.addAll(par(PAR1));
		logs.add(transfer(B, PAR2, ROTEADOR, 50));
		logs.addAll(par(PAR2));
		logs.add(transfer(B, ROTEADOR, USUARIO, 49));

		assertTrue(detector.em(logs).isEmpty(), "o roteador fica com 1 B de taxa, que nunca saiu dele");
	}

	@Test
	@DisplayName("agregador com taxa na entrada: recebe 30 do usuário, repassa 29,91 — não é arbitragem")
	void taxaDeAgregadorNaEntrada() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(A, USUARIO, ROTEADOR, 30_000));
		logs.add(transfer(A, ROTEADOR, PAR1, 29_910));
		logs.add(transfer(B, PAR1, PAR2, 50));
		logs.addAll(par(PAR1));
		logs.add(transfer(WBNB, PAR2, USUARIO, 7));
		logs.addAll(par(PAR2));
		logs.add(log(ROTEADOR, List.of(SETTLE, palavra(1)), new byte[96]));

		assertTrue(detector.em(logs).isEmpty(), "os 90 de taxa vieram do usuário, não de uma pool");
	}

	@Test
	@DisplayName("WBNB desembrulhado NO MEIO do ciclo para pagar o V4 em nativo é gasto, não lucro")
	void desembrulhoSaiDaConta() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(WBNB, PAR1, BOT, 606)); 
		logs.add(log(WBNB, List.of(Eventos.assinatura("Withdrawal(address,uint256)"), palavra(BOT)), valor(606)));
		logs.add(log(GERENTE_V4, List.of(SWAP_V4, palavra(1)), new byte[64])); 
		logs.add(transfer(A, GERENTE_V4, BOT, 472));
		logs.add(transfer(A, BOT, PAR2, 472));
		logs.add(transfer(WBNB, PAR2, BOT, 606));
		logs.addAll(par(PAR2));
		logs.add(transfer(WBNB, BOT, PAR1, 606)); 
		logs.addAll(par(PAR1));

		assertTrue(detector.em(logs).isEmpty(), "o WBNB desembrulhado antes de voltar tem de ser descontado");
	}

	@Test
	@DisplayName("desembrulhar o lucro NO FIM (para pagar o builder) não apaga a arbitragem")
	void desembrulhoNoFimEhLucro() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(WBNB, BOT, PAR1, 100));
		logs.add(transfer(B, PAR1, PAR2, 50));
		logs.addAll(par(PAR1));
		logs.add(transfer(WBNB, PAR2, BOT, 104));
		logs.addAll(par(PAR2));
		logs.add(log(WBNB, List.of(Eventos.assinatura("Withdrawal(address,uint256)"), palavra(BOT)), valor(4)));

		Arbitragem arb = detector.em(logs).orElseThrow();

		assertEquals(BigInteger.valueOf(4), arb.sobra());
	}

	@Test
	@DisplayName("pagamento que chega grande e sai com taxinha: retorno acima de 100% não é arbitragem")
	void pagamentoComTaxinha() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(A, PAR1, BOT, 422_700));
		logs.addAll(par(PAR1));
		logs.add(transfer(A, BOT, PAR2, 253));
		logs.addAll(par(PAR2));

		assertTrue(detector.em(logs).isEmpty());
	}

	@Test
	@DisplayName("cofre de liquidez se rebalanceando: fica com dois tokens a mais, não é ciclo")
	void rebalanceamentoDeCofre() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(WBNB, PAR1, BOT, 21_457));
		logs.add(transfer(A, PAR1, BOT, 151_135));
		logs.add(log(PAR1, List.of(SETTLE, palavra(1)), new byte[96]));
		logs.add(transfer(WBNB, BOT, PAR1, 19_849));
		logs.add(transfer(A, BOT, PAR1, 151_134));
		logs.add(log(PAR2, List.of(SETTLE, palavra(2)), new byte[96]));

		assertTrue(detector.em(logs).isEmpty());
	}

	@Test
	@DisplayName("o evento do próprio bot não conta como pool")
	void eventoDoBotNaoEhPool() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(A, BOT, PAR1, 100));
		logs.add(transfer(B, PAR1, PAR2, 50));
		logs.addAll(par(PAR1));
		logs.add(transfer(A, PAR2, BOT, 101));
		logs.addAll(par(PAR2));
		logs.add(log(BOT, List.of(SETTLE, palavra(1)), new byte[96]));

		Arbitragem arb = detector.em(logs).orElseThrow();

		assertEquals(2, arb.pernas());
	}

	@Test
	@DisplayName("uma pool só não é arbitragem (depósito e saque com juros num cofre, por exemplo)")
	void umaPoolSo() {

		List<Log> logs = new ArrayList<>();
		logs.add(transfer(A, BOT, PAR1, 100));
		logs.add(transfer(B, PAR1, BOT, 1));
		logs.add(transfer(B, BOT, PAR1, 1));
		logs.add(transfer(A, PAR1, BOT, 101));
		logs.addAll(par(PAR1));

		assertTrue(detector.em(logs).isEmpty());
	}

	private static List<Log> par(byte[] par) {
		return List.of(log(par, List.of(Eventos.SYNC_V2), new byte[64]),
				log(par, List.of(Eventos.SWAP_V2, palavra(1), palavra(2)), new byte[128]));
	}

	private static Log transfer(byte[] token, byte[] de, byte[] para, long quanto) {
		return log(token, List.of(Eventos.TRANSFER, palavra(de), palavra(para)), valor(quanto));
	}

	private static Log log(byte[] endereco, List<byte[]> topicos, byte[] dados) {
		return new Log(endereco, topicos, dados);
	}

	private static byte[] valor(long quanto) {
		byte[] v = new byte[32];
		byte[] b = BigInteger.valueOf(quanto).toByteArray();
		System.arraycopy(b, 0, v, 32 - b.length, b.length);
		return v;
	}

	private static byte[] palavra(byte[] endereco) {
		byte[] p = new byte[32];
		System.arraycopy(endereco, 0, p, 12, 20);
		return p;
	}

	private static byte[] palavra(long n) {
		return valor(n);
	}

	private static byte[] end(String byteRepetido) {
		return HexFormat.of().parseHex(byteRepetido.repeat(20));
	}
}