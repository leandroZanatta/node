package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.sincronizacao.VarredorDeGerentes;

// A varredura contra o RPC de logs de VERDADE, numa faixa de 21 blocos em volta do nascimento de uma pool
// conhecida (a V4 sem hook dos pares de swap). Sem `RPC_LOGS` o teste não roda: não há outro jeito de
// pedir `eth_getLogs` à BSC (o RPC público recusa com "limit exceeded").
class VarredorDeGerentesTest {

	private static final long NASCEU = 123_756_199L;

	@Test
	@DisplayName("a faixa em volta do nascimento traz a pool, com a mesma chave que o log dela dá")
	void achaAPoolNaFaixa() {

		assumeTrue(VarredorDeGerentes.urlDoAmbiente().isPresent(), "sem RPC_LOGS");

		ChaveDaPool esperada = ChaveDaPool.doInitialize(ChaveDaPoolTest.initializeV4SemHook(), NASCEU).orElseThrow();

		try (VarredorDeGerentes varredor = new VarredorDeGerentes(VarredorDeGerentes.urlDoAmbiente().get(), null,
				() -> {
				})) {

			List<ChaveDaPool> achadas = varredor.faixa(Eventos.GERENTE_V4, NASCEU - 10, NASCEU + 10);
			ChaveDaPool achada = achadas.stream()
					.filter(chave -> java.util.Arrays.equals(chave.poolId(), esperada.poolId())).findFirst()
					.orElseThrow(() -> new AssertionError("a pool não veio na faixa: " + achadas.size() + " outras"));

			assertArrayEquals(esperada.token0(), achada.token0());
			assertArrayEquals(esperada.token1(), achada.token1());
			assertEquals(esperada.taxaLp(), achada.taxaLp());
			assertEquals(esperada.espacamento(), achada.espacamento());
			assertArrayEquals(esperada.hooks(), achada.hooks());
			assertEquals(NASCEU, achada.altura(), "a altura é a do bloco do log");
		}
	}
}
