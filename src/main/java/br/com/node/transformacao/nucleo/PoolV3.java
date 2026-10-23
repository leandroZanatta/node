package br.com.node.transformacao.nucleo;

import java.math.BigInteger;

public record PoolV3(BigInteger sqrtPrecoX96, BigInteger liquidez, int tick, int taxa, int tickSpacing,
		MapaDeTicks ticks) implements Pool {

	private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);
	private static final BigInteger ESCALA = BigInteger.valueOf(ESCALA_DA_TAXA);
	private static final int PASSOS_MAXIMOS = 512;

	public PoolV3(BigInteger sqrtPrecoX96, BigInteger liquidez, int tick, int taxa, int tickSpacing) {
		this(sqrtPrecoX96, liquidez, tick, taxa, tickSpacing, null);
	}

	public PoolV3 comTicks(MapaDeTicks mapa) {
		return mapa == null || tick < mapa.tickMinimo() || tick > mapa.tickMaximo() ? this
				: new PoolV3(sqrtPrecoX96, liquidez, tick, taxa, tickSpacing, mapa);
	}

	public boolean atravessa() {
		return ticks != null;
	}

	@Override
	public Passo aplicar(Direcao direcao, BigInteger entrada) {

		if (entrada.signum() <= 0) {
			throw new IllegalArgumentException("Entrada tem de ser positiva: " + entrada);
		}

		if (ticks != null) {
			return atravessar(direcao == Direcao.ZERO_PARA_UM, entrada);
		}

		if (liquidez.signum() <= 0) {
			throw new IllegalArgumentException("Pool sem liquidez ativa não cota");
		}

		if (entrada.compareTo(maximoNoTick(direcao)) > 0) {
			throw new IllegalArgumentException("Entrada cruza o tick: máximo " + maximoNoTick(direcao));
		}

		BigInteger liquida = semTaxa(entrada);

		BigInteger proximoSqrt = direcao == Direcao.ZERO_PARA_UM ? sqrtAposToken0(liquida) : sqrtAposToken1(liquida);

		BigInteger saida = direcao == Direcao.ZERO_PARA_UM ? token1Entre(proximoSqrt, sqrtPrecoX96)
				: token0Entre(sqrtPrecoX96, proximoSqrt);

		return new Passo(saida, new PoolV3(proximoSqrt, liquidez, tick, taxa, tickSpacing));
	}

	private Passo atravessar(boolean zeroParaUm, BigInteger entrada) {

		BigInteger restante = entrada;
		BigInteger saida = BigInteger.ZERO;
		BigInteger sqrt = sqrtPrecoX96;
		BigInteger l = liquidez;
		int t = tick;

		for (int passo = 0; restante.signum() > 0; passo++) {

			if (passo >= PASSOS_MAXIMOS) {
				throw new IllegalArgumentException("Entrada cruza ticks demais");
			}

			int comprimido = Math.floorDiv(t, tickSpacing);
			Integer candidato = zeroParaUm ? ticks.abaixoOuIgual(t) : ticks.acima(t);
			Integer inicializado;
			int alvo;

			if (zeroParaUm) {
				int inicioDaPalavra = (comprimido >> 8) << 8;
				boolean naPalavra = candidato != null && Math.floorDiv(candidato, tickSpacing) >= inicioDaPalavra;
				inicializado = naPalavra ? candidato : null;
				alvo = naPalavra ? candidato : inicioDaPalavra * tickSpacing;
			} else {
				int fimDaPalavra = (((comprimido + 1) >> 8) << 8) + 255;
				boolean naPalavra = candidato != null && Math.floorDiv(candidato, tickSpacing) <= fimDaPalavra;
				inicializado = naPalavra ? candidato : null;
				alvo = naPalavra ? candidato : fimDaPalavra * tickSpacing;
			}

			alvo = Math.max(-TickMath.TICK_MAXIMO, Math.min(TickMath.TICK_MAXIMO, alvo));

			if (alvo < ticks.tickMinimo() || alvo > ticks.tickMaximo()) {
				throw new IllegalArgumentException("Entrada sai da faixa conhecida de ticks");
			}

			BigInteger sqrtAlvo = TickMath.paraSqrt(alvo);

			if (zeroParaUm ? sqrtAlvo.compareTo(sqrt) > 0 : sqrtAlvo.compareTo(sqrt) < 0) {
				throw new IllegalArgumentException("Entrada sai da faixa conhecida de ticks");
			}

			BigInteger[] passoDado = passo(zeroParaUm, sqrt, sqrtAlvo, l, restante);
			BigInteger sqrtProximo = passoDado[0];

			restante = restante.subtract(passoDado[1]).subtract(passoDado[3]);
			saida = saida.add(passoDado[2]);
			sqrt = sqrtProximo;

			if (!sqrtProximo.equals(sqrtAlvo)) {
				break;
			}

			if (inicializado == null) {
				t = zeroParaUm ? alvo - 1 : alvo;
				continue;
			}

			BigInteger liquida = ticks.liquida(alvo);
			l = zeroParaUm ? l.subtract(liquida) : l.add(liquida);

			if (l.signum() < 0) {
				throw new IllegalArgumentException("Liquidez negativa ao cruzar o tick " + alvo);
			}

			t = zeroParaUm ? alvo - 1 : alvo;
		}

		return new Passo(saida, new PoolV3(sqrt, l, t, taxa, tickSpacing, ticks));
	}

	private BigInteger[] passo(boolean zeroParaUm, BigInteger sqrt, BigInteger sqrtAlvo, BigInteger l,
			BigInteger restante) {

		BigInteger taxaPips = BigInteger.valueOf(taxa);
		BigInteger menosTaxa = restante.multiply(ESCALA.subtract(taxaPips)).divide(ESCALA);

		BigInteger ateOAlvo = zeroParaUm ? delta0(sqrtAlvo, sqrt, l, true) : delta1(sqrt, sqrtAlvo, l, true);

		boolean chega = menosTaxa.compareTo(ateOAlvo) >= 0;
		BigInteger sqrtProximo = chega ? sqrtAlvo
				: zeroParaUm ? proximoPorToken0(sqrt, l, menosTaxa) : proximoPorToken1(sqrt, l, menosTaxa);

		BigInteger entrou = chega ? ateOAlvo
				: zeroParaUm ? delta0(sqrtProximo, sqrt, l, true) : delta1(sqrt, sqrtProximo, l, true);
		BigInteger saiu = zeroParaUm ? delta1(sqrtProximo, sqrt, l, false) : delta0(sqrt, sqrtProximo, l, false);
		BigInteger cobrada = chega ? divCima(entrou.multiply(taxaPips), ESCALA.subtract(taxaPips))
				: restante.subtract(entrou);

		return new BigInteger[] { sqrtProximo, entrou, saiu, cobrada };
	}

	private static BigInteger delta0(BigInteger a, BigInteger b, BigInteger l, boolean paraCima) {

		BigInteger menor = a.min(b);
		BigInteger maior = a.max(b);
		BigInteger numerador1 = l.shiftLeft(96);
		BigInteger numerador2 = maior.subtract(menor);

		return paraCima ? divCima(divCima(numerador1.multiply(numerador2), maior), menor)
				: numerador1.multiply(numerador2).divide(maior).divide(menor);
	}

	private static BigInteger delta1(BigInteger a, BigInteger b, BigInteger l, boolean paraCima) {

		BigInteger diferenca = a.max(b).subtract(a.min(b));

		return paraCima ? divCima(l.multiply(diferenca), Q96) : l.multiply(diferenca).divide(Q96);
	}

	private static BigInteger proximoPorToken0(BigInteger sqrt, BigInteger l, BigInteger quantia) {

		if (quantia.signum() == 0) {
			return sqrt;
		}

		BigInteger numerador1 = l.shiftLeft(96);

		return divCima(numerador1.multiply(sqrt), numerador1.add(quantia.multiply(sqrt)));
	}

	private static BigInteger proximoPorToken1(BigInteger sqrt, BigInteger l, BigInteger quantia) {
		return sqrt.add(quantia.shiftLeft(96).divide(l));
	}

	private static BigInteger divCima(BigInteger a, BigInteger b) {
		BigInteger[] qr = a.divideAndRemainder(b);
		return qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
	}

	@Override
	public Curva curva(Direcao direcao) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquido = escala.subtract(BigInteger.valueOf(taxa));

		BigInteger quadradoDoPreco = sqrtPrecoX96.multiply(sqrtPrecoX96);
		BigInteger quadradoDeQ96 = Q96.multiply(Q96);

		BigInteger deCima = direcao == Direcao.ZERO_PARA_UM ? quadradoDoPreco : quadradoDeQ96;
		BigInteger deBaixo = direcao == Direcao.ZERO_PARA_UM ? quadradoDeQ96 : quadradoDoPreco;

		return new Curva(liquidez.multiply(deCima).multiply(liquido), liquidez.multiply(deBaixo).multiply(escala),
				Q96.multiply(sqrtPrecoX96).multiply(liquido));
	}

	public BigInteger maximoNoTick(Direcao direcao) {

		int inferior = Math.floorDiv(tick, tickSpacing) * tickSpacing;

		BigInteger borda = direcao == Direcao.ZERO_PARA_UM ? TickMath.paraSqrt(inferior)
				: TickMath.paraSqrt(inferior + tickSpacing);

		BigInteger liquida = direcao == Direcao.ZERO_PARA_UM ? token0Entre(borda, sqrtPrecoX96)
				: token1Entre(sqrtPrecoX96, borda);

		return comTaxa(liquida);
	}

	private BigInteger semTaxa(BigInteger entrada) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger cobrada = entrada.multiply(BigInteger.valueOf(taxa)).add(escala).subtract(BigInteger.ONE)
				.divide(escala);

		return entrada.subtract(cobrada);
	}

	private BigInteger comTaxa(BigInteger liquida) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);

		return liquida.multiply(escala).divide(escala.subtract(BigInteger.valueOf(taxa)));
	}

	private BigInteger sqrtAposToken0(BigInteger entrada) {

		BigInteger numerador = liquidez.multiply(Q96);

		return numerador.multiply(sqrtPrecoX96).divide(numerador.add(entrada.multiply(sqrtPrecoX96)));
	}

	private BigInteger sqrtAposToken1(BigInteger entrada) {
		return sqrtPrecoX96.add(entrada.multiply(Q96).divide(liquidez));
	}

	private BigInteger token0Entre(BigInteger menor, BigInteger maior) {
		return liquidez.multiply(Q96).multiply(maior.subtract(menor)).divide(maior.multiply(menor));
	}

	private BigInteger token1Entre(BigInteger menor, BigInteger maior) {
		return liquidez.multiply(maior.subtract(menor)).divide(Q96);
	}
}