package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigInteger;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// O placar da PRECISÃO, provado sem rede: é a conta que diz se a estimativa está certa, e o SINAL do
// desvio importa — positivo é gasto acima do estimado (o lado que estoura o limite), negativo é
// estimativa folgada (o lado que recusa fenda boa).
class MedidorDeGasTest {

	@Test
	@DisplayName("mediana de medido/estimado e o PIOR desvio, com as revertidas contadas")
	void placarDoDesvio() {

		MedidorDeGas medidor = new MedidorDeGas(null);

		medidor.registrar(BigInteger.valueOf(300_000), BigInteger.valueOf(330_000), true); // +10%
		medidor.registrar(BigInteger.valueOf(400_000), BigInteger.valueOf(360_000), false); // -10% (reverteu)
		medidor.registrar(BigInteger.valueOf(200_000), BigInteger.valueOf(230_000), true); // +15%

		Map<String, Object> resumo = medidor.resumo();

		assertEquals(3L, resumo.get("amostras"));
		assertEquals(1L, resumo.get("revertidas"));
		assertEquals("330000", resumo.get("medido_mediana"));
		assertEquals("300000", resumo.get("estimado_mediana"));
		assertEquals(10L, resumo.get("desvio_mediano_pct"));
		assertEquals(15L, resumo.get("desvio_pior_pct"));
	}

	@Test
	@DisplayName("sem amostra o resumo é só o contador — não inventa mediana")
	void semAmostra() {

		Map<String, Object> resumo = new MedidorDeGas(null).resumo();

		assertEquals(0L, resumo.get("amostras"));
		assertEquals(0L, resumo.get("sem_recibo"));
		assertFalse(resumo.containsKey("desvio_mediano_pct"));
	}
}
