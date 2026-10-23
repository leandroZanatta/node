package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MedidorDeGasTest {

	@Test
	void fatorSoApareceComAmostrasSuficientesEEMediana() {
		MedidorDeGas medidor = new MedidorDeGas(null);
		List<Integer> fatores = new ArrayList<>();
		medidor.aoCalibrar(fatores::add);

		
		
		for (int i = 0; i < 4; i++) {
			medidor.registrar(BigInteger.valueOf(100), BigInteger.valueOf(50), true);
		}
		assertTrue(fatores.isEmpty(), "com 4 amostras ainda não calibra");

		medidor.registrar(BigInteger.valueOf(100), BigInteger.valueOf(50), true);
		assertEquals(List.of(500), fatores);
	}

	@Test
	void medianaNaoSeDeixaPuxarPorUmOutlier() {
		MedidorDeGas medidor = new MedidorDeGas(null);
		List<Integer> fatores = new ArrayList<>();
		medidor.aoCalibrar(fatores::add);

		for (int i = 0; i < 4; i++) {
			medidor.registrar(BigInteger.valueOf(100), BigInteger.valueOf(100), true);
		}
		medidor.registrar(BigInteger.valueOf(100), BigInteger.valueOf(900), true);
		assertEquals(1000, fatores.get(fatores.size() - 1));
	}
}