package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import br.com.node.vo.SwapPendente;

public final class RoteadorAgregador {

	static final String SELETOR = "27772d13";

	private static final HexFormat HEX = HexFormat.of();
	private static final byte[] BNB_NATIVO = HEX.parseHex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
	private static final byte[] WBNB = HEX.parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	private static final int W = 32;
	private static final int SEL = 4;

	private static final int OFF_SRC_TOKEN = SEL + 1 * W;
	private static final int OFF_SRC_AMOUNT = SEL + 3 * W;
	private static final int OFF_ROUTES_LEN = SEL + 15 * W;
	private static final int OFF_ROUTES_BASE = SEL + 16 * W;

	private RoteadorAgregador() {
	}

	public static Optional<SwapPendente> decodificar(byte[] calldata) {

		if (calldata == null || calldata.length < OFF_ROUTES_BASE + W) {
			return Optional.empty();
		}
		if (!SELETOR.equals(HEX.formatHex(calldata, 0, SEL))) {
			return Optional.empty();
		}

		byte[] srcTokenRaw = addrAt(calldata, OFF_SRC_TOKEN);
		byte[] srcToken = Arrays.equals(srcTokenRaw, BNB_NATIVO) ? WBNB.clone() : srcTokenRaw;

		BigInteger srcAmount = wordAt(calldata, OFF_SRC_AMOUNT);
		if (srcAmount.signum() <= 0) {
			return Optional.empty();
		}

		long routesCount = wordAt(calldata, OFF_ROUTES_LEN).longValue();
		if (routesCount <= 0 || routesCount > 8) {
			return Optional.empty();
		}
		if (calldata.length < OFF_ROUTES_BASE + routesCount * W) {
			return Optional.empty();
		}

		List<byte[]> caminho = new ArrayList<>();
		List<byte[]> pools = new ArrayList<>();
		caminho.add(srcToken);
		boolean poolsOk = true;

		for (int i = 0; i < routesCount; i++) {

			long routeOffset = wordAt(calldata, OFF_ROUTES_BASE + i * W).longValue();
			int routeStart = OFF_ROUTES_BASE + (int) routeOffset;

			if (calldata.length < routeStart + 3 * W) {
				return Optional.empty();
			}

			byte[] pool = addrAt(calldata, routeStart);
			byte[] toToken = addrAt(calldata, routeStart + 2 * W);

			caminho.add(toToken);
			pools.add(pool);
			if (allZero(pool)) {
				poolsOk = false;
			}
		}

		return Optional.of(new SwapPendente(srcAmount, caminho, poolsOk ? pools : List.of()));
	}

	private static byte[] addrAt(byte[] calldata, int byteOffset) {
		return Arrays.copyOfRange(calldata, byteOffset + 12, byteOffset + W);
	}

	private static BigInteger wordAt(byte[] calldata, int byteOffset) {
		return new BigInteger(1, Arrays.copyOfRange(calldata, byteOffset, byteOffset + W));
	}

	private static boolean allZero(byte[] addr) {
		for (byte b : addr) {
			if (b != 0)
				return false;
		}
		return true;
	}
}