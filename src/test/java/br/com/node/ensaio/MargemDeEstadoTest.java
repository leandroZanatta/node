package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class MargemDeEstadoTest {

	private static final BigInteger LUCRO = BigInteger.valueOf(1_000_000L);
	private static final BigInteger PISO = BigInteger.valueOf(200_000L);

	@Test
	void margemZeroDeixaOLucroInteiroParaOLance() {
		assertEquals(LUCRO, Disparo.lucroParaLance(LUCRO, PISO, 0));
	}

	@Test
	void margemTiraSoOPercentualDoExcedenteNaoDoLucro() {
		// excedente = 1.000.000 − 200.000 = 800.000; 10% dele (80.000) fica de folga; o lance enxerga 920.000.
		assertEquals(BigInteger.valueOf(920_000L), Disparo.lucroParaLance(LUCRO, PISO, 100));
	}

	@Test
	void lucroAbaixoDoPisoNaoGeraExcedente() {
		assertEquals(PISO, Disparo.lucroParaLance(BigInteger.valueOf(50_000L), PISO, 300));
	}

	@Test
	void margemForaDoIntervaloEErro() {
		assertThrows(IllegalStateException.class, () -> Disparo.lucroParaLance(LUCRO, PISO, 901));
		assertThrows(IllegalStateException.class, () -> Disparo.lucroParaLance(LUCRO, PISO, -1));
	}
}
