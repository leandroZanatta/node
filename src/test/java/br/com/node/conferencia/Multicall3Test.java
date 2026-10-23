package br.com.node.conferencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Multicall3Test {

	
	
	private static final String PAR_V2 = "0x16b9a82891338f9ba80e2d6970fdda79d1eb0dae";
	private static final String POOL_V3 = "0x47a90a2d92a8367a91efa1906bfc8c1e05bf10c4";

	@Test
	@DisplayName("o caminho agregado lê o mesmo que a chamada direta, na mesma altura, inclusive a falha")
	void agregadoIgualAoDireto() throws InterruptedException {

		RpcDeConferencia rpc = new RpcDeConferencia();

		long altura = rpc.cabeca() - 10;

		List<Chamada> chamadas = List.of(new Chamada(PAR_V2, Abi.seletor("getReserves()")),
				new Chamada(PAR_V2, Abi.seletor("token0()")), new Chamada(PAR_V2, Abi.seletor("token1()")),
				new Chamada(PAR_V2, Abi.seletor("fee()")), new Chamada(POOL_V3, Abi.seletor("slot0()")),
				new Chamada(POOL_V3, Abi.seletor("liquidity()")), new Chamada(POOL_V3, Abi.seletor("fee()")),
				new Chamada(POOL_V3, Abi.seletor("tickSpacing()")));

		List<Optional<String>> diretas = rpc.chamar(chamadas, altura);
		List<Optional<String>> agregadas = rpc.chamarAgregado(chamadas, altura);

		System.out.println("altura " + altura);

		for (int i = 0; i < chamadas.size(); i++) {
			System.out.println("   " + chamadas.get(i).dados() + " em " + chamadas.get(i).para().substring(0, 10)
					+ "… direta "
					+ diretas.get(i).map(r -> r.substring(0, Math.min(26, r.length())) + "…").orElse("vazia")
					+ " · agregada "
					+ agregadas.get(i).map(r -> r.substring(0, Math.min(26, r.length())) + "…").orElse("vazia"));
		}

		assertTrue(diretas.get(0).isPresent(), "a chamada direta nem respondeu: o teste não compara nada");
		assertTrue(diretas.get(3).isEmpty(), "fee() numa pool V2 respondeu: o caso de falha não está sendo exercitado");
		assertEquals(diretas, agregadas, "o caminho agregado lê diferente da chamada direta");
	}
}