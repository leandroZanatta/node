package br.com.node.nucleo;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TravessiaComImpostoTest {

	private static final int TAXA = 2500;

	@Test
	@DisplayName("a perna taxada entrega ao par entrada − venda e a nós saída − compra, arredondando contra nós")
	void oImpostoSaiDosDoisLados() {

		PoolV2 pool = new PoolV2(mil(1_000), mil(600_000), TAXA);
		BigInteger entrada = mil(1);

		BigInteger semImposto = pool.aplicar(Direcao.ZERO_PARA_UM, entrada.subtract(entrada
				.multiply(BigInteger.valueOf(500)).add(BigInteger.valueOf(9_999)).divide(BigInteger.valueOf(10_000))))
				.saida();
		BigInteger esperado = semImposto.subtract(semImposto.multiply(BigInteger.valueOf(300))
				.add(BigInteger.valueOf(9_999)).divide(BigInteger.valueOf(10_000)));

		Passo passo = new Travessia(pool, Direcao.ZERO_PARA_UM, 500, 300).aplicar(entrada);

		assertEquals(esperado, passo.saida(), "a saída taxada não é (pool sobre entrada−venda) − compra");
	}

	@Test
	@DisplayName("a curva com imposto acompanha o caminho exato (é a mesma forma, reescalada)")
	void aCurvaAcompanhaOCaminho() {

		PoolV2 pool = new PoolV2(mil(1_000), mil(600_000), TAXA);
		Travessia taxada = new Travessia(pool, Direcao.ZERO_PARA_UM, 500, 300);

		for (BigInteger entrada : List.of(mil(1), mil(10), mil(100))) {
			BigInteger exato = taxada.aplicar(entrada).saida();
			BigInteger curva = taxada.curva().aplicar(entrada);
			BigInteger erro = exato.subtract(curva).abs();
			assertTrue(erro.multiply(BigInteger.valueOf(1_000_000)).compareTo(exato) <= 0,
					"curva taxada erra " + erro + " de " + exato + " em " + entrada);
		}
	}

	@Test
	@DisplayName("ciclo pela bPay: com 3% de compra, rende se a divergência passa do imposto, e não rende se não passa")
	void cicloTipoBpay() {

		Avaliador avaliador = new Avaliador();

		
		
		PoolV2 compraTaxada = new PoolV2(mil(1_000), mil(100_000_000), TAXA);

		PoolV2 divergente8 = new PoolV2(mil(100_000_000), mil(1_080), TAXA);
		Optional<Oportunidade> rende = avaliador
				.melhor(List.of(new Travessia(compraTaxada, Direcao.ZERO_PARA_UM, 0, 300),
						new Travessia(divergente8, Direcao.ZERO_PARA_UM)));
		assertTrue(rende.isPresent(), "8% de divergência contra 3% de imposto + taxas tinha de render");

		PoolV2 divergente2 = new PoolV2(mil(100_000_000), mil(1_020), TAXA);
		Optional<Oportunidade> naoRende = avaliador
				.melhor(List.of(new Travessia(compraTaxada, Direcao.ZERO_PARA_UM, 0, 300),
						new Travessia(divergente2, Direcao.ZERO_PARA_UM)));
		assertTrue(naoRende.isEmpty(), "2% de divergência não paga 3% de imposto — o avaliador inventou lucro");

		Optional<Oportunidade> semImposto = avaliador.melhor(List.of(new Travessia(compraTaxada, Direcao.ZERO_PARA_UM),
				new Travessia(divergente2, Direcao.ZERO_PARA_UM)));
		assertTrue(semImposto.isPresent(), "o mesmo ciclo SEM imposto rende — é o imposto que o derruba");
	}

	private static BigInteger mil(long tokens) {
		return BigInteger.valueOf(tokens).multiply(BigInteger.TEN.pow(18));
	}
}