package br.com.node.mempool;
import br.com.node.transformacao.mempool.*;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.node.transformacao.nucleo.Par;
import br.com.node.vo.SwapPendente;

class RoteadorBStocksTest {

	private static final HexFormat HEX = HexFormat.of();

	
	
	
	
	
	
	
	private static final String CALLDATA = "42e3dcf3"
			+ "0000000000000000000000000000000000000000000000003668e5793394db10"
			+ "000000000000000000000000ee6f4bcc88c2c8583d5a65c7d0c877b464100711"
			+ "000000000000000000000000000000000000000000000005f7319e83bbb2bda4"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000000003da7d3c0c0f4741f91b89aeaeca36465cc6be6d"
			+ "000000000000000000000000000000000000000000000000000000006ad621a0";

	private static final byte[] HIMSB = hex("ee6f4bcc88c2c8583d5a65c7d0c877b464100711");
	private static final byte[] USDT = hex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] POOL = hex("03da7d3c0c0f4741f91b89aeaeca36465cc6be6d");

	private static final Par PAR_USDT_HIMSB = new Par(USDT, HIMSB);

	@Test
	void decodificaCalldata42e3dcf3() {
		byte[] calldata = HEX.parseHex(CALLDATA);
		Optional<SwapPendente> opt = RoteadorBStocks.decodificar(calldata, pool -> Optional.of(PAR_USDT_HIMSB));

		assertTrue(opt.isPresent(), "não decodificou 42e3dcf3");
		SwapPendente swap = opt.get();

		assertEquals(2, swap.caminho().size());
		assertArrayEquals(USDT, swap.caminho().get(0), "tokenIn deve ser USDT");
		assertArrayEquals(HIMSB, swap.caminho().get(1), "tokenOut deve ser HIMSB");

		assertEquals(1, swap.pools().size());
		assertArrayEquals(POOL, swap.pools().get(0), "pool incorreta");

		
		
		assertEquals(new BigInteger("05f7319e83bbb2bda4", 16), swap.entra());
	}

	@Test
	void falhaSePoolNaoEstaNoCatalogo() {
		byte[] calldata = HEX.parseHex(CALLDATA);
		Optional<SwapPendente> opt = RoteadorBStocks.decodificar(calldata, pool -> Optional.empty());
		assertTrue(opt.isEmpty(), "deve falhar se pool não está no catálogo");
	}

	@Test
	void falhaParaSeletorDesconhecido() {
		byte[] calldata = HEX.parseHex("27772d13" + "0".repeat(64 * 5));
		Optional<SwapPendente> opt = RoteadorBStocks.decodificar(calldata, pool -> Optional.of(PAR_USDT_HIMSB));
		assertTrue(opt.isEmpty(), "não deve decodificar outro seletor");
	}

	@Test
	void falhaParaCalldataCurta() {
		Optional<SwapPendente> opt = RoteadorBStocks.decodificar(new byte[10], pool -> Optional.of(PAR_USDT_HIMSB));
		assertTrue(opt.isEmpty());
	}

	
	
	private static final String CALLDATA_SELL = "a00597a0"
			+ "00000000000000000000000000000000000000000000004719e98f65f0e97f1e"
			+ "0000000000000000000000000000000000000006fbd47b87de87c79e538cf0d4"
			+ "000000000000000000000000eccbb861c0dda7efd964010085488b69317e4444"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000000027b5c411a43dea7ca7e60632ea73fd9e74ed06a8"
			+ "000000000000000000000000000000000000000000000000000000006ad6a054";

	private static final byte[] STOCK_ECCBB = hex("eccbb861c0dda7efd964010085488b69317e4444");
	private static final byte[] POOL_27B5 = hex("27b5c411a43dea7ca7e60632ea73fd9e74ed06a8");
	private static final Par PAR_STOCK_USDT = new Par(USDT, STOCK_ECCBB);

	@Test
	void decodificaCalldataA00597a0() {
		byte[] calldata = HEX.parseHex(CALLDATA_SELL);
		Optional<SwapPendente> opt = RoteadorBStocks.decodificar(calldata, pool -> Optional.of(PAR_STOCK_USDT));

		assertTrue(opt.isPresent(), "não decodificou a00597a0");
		SwapPendente swap = opt.get();

		assertEquals(2, swap.caminho().size());
		assertArrayEquals(STOCK_ECCBB, swap.caminho().get(0), "tokenIn deve ser STOCK_ECCBB");
		assertArrayEquals(USDT, swap.caminho().get(1), "tokenOut deve ser USDT");

		assertArrayEquals(POOL_27B5, swap.pools().get(0), "pool incorreta");

		assertEquals(new BigInteger("4719e98f65f0e97f1e", 16), swap.entra());
	}

	
	
	private static final String CALLDATA_SELL_V2 = "95435ac9"
			+ "0000000000000000000000000000000000000000000000751335220c653db4e3"
			+ "00000000000000000000000000000000000000000149a053ceb3946eb9fd49c7"
			+ "000000000000000000000000febfa339e44c28e2aa9e62ea1027c9cb4e378605"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000000000000000000000000000000000000000000185a6"
			+ "000000000000000000000000b2f3279107da5fde24e6d7337cf4ecaf6c5ada26"
			+ "000000000000000000000000000000000000000000000000000000006ad6a1ee";

	private static final byte[] STOCK_FEBFA = hex("febfa339e44c28e2aa9e62ea1027c9cb4e378605");
	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] POOL_B2F3 = hex("b2f3279107da5fde24e6d7337cf4ecaf6c5ada26");
	private static final Par PAR_STOCK_WBNB = new Par(STOCK_FEBFA, WBNB);

	@Test
	void decodificaCalldata95435ac9() {
		byte[] calldata = HEX.parseHex(CALLDATA_SELL_V2);
		Optional<SwapPendente> opt = RoteadorBStocks.decodificar(calldata, pool -> Optional.of(PAR_STOCK_WBNB));

		assertTrue(opt.isPresent(), "não decodificou 95435ac9");
		SwapPendente swap = opt.get();

		assertEquals(2, swap.caminho().size());
		assertArrayEquals(STOCK_FEBFA, swap.caminho().get(0), "tokenIn deve ser STOCK_FEBFA");
		assertArrayEquals(WBNB, swap.caminho().get(1), "tokenOut deve ser WBNB");

		assertArrayEquals(POOL_B2F3, swap.pools().get(0), "pool incorreta");

		assertEquals(new BigInteger("751335220c653db4e3", 16), swap.entra());
	}

	private static byte[] hex(String s) {
		return HEX.parseHex(s);
	}
}