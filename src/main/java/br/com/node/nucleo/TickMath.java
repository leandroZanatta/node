package br.com.node.nucleo;

import java.math.BigInteger;

public final class TickMath {

	public static final int TICK_MAXIMO = 887272;

	private static final BigInteger Q128 = BigInteger.ONE.shiftLeft(128);
	private static final BigInteger MAXIMO_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
	private static final BigInteger DOIS_A_32 = BigInteger.ONE.shiftLeft(32);

	private static final String[] FATORES = { "fffcb933bd6fad37aa2d162d1a594001", "fff97272373d413259a46990580e213a",
			"fff2e50f5f656932ef12357cf3c7fdcc", "ffe5caca7e10e4e61c3624eaa0941cd0", "ffcb9843d60f6159c9db58835c926644",
			"ff973b41fa98c081472e6896dfb254c0", "ff2ea16466c96a3843ec78b326b52861", "fe5dee046a99a2a811c461f1969c3053",
			"fcbe86c7900a88aedcffc83b479aa3a4", "f987a7253ac413176f2b074cf7815e54", "f3392b0822b70005940c7a398e4b70f3",
			"e7159475a2c29b7443b29c7fa6e889d9", "d097f3bdfd2022b8845ad8f792aa5825", "a9f746462d870fdf8a65dc1f90e061e5",
			"70d869a156d2a1b890bb3df62baf32f7", "31be135f97d08fd981231505542fcfa6", "9aa508b5b7a84e1c677de54f3e99bc9",
			"5d6af8dedb81196699c329225ee604", "2216e584f5fa1ea926041bedfe98", "48a170391f7dc42444e8fa2" };

	private static final BigInteger[] FATORES_Q128 = java.util.Arrays.stream(FATORES)
			.map(hex -> new BigInteger(hex, 16)).toArray(BigInteger[]::new);

	private static final int TETO_DO_CACHE = 200_000;
	private static final java.util.concurrent.ConcurrentHashMap<Integer, BigInteger> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private TickMath() {
	}

	public static BigInteger paraSqrt(int tick) {

		BigInteger guardado = CACHE.get(tick);

		if (guardado != null) {
			return guardado;
		}

		BigInteger calculado = calcular(tick);

		if (CACHE.size() >= TETO_DO_CACHE) {
			CACHE.clear();
		}

		CACHE.put(tick, calculado);
		return calculado;
	}

	private static BigInteger calcular(int tick) {

		int absoluto = Math.abs(tick);

		if (absoluto > TICK_MAXIMO) {
			throw new IllegalArgumentException("Tick fora da faixa: " + tick);
		}

		BigInteger razao = (absoluto & 0x1) != 0 ? FATORES_Q128[0] : Q128;

		for (int bit = 1; bit < FATORES.length; bit++) {

			if ((absoluto & (1 << bit)) != 0) {
				razao = razao.multiply(FATORES_Q128[bit]).shiftRight(128);
			}
		}

		if (tick > 0) {
			razao = MAXIMO_UINT256.divide(razao);
		}

		BigInteger[] divisao = razao.divideAndRemainder(DOIS_A_32);

		return divisao[1].signum() == 0 ? divisao[0] : divisao[0].add(BigInteger.ONE);
	}
}