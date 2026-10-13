package br.com.node.nucleo;

import java.math.BigInteger;

// Converte tick em raiz de preço Q96, como o contrato do V3 faz.
//
// É a tabela de constantes do TickMath.sol, e cada uma é um fator binário de
// 1,0001^(2^n). Não há como conferir isto lendo: uma constante errada dá um preço
// plausível e errado, em silêncio.
//
// Por isso ela é PROVADA contra a rede, não contra vetor escrito à mão: todo evento
// `Swap` do V3 carrega `sqrtPriceX96` E `tick`, e o contrato garante
// `paraSqrt(tick) <= sqrtPriceX96 < paraSqrt(tick+1)`. Mil swaps reais são mil
// asserções sobre esta tabela.
public final class TickMath {

	public static final int TICK_MAXIMO = 887272;

	private static final BigInteger Q128 = BigInteger.ONE.shiftLeft(128);
	private static final BigInteger MAXIMO_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
	private static final BigInteger DOIS_A_32 = BigInteger.ONE.shiftLeft(32);

	// Índice = bit do tick absoluto; valor = fator Q128.
	private static final String[] FATORES = { "fffcb933bd6fad37aa2d162d1a594001", "fff97272373d413259a46990580e213a",
			"fff2e50f5f656932ef12357cf3c7fdcc", "ffe5caca7e10e4e61c3624eaa0941cd0",
			"ffcb9843d60f6159c9db58835c926644", "ff973b41fa98c081472e6896dfb254c0",
			"ff2ea16466c96a3843ec78b326b52861", "fe5dee046a99a2a811c461f1969c3053",
			"fcbe86c7900a88aedcffc83b479aa3a4", "f987a7253ac413176f2b074cf7815e54",
			"f3392b0822b70005940c7a398e4b70f3", "e7159475a2c29b7443b29c7fa6e889d9",
			"d097f3bdfd2022b8845ad8f792aa5825", "a9f746462d870fdf8a65dc1f90e061e5",
			"70d869a156d2a1b890bb3df62baf32f7", "31be135f97d08fd981231505542fcfa6",
			"9aa508b5b7a84e1c677de54f3e99bc9", "5d6af8dedb81196699c329225ee604",
			"2216e584f5fa1ea926041bedfe98", "48a170391f7dc42444e8fa2" };

	// As constantes em BigInteger, lidas UMA vez. CICATRIZ de 26/09: o parse do hexadecimal era refeito a cada
	// chamada (até 20 por tick), e o `paraSqrt` roda em cada passo de tick de cada simulação da busca do
	// `Avaliador` — medido na bancada, ~2 µs por chamada; com o mapa de ticks nas pools V3, a avaliação do
	// garimpo foi de ~80 para ~1.000 ms (p90) no servidor.
	private static final BigInteger[] FATORES_Q128 = java.util.Arrays.stream(FATORES)
			.map(hex -> new BigInteger(hex, 16)).toArray(BigInteger[]::new);

	// O resultado por tick: a função é pura, e a busca do `Avaliador` cruza os MESMOS ticks centenas de vezes
	// por ciclo. Limpo inteiro ao passar do teto — os ticks vivos voltam no primeiro uso.
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

		// Q128 para Q96, arredondando para cima — é o que o contrato faz.
		BigInteger[] divisao = razao.divideAndRemainder(DOIS_A_32);

		return divisao[1].signum() == 0 ? divisao[0] : divisao[0].add(BigInteger.ONE);
	}
}
