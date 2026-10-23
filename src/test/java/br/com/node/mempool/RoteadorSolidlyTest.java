package br.com.node.mempool;
import br.com.node.transformacao.mempool.*;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.vo.SwapPendente;

class RoteadorSolidlyTest {

	
	
	
	
	private static final String CALLDATA_VOLATILE = "f41766d8" +
	
			"0000000000000000000000000000000000000000000000000de0b6b3a7640000" +
			
			"0000000000000000000000000000000000000000000000000000000000000000" +
			
			"00000000000000000000000000000000000000000000000000000000000000a0" +
			
			"0000000000000000000000001111111111111111111111111111111111111111" +
			
			"00000000000000000000000000000000000000000000000000000000ffffffff" +
			
			"0000000000000000000000000000000000000000000000000000000000000001" +
			
			"000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c" +
			
			"00000000000000000000000055d398326f99059ff775485246999027b3197955" +
			
			"0000000000000000000000000000000000000000000000000000000000000000";

	
	
	private static final String CALLDATA_STABLE = "f41766d8"
			+ "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000000000000000000000000000000000000000000000a0"
			+ "0000000000000000000000001111111111111111111111111111111111111111"
			+ "00000000000000000000000000000000000000000000000000000000ffffffff"
			+ "0000000000000000000000000000000000000000000000000000000000000001"
			+ "000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"
			+ "00000000000000000000000055d398326f99059ff775485246999027b3197955" +
			
			"0000000000000000000000000000000000000000000000000000000000000001";

	@Test
	@DisplayName("decodifica rota volatile: caminho e pool CREATE2 corretos")
	void decodificaRotaVolatile() {

		Optional<SwapPendente> swap = Roteamento.decodificar(HexFormat.of().parseHex(CALLDATA_VOLATILE));

		assertTrue(swap.isPresent(), "deve decodificar");
		assertEquals(List.of("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", "55d398326f99059ff775485246999027b3197955"),
				swap.get().caminho().stream().map(HexFormat.of()::formatHex).toList());
		assertEquals(List.of("6be6a437a1172e6c220246ecb3a92a45af9f0cbc"),
				swap.get().pools().stream().map(HexFormat.of()::formatHex).toList());
		assertEquals("1000000000000000000", swap.get().entra().toString());
	}

	@Test
	@DisplayName("decodifica rota stable: pool CREATE2 com byte stable=1 no salt")
	void decodificaRotaStable() {

		Optional<SwapPendente> swap = Roteamento.decodificar(HexFormat.of().parseHex(CALLDATA_STABLE));

		assertTrue(swap.isPresent(), "deve decodificar");
		assertEquals(List.of("f048cf3eac3c17ad47ae1c4e37408040177a8554"),
				swap.get().pools().stream().map(HexFormat.of()::formatHex).toList());
	}

	@Test
	@DisplayName("calldata truncada não decodifica")
	void calldataTruncadaNaoDecodifica() {
		assertTrue(Roteamento.decodificar(HexFormat.of().parseHex(CALLDATA_VOLATILE.substring(0, 100))).isEmpty());
	}
}