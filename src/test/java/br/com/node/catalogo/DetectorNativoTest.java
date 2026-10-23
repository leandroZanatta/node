package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.transformacao.nucleo.Arbitragem;
import br.com.node.vo.Log;

class DetectorNativoTest {

	private static final BigInteger E18 = BigInteger.TEN.pow(18);
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] COFRE = HexFormat.of().parseHex("238a358808379702088667322f80ac48bad5e6c4");
	private static final byte[] EXECUTOR = HexFormat.of().parseHex("ee".repeat(20));
	private static final byte[] PAR = HexFormat.of().parseHex("01".repeat(20));
	private static final byte[] POOL_ID = HexFormat.of().parseHex("d3".repeat(32));

	private static final byte[] TOKEN_A = HexFormat.of().parseHex("aa".repeat(20));
	private static final byte[] PAR_A = HexFormat.of().parseHex("0a".repeat(20));
	private static final byte[] PAR_USDT = HexFormat.of().parseHex("0b".repeat(20));

	@Test
	@DisplayName("a forma do caso real: parte de 200 WBNB, paga 100 BNB NATIVO no meio, volta com 300,001 WBNB — lucro 0,001")
	void nativoEntraNaConta() {

		BigInteger um = BigInteger.valueOf(200).multiply(E18);
		BigInteger nativo = BigInteger.valueOf(100).multiply(E18);
		BigInteger a = BigInteger.valueOf(500).multiply(E18);
		BigInteger usdt1 = BigInteger.valueOf(600).multiply(E18);
		BigInteger usdt2 = BigInteger.valueOf(60_000).multiply(E18);
		BigInteger voltou = new BigInteger("300001000000000000000");

		List<Log> logs = List.of(transfer(WBNB, EXECUTOR, PAR_A, um), transfer(TOKEN_A, PAR_A, EXECUTOR, a),
				sync(PAR_A), transfer(TOKEN_A, EXECUTOR, PAR_USDT, a), transfer(USDT, PAR_USDT, EXECUTOR, usdt1),
				sync(PAR_USDT),
				
				
				swapInfinity(nativo.negate(), usdt2), transfer(USDT, COFRE, EXECUTOR, usdt2),
				transfer(USDT, EXECUTOR, PAR, usdt1.add(usdt2)), transfer(WBNB, PAR, EXECUTOR, voltou), sync(PAR));

		
		
		Arbitragem semNativo = new Detector().em(logs).orElseThrow();
		assertEquals(voltou.subtract(um), semNativo.sobra(), "o artefato: 100,001 de lucro");

		Arbitragem comNativo = new Detector(pool -> OptionalInt.of(0)).em(logs).orElseThrow();
		assertArrayEquals(WBNB, comNativo.token());
		assertEquals(new BigInteger("1000000000000000"), comNativo.sobra(), "0,001 WBNB");
	}

	private static Log swapInfinity(BigInteger quantia0, BigInteger quantia1) {

		byte[] dados = new byte[7 * 32];
		palavraComSinal(dados, 0, quantia0);
		palavraComSinal(dados, 1, quantia1);
		dados[2 * 32 + 31] = 1; 
		dados[3 * 32 + 31] = 1;

		return new Log(Eventos.GERENTE_INFINITY_CL, List.of(Eventos.SWAP_INFINITY_CL, POOL_ID, palavra(EXECUTOR)),
				dados);
	}

	private static Log transfer(byte[] token, byte[] de, byte[] para, BigInteger valor) {
		byte[] dados = new byte[32];
		palavraComSinal(dados, 0, valor);
		return new Log(token, List.of(Eventos.TRANSFER, palavra(de), palavra(para)), dados);
	}

	private static Log sync(byte[] par) {
		return new Log(par, List.of(Eventos.SYNC_V2), new byte[64]);
	}

	private static byte[] palavra(byte[] endereco) {
		byte[] p = new byte[32];
		System.arraycopy(endereco, 0, p, 12, 20);
		return p;
	}

	private static void palavraComSinal(byte[] destino, int indice, BigInteger valor) {
		byte[] cru = valor.toByteArray();
		byte preenche = (byte) (valor.signum() < 0 ? 0xff : 0);
		for (int i = 0; i < 32; i++) {
			destino[indice * 32 + i] = preenche;
		}
		System.arraycopy(cru, Math.max(0, cru.length - 32), destino, indice * 32 + 32 - Math.min(32, cru.length),
				Math.min(32, cru.length));
	}
}