package br.com.node.nucleo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArbitragemTest {

	private static final BigInteger UM_TOKEN = BigInteger.TEN.pow(18);

	
	private static final int TAXA = 2500;

	private final Avaliador avaliador = new Avaliador();

	@Test
	@DisplayName("a curva do V2 é a mesma função que a pool aplica, wei a wei")
	void curvaDoV2FechaExata() {

		PoolV2 pool = new PoolV2(mil(1_000), mil(620_000), TAXA);

		for (Direcao direcao : Direcao.values()) {

			Curva curva = pool.curva(direcao);

			for (int potencia = 15; potencia <= 22; potencia++) {

				BigInteger entrada = BigInteger.TEN.pow(potencia);

				assertEquals(pool.aplicar(direcao, entrada).saida(), curva.aplicar(entrada),
						"curva e pool discordam em " + direcao + " com " + entrada);
			}
		}
	}

	
	
	
	
	
	@Test
	@DisplayName("a curva do V3 acompanha a pool dentro de um wei de taxa")
	void curvaDoV3AcompanhaAPool() {

		int tick = 64_075;
		PoolV3 pool = new PoolV3(TickMath.paraSqrt(tick), BigInteger.TEN.pow(21), tick, TAXA, 50);

		for (Direcao direcao : Direcao.values()) {

			Curva curva = pool.curva(direcao);
			BigInteger teto = pool.maximoNoTick(direcao);

			assertTrue(teto.signum() > 0, "pool V3 sem espaço no próprio tick em " + direcao);

			for (int divisor : List.of(1_000_000, 10_000, 100, 2)) {

				BigInteger entrada = teto.divide(BigInteger.valueOf(divisor));

				if (entrada.signum() <= 0) {
					continue;
				}

				BigInteger daPool = pool.aplicar(direcao, entrada).saida();
				BigInteger daCurva = curva.aplicar(entrada);
				BigInteger erro = daPool.subtract(daCurva).abs();

				
				
				assertTrue(erro.multiply(BigInteger.valueOf(1_000_000)).compareTo(daPool) <= 0,
						"curva V3 erra " + erro + " de " + daPool + " em " + direcao);
			}
		}
	}

	@Test
	@DisplayName("a entrada ótima é o máximo do lucro, e o lucro cai dos dois lados dela")
	void otimoEhOMaximo() {

		List<Travessia> ciclo = cicloComDesequilibrio();

		Optional<Oportunidade> melhor = avaliador.melhor(ciclo);

		assertTrue(melhor.isPresent(), "desequilíbrio de 3,3% contra 0,5% de taxa tinha de render");

		BigInteger otima = melhor.get().entrada();
		BigInteger lucro = melhor.get().lucro();

		System.out.println("ótimo: entrar " + emTokens(otima) + " para tirar " + emTokens(lucro) + " de lucro");

		assertTrue(lucro.signum() > 0, "lucro não positivo no ótimo: " + lucro);

		for (int porcento : List.of(50, 80, 90, 110, 150, 200)) {

			BigInteger outra = otima.multiply(BigInteger.valueOf(porcento)).divide(BigInteger.valueOf(100));
			BigInteger outroLucro = lucroEm(ciclo, outra);

			System.out.println("   a " + porcento + "% do ótimo: " + emTokens(outroLucro));

			assertTrue(outroLucro.compareTo(lucro) <= 0,
					"entrar " + porcento + "% do ótimo rendeu MAIS (" + outroLucro + " contra " + lucro + ")");
		}
	}

	@Test
	@DisplayName("ciclo sem desequilíbrio não rende, e o avaliador diz isso em vez de forçar um número")
	void semDesequilibrioNaoRende() {

		PoolV2 uma = new PoolV2(mil(1_000), mil(600_000), TAXA);
		PoolV2 outra = new PoolV2(mil(2_000), mil(1_200_000), TAXA);

		List<Travessia> ciclo = List.of(new Travessia(uma, Direcao.ZERO_PARA_UM),
				new Travessia(outra, Direcao.UM_PARA_ZERO));

		Curva curva = uma.curva(Direcao.ZERO_PARA_UM).compor(outra.curva(Direcao.UM_PARA_ZERO));

		assertTrue(!curva.rende(), "duas pools no MESMO preço não podem render: só as taxas sobram");
		assertTrue(avaliador.melhor(ciclo).isEmpty(), "avaliador achou oportunidade onde só há taxa a pagar");
	}

	
	
	
	private List<Travessia> cicloComDesequilibrio() {

		PoolV2 caro = new PoolV2(mil(1_000), mil(620_000), TAXA);
		PoolV2 barato = new PoolV2(mil(1_000), mil(600_000), TAXA);

		return List.of(new Travessia(caro, Direcao.ZERO_PARA_UM), new Travessia(barato, Direcao.UM_PARA_ZERO));
	}

	private BigInteger lucroEm(List<Travessia> ciclo, BigInteger entrada) {

		BigInteger carregando = entrada;

		for (Travessia travessia : ciclo) {
			carregando = travessia.aplicar(carregando).saida();
		}

		return carregando.subtract(entrada);
	}

	private static BigInteger mil(long quantos) {
		return BigInteger.valueOf(quantos).multiply(UM_TOKEN);
	}

	private static String emTokens(BigInteger wei) {
		return new java.math.BigDecimal(wei).divide(new java.math.BigDecimal(UM_TOKEN)).toPlainString();
	}
}