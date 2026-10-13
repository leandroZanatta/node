package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Quanto vai ao builder: o que falta para o lance chegar ao ALVO em wei por gás, limitado pelo excedente sobre
// o piso (pagar além deixaria a arb no prejuízo) e pelo teto. Os números da fenda de 5,03 USDT de 25/09
// (piso ~0,0168 USDT) e o topo de bloco medido em 27/09 (0,15 gwei).
class PagamentoAoBuilderTest {

	private static final BigInteger E18 = BigInteger.TEN.pow(18);
	private static final BigInteger LUCRO = new BigInteger("5028711399043538617");
	private static final BigInteger PISO = new BigInteger("16815600000000000");
	private static final BigInteger SEM_TETO = E18.multiply(BigInteger.valueOf(1_000));

	private static final BigInteger GWEI_015 = BigInteger.valueOf(150_000_000L);
	private static final BigInteger GWEI_005 = BigInteger.valueOf(50_000_000L);
	private static final BigInteger GAS = BigInteger.valueOf(372_000);

	@Test
	@DisplayName("o necessário é (alvo − 0,9 × preço do gás) × gás: 0,15 gwei a 0,05 → 0,105 gwei × 372k")
	void necessarioParaOAlvo() {
		assertEquals(BigInteger.valueOf(105_000_000L).multiply(GAS), Disparo.necessarioParaOAlvo(GWEI_015, GWEI_005, GAS));
	}

	@Test
	@DisplayName("gás que já passa do alvo não paga nada a mais")
	void gasJaBastaNaoPaga() {
		assertEquals(BigInteger.ZERO, Disparo.necessarioParaOAlvo(GWEI_005, GWEI_015, GAS));
	}

	@Test
	@DisplayName("paga o necessário quando cabe no excedente e no teto")
	void pagaONecessario() {
		BigInteger necessario = E18;
		assertEquals(necessario, Disparo.pagamentoAoBuilder(necessario, LUCRO, PISO, SEM_TETO));
	}

	@Test
	@DisplayName("nunca mais que o excedente sobre o piso")
	void limitadoPeloExcedente() {
		BigInteger lucro = PISO.add(BigInteger.valueOf(100));
		assertEquals(BigInteger.valueOf(100), Disparo.pagamentoAoBuilder(E18, lucro, PISO, SEM_TETO));
	}

	@Test
	@DisplayName("nunca mais que o teto")
	void limitadoPeloTeto() {
		assertEquals(E18, Disparo.pagamentoAoBuilder(LUCRO, LUCRO.add(PISO), PISO, E18));
	}

	@Test
	@DisplayName("lucro no piso ou abaixo, ou nada a pagar: não paga")
	void naoPaga() {
		assertEquals(BigInteger.ZERO, Disparo.pagamentoAoBuilder(E18, PISO, PISO, SEM_TETO));
		assertEquals(BigInteger.ZERO, Disparo.pagamentoAoBuilder(E18, PISO.subtract(BigInteger.ONE), PISO, SEM_TETO));
		assertEquals(BigInteger.ZERO, Disparo.pagamentoAoBuilder(BigInteger.ZERO, LUCRO, PISO, SEM_TETO));
	}
}
