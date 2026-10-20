package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class Lance48ClubTest {

	@Test
	void taxaDeGasEContribuicaoUsamPrecoEfetivoE90PorCento() {
		BigInteger taxa = Lance48Club.taxaGas(BigInteger.valueOf(21_000), BigInteger.valueOf(1_000_000_000L));

		assertEquals(new BigInteger("21000000000000"), taxa);
		assertEquals(new BigInteger("18900000000000"), Lance48Club.contribuicaoGas(taxa));
	}

	@Test
	void bnbDiretoValeIntegralmenteEPrecoGasDesconhecidoNaoViraZero() {
		assertEquals(new BigInteger("239"), Lance48Club.pontuacaoMinima(BigInteger.valueOf(100), BigInteger.valueOf(149)));
		assertNull(Lance48Club.taxaGas(BigInteger.valueOf(21_000), null));
		assertNull(Lance48Club.pontuacaoMinima(null, BigInteger.ZERO));
	}
}
