package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.node.catalogo.Fabricas;
import br.com.node.vo.SwapPendente;

final class RoteadorSolidly {

	static final Set<String> SELECOES_TOKEN = Set.of("f41766d8", "6cc1ae13", "18a13086", "7af728c8");

	static final Set<String> SELECOES_ETH = Set.of("67ffb66a", "76c72751");

	private static final HexFormat HEX = HexFormat.of();
	private static final byte[] FACTORY = HEX.parseHex("afd89d21bdb66d00817d4153e055830b1c2b3970");
	private static final int PALAVRA = 32;
	private static final int SELETOR_LEN = 4;

	private RoteadorSolidly() {
	}

	static Optional<SwapPendente> decodificar(byte[] calldata, BigInteger valor) {

		if (calldata == null || calldata.length < SELETOR_LEN + PALAVRA * 3) {
			return Optional.empty();
		}

		String sel = HEX.formatHex(calldata, 0, SELETOR_LEN);

		if (SELECOES_TOKEN.contains(sel)) {
			BigInteger amountIn = palavraCd(calldata, 0);
			if (amountIn.signum() <= 0) {
				return Optional.empty();
			}
			int offRoutes = SELETOR_LEN + inteiro(palavraCd(calldata, 2));
			return rotas(calldata, offRoutes, amountIn);
		}

		if (SELECOES_ETH.contains(sel) && valor != null && valor.signum() > 0) {
			int offRoutes = SELETOR_LEN + inteiro(palavraCd(calldata, 1));
			return rotas(calldata, offRoutes, valor);
		}

		return Optional.empty();
	}

	private static Optional<SwapPendente> rotas(byte[] calldata, int offRoutes, BigInteger amountIn) {

		if (offRoutes < 0 || offRoutes + PALAVRA > calldata.length) {
			return Optional.empty();
		}

		int n = inteiro(new BigInteger(1, Arrays.copyOfRange(calldata, offRoutes, offRoutes + PALAVRA)));

		if (n < 1 || n > 8 || offRoutes + PALAVRA + (long) n * 3 * PALAVRA > calldata.length) {
			return Optional.empty();
		}

		List<byte[]> tokens = new ArrayList<>();
		List<byte[]> pools = new ArrayList<>();
		int base = offRoutes + PALAVRA;

		for (int i = 0; i < n; i++) {
			int r = base + i * 3 * PALAVRA;
			byte[] from = Arrays.copyOfRange(calldata, r + 12, r + PALAVRA);
			byte[] to = Arrays.copyOfRange(calldata, r + PALAVRA + 12, r + 2 * PALAVRA);
			boolean stable = calldata[r + 3 * PALAVRA - 1] != 0;

			Optional<byte[]> pool = Fabricas.parSolidly(FACTORY, from, to, stable);
			if (pool.isEmpty()) {
				return Optional.empty();
			}

			if (i == 0) {
				tokens.add(from);
			}
			tokens.add(to);
			pools.add(pool.get());
		}

		return Optional.of(new SwapPendente(amountIn, tokens, pools));
	}

	private static BigInteger palavraCd(byte[] calldata, int indice) {
		int base = SELETOR_LEN + indice * PALAVRA;
		return new BigInteger(1, Arrays.copyOfRange(calldata, base, base + PALAVRA));
	}

	private static int inteiro(BigInteger valor) {
		return valor.bitLength() > 31 ? -1 : valor.intValueExact();
	}
}