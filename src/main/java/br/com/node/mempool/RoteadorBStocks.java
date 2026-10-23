package br.com.node.mempool;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import br.com.node.nucleo.Par;
import br.com.node.vo.SwapPendente;

public final class RoteadorBStocks {

	static final String ROTEADOR = "278d858f05b94576c1e6f73285886876ff6ef8d2";

	static final String SELETOR_BUY = "42e3dcf3";
	static final String SELETOR_SELL = "a00597a0";
	static final String SELETOR_SELL_V2 = "95435ac9";

	private static final HexFormat HEX = HexFormat.of();
	private static final int W = 32;
	private static final int SEL = 4;

	private RoteadorBStocks() {
	}

	public static Optional<SwapPendente> decodificar(byte[] calldata, Function<byte[], Optional<Par>> parDaPool) {

		if (calldata == null || calldata.length < SEL + 5 * W) {
			return Optional.empty();
		}
		String sel = HEX.formatHex(calldata, 0, SEL);

		if (SELETOR_BUY.equals(sel)) {
			byte[] tokenOut = addrAt(calldata, SEL + W);
			byte[] pool = addrAt(calldata, SEL + 4 * W);
			if (allZero(pool))
				return Optional.empty();
			Optional<Par> par = parDaPool.apply(pool);
			if (par.isEmpty())
				return Optional.empty();
			byte[] tokenIn = Arrays.equals(par.get().token0(), tokenOut) ? par.get().token1() : par.get().token0();
			BigInteger entra = new BigInteger(1, Arrays.copyOfRange(calldata, SEL + 2 * W, SEL + 3 * W));
			if (entra.signum() <= 0) {
				entra = new BigInteger(1, Arrays.copyOfRange(calldata, SEL, SEL + W));
			}
			return Optional.of(new SwapPendente(entra, List.of(tokenIn, tokenOut), List.of(pool)));
		}

		if (SELETOR_SELL.equals(sel)) {
			byte[] tokenIn = addrAt(calldata, SEL + 2 * W);
			byte[] pool = addrAt(calldata, SEL + 4 * W);
			if (allZero(pool))
				return Optional.empty();
			Optional<Par> par = parDaPool.apply(pool);
			if (par.isEmpty())
				return Optional.empty();
			byte[] tokenOut = Arrays.equals(par.get().token0(), tokenIn) ? par.get().token1() : par.get().token0();
			BigInteger entra = new BigInteger(1, Arrays.copyOfRange(calldata, SEL, SEL + W));
			if (entra.signum() <= 0)
				return Optional.empty();
			return Optional.of(new SwapPendente(entra, List.of(tokenIn, tokenOut), List.of(pool)));
		}

		if (SELETOR_SELL_V2.equals(sel) && calldata.length >= SEL + 6 * W) {
			byte[] tokenIn = addrAt(calldata, SEL + 2 * W);
			byte[] pool = addrAt(calldata, SEL + 5 * W);
			if (allZero(pool))
				return Optional.empty();
			Optional<Par> par = parDaPool.apply(pool);
			if (par.isEmpty())
				return Optional.empty();
			byte[] tokenOut = Arrays.equals(par.get().token0(), tokenIn) ? par.get().token1() : par.get().token0();
			BigInteger entra = new BigInteger(1, Arrays.copyOfRange(calldata, SEL, SEL + W));
			if (entra.signum() <= 0)
				return Optional.empty();
			return Optional.of(new SwapPendente(entra, List.of(tokenIn, tokenOut), List.of(pool)));
		}

		return Optional.empty();
	}

	private static byte[] addrAt(byte[] calldata, int byteOffset) {
		return Arrays.copyOfRange(calldata, byteOffset + 12, byteOffset + W);
	}

	private static boolean allZero(byte[] addr) {
		for (byte b : addr) {
			if (b != 0)
				return false;
		}
		return true;
	}
}