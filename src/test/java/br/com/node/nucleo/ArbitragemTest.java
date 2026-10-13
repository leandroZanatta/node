package br.com.node.nucleo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// A aritmética de arbitragem, medida contra a aritmética dos contratos.
//
// Este teste não precisa de rede, e não é por conveniência: a cadeia viva quase nunca tem
// oportunidade aberta — medido, a maior arbitragem executada em 48 blocos rendia 19 vezes
// MENOS que o próprio gas. Um teste que só olhasse a cadeia não teria como provar que o
// ótimo é ótimo, porque não haveria caso em que ele aparece. Então o desequilíbrio é
// construído, com números do tamanho dos reais, e as perguntas são as três que decidem se
// dá para arbitrar:
//
//   1. A `Curva` é a MESMA função que a pool aplica? (para V2, wei a wei)
//   2. `entradaOtima` é de fato o máximo do lucro?
//   3. O `Avaliador` recusa o que não rende, em vez de forçar um número?
class ArbitragemTest {

	private static final BigInteger UM_TOKEN = BigInteger.TEN.pow(18);

	// 0,25% — a taxa da Pancake V2, que é a maioria do livro na BSC.
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

	// O V3 não fecha wei a wei e isso é esperado: o contrato arredonda a TAXA para cima e a
	// curva a trata como linear. O que não pode é a diferença ser relevante — se fosse, o
	// tamanho ótimo sairia do lugar.
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

				// Um milionésimo da saída é folga generosa para um wei de taxa; medir o erro
				// relativo é o que impede a asserção de passar por acaso em pool pequena.
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

	// Mesmo par, preços diferentes: 620 numa, 600 na outra. Vende-se onde o token vale mais
	// e recompra-se onde vale menos — 3,3% de fenda contra 0,5% de taxa somada.
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
