package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import br.com.node.vo.SwapPendente;

// Decodifica swaps do roteador agregador multi-hop — seletor 27772d13, proxy d36b6d64 na BSC.
// Cada transação percorre N rotas (N ≥ 1); cada rota é uma perna num par de tokens via um pool.
//
// Layout do calldata (após seletor de 4 bytes):
//   byte  4..35  = 0x20          → offset ao struct principal (sempre 0x20)
//   byte 36..67  = srcToken      (0xeee…eee = BNB nativo → convertido a WBNB)
//   byte 68..99  = dstToken
//   byte 100..131 = srcAmount
//   byte 132..163 = minReturn
//   byte 164..483 = outros campos do struct (flags, recipient, offsets para dois arrays vazios)
//   byte 484..515 = N (número de rotas)
//   byte 516..    = offsets de cada rota[i], relativos ao byte 516
//   — body da rota[i] a partir do byte 516 + offset[i] —
//     word 0 = pool (endereço, últimos 20 bytes)
//     word 1 = fromToken
//     word 2 = toToken
//     ...
//
// Caminhos com pool zero em qualquer rota retornam SwapPendente sem pools (caída no fallback de lookup).
public final class RoteadorAgregador {

	static final String SELETOR = "27772d13";

	private static final byte[] BNB_NATIVO =
			HexFormat.of().parseHex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
	private static final byte[] WBNB =
			HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	private static final int W = 32;
	private static final int SEL = 4;

	// Byte offsets absolutos no calldata:
	private static final int OFF_SRC_TOKEN  = SEL + 1 * W;       // palavra 1 dos params
	private static final int OFF_SRC_AMOUNT = SEL + 3 * W;       // palavra 3
	private static final int OFF_ROUTES_LEN = SEL + 15 * W;      // palavra 15
	private static final int OFF_ROUTES_BASE = SEL + 16 * W;     // 516 — base dos offsets de rota

	private RoteadorAgregador() {}

	public static Optional<SwapPendente> decodificar(byte[] calldata) {

		if (calldata == null || calldata.length < OFF_ROUTES_BASE + W) {
			return Optional.empty();
		}
		if (!SELETOR.equals(HexFormat.of().formatHex(calldata, 0, SEL))) {
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

			byte[] pool    = addrAt(calldata, routeStart);           // word[0] da rota
			byte[] toToken = addrAt(calldata, routeStart + 2 * W);   // word[2] da rota

			caminho.add(toToken);
			pools.add(pool);
			if (allZero(pool)) {
				poolsOk = false;
			}
		}

		return Optional.of(new SwapPendente(srcAmount, caminho, poolsOk ? pools : List.of()));
	}

	// Endereço de 20 bytes: últimos 20 bytes da palavra ABI (a palavra é right-aligned para
	// endereço, i.e. zeros nos 12 bytes superiores, endereço nos 20 inferiores).
	private static byte[] addrAt(byte[] calldata, int byteOffset) {
		return Arrays.copyOfRange(calldata, byteOffset + 12, byteOffset + W);
	}

	private static BigInteger wordAt(byte[] calldata, int byteOffset) {
		return new BigInteger(1, Arrays.copyOfRange(calldata, byteOffset, byteOffset + W));
	}

	private static boolean allZero(byte[] addr) {
		for (byte b : addr) {
			if (b != 0) return false;
		}
		return true;
	}
}
