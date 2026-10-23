package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.nucleo.ReservasV2;
import br.com.node.vo.Log;

class CatalogoTest {

	private static final byte[] POOL = HexFormat.of().parseHex("11".repeat(20));
	private static final BigInteger TETO_UINT112 = BigInteger.TWO.pow(112).subtract(BigInteger.ONE);

	@Test
	@DisplayName("reserva dentro do uint112 entra, e o estado ANTERIOR volta")
	void reservaBoaEntra() {

		Catalogo catalogo = new Catalogo();

		assertTrue(catalogo.aplicarSync(sync(BigInteger.TEN, BigInteger.TEN), 1L).isEmpty(),
				"a primeira vez não tem anterior");

		Optional<ReservasV2> antes = catalogo.aplicarSync(sync(BigInteger.valueOf(20), BigInteger.TEN), 2L);

		assertTrue(antes.isPresent());
		assertEquals(BigInteger.TEN, antes.get().reserva0());
		assertEquals(2L, catalogo.reservas(POOL).orElseThrow().altura());
		assertEquals(0, catalogo.poolsDescartadas());
	}

	@Test
	@DisplayName("reserva acima do uint112 é DESCARTADA e contada — o par estouraria em OVERFLOW")
	void reservaAcimaDoTetoEDescartada() {

		Catalogo catalogo = new Catalogo();

		assertTrue(catalogo.aplicarSync(sync(TETO_UINT112.add(BigInteger.ONE), BigInteger.TEN), 1L).isEmpty());
		assertTrue(catalogo.reservas(POOL).isEmpty(), "estado impossível não vira reserva");
		assertEquals(1, catalogo.poolsDescartadas(), "a recusa tem de CONTAR, não sumir");
	}

	@Test
	@DisplayName("reserva ZERO entra: o par emite Sync(0,0) quando a liquidez sai, e isso é fato")
	void reservaZeroEntra() {

		Catalogo catalogo = new Catalogo();

		assertTrue(catalogo.aplicarSync(sync(BigInteger.ZERO, BigInteger.ZERO), 1L).isEmpty());

		
		
		
		assertEquals(BigInteger.ZERO, catalogo.reservas(POOL).orElseThrow().reserva0());
		assertEquals(0, catalogo.poolsDescartadas(), "zero não é log inventado — não se descarta");
	}

	@Test
	@DisplayName("o zero RETIRA o estado velho: a pool que esvaziou não fica com a reserva antiga")
	void zeroSobrescreveOReservaAntiga() {

		Catalogo catalogo = new Catalogo();

		catalogo.aplicarSync(sync(BigInteger.valueOf(1_000), BigInteger.valueOf(2_000)), 1L);

		Optional<ReservasV2> antes = catalogo.aplicarSync(sync(BigInteger.ZERO, BigInteger.valueOf(2_000)), 2L);

		assertTrue(antes.isPresent(), "o estado anterior volta para quem quiser comparar");
		assertEquals(BigInteger.valueOf(1_000), antes.get().reserva0());
		assertEquals(BigInteger.ZERO, catalogo.reservas(POOL).orElseThrow().reserva0(),
				"a liquidez saiu; ficar com o valor antigo seria inventar fenda");
	}

	@Test
	@DisplayName("payload curto (palavra faltando) não vira Sync de dois uint112")
	void payloadCurtoNaoEntra() {

		Catalogo catalogo = new Catalogo();

		catalogo.aplicarSync(new Log(POOL, List.of(), new byte[32]), 1L);

		assertTrue(catalogo.reservas(POOL).isEmpty(), "palavra ausente lida como ZERO não é reserva");
		assertEquals(1, catalogo.poolsDescartadas());
	}

	
	private static Log sync(BigInteger r0, BigInteger r1) {

		byte[] dados = new byte[64];
		byte[] b0 = r0.toByteArray();
		byte[] b1 = r1.toByteArray();

		System.arraycopy(b0, 0, dados, 32 - b0.length, b0.length);
		System.arraycopy(b1, 0, dados, 64 - b1.length, b1.length);

		return new Log(POOL, List.of(), dados);
	}
}