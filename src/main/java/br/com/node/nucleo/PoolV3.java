package br.com.node.nucleo;

import java.math.BigInteger;

// O estado de uma pool de liquidez concentrada, no tick corrente.
//
// Não tem reserva: tem preço e liquidez, e os dois vêm prontos no evento `Swap`
// (`sqrtPriceX96`, `liquidity`, `tick`). Por isso o modelo do V3 não deriva — foi o
// V2, com taxa inferida, que era o inconfiável.
//
// A liquidez ativa muda também por `Mint`/`Burn` cuja faixa cobre o tick corrente.
// Entre dois swaps, ignorar esses dois deltas é ler aporte de liquidez como variação
// de preço — e é o que cegou o projeto anterior.
//
// SOBRE CRUZAR TICK: fora da faixa corrente a liquidez é outra, e o mapa de ticks
// exigiria história desde a criação da pool, que os pares não guardam (medido: eles
// servem recibo de 100 mil blocos atrás, não de 1 milhão). Então `aplicar` RECUSA o
// que cruza, e `maximoNoTick` diz até onde vai. Liquidez concentrada mais trade
// pequeno raramente cruza — a borda é teto, não obstáculo.
//
// COM `MapaDeTicks` A RECUSA ACABA — dentro da faixa que o mapa conhece. O swap anda pelo laço do contrato
// (`SwapMath.computeSwapStep` + `SqrtPriceMath`, idêntico na Uniswap V3, na PancakeSwap V3, na Uniswap V4 e no
// Infinity CL), com os MESMOS arredondamentos: entrada para cima, saída para baixo, taxa cobrada passo a passo,
// e a liquidez trocando pelo `liquidityNet` de cada tick inicializado cruzado. Sem mapa, tudo como antes.
public record PoolV3(BigInteger sqrtPrecoX96, BigInteger liquidez, int tick, int taxa, int tickSpacing,
		MapaDeTicks ticks) implements Pool {

	private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);
	private static final BigInteger ESCALA = BigInteger.valueOf(ESCALA_DA_TAXA);
	private static final int PASSOS_MAXIMOS = 512;

	public PoolV3(BigInteger sqrtPrecoX96, BigInteger liquidez, int tick, int taxa, int tickSpacing) {
		this(sqrtPrecoX96, liquidez, tick, taxa, tickSpacing, null);
	}

	// A mesma pool com o mapa de ticks — só onde o mapa existe e cobre o tick corrente.
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

	// O laço de swap do contrato, entrada exata. Cada passo vai até o próximo tick inicializado (ou a borda da
	// faixa conhecida); chegando nele com entrada sobrando, cruza e troca a liquidez. Passar da borda da faixa
	// é recusado: além dela o mapa não sabe o que há.
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

			// `TickBitmap.nextInitializedTickWithinOneWord`: o passo para no próximo tick inicializado OU na
			// fronteira da palavra do bitmap (256 ticks comprimidos), o que vier antes — mesmo sem liquidez ali.
			// Cada parada é um passo de arredondamento a mais: ir direto ao tick inicializado dava 1 wei A MAIS
			// que a cadeia num swap real que atravessou 62 ticks e uma fronteira de palavra.
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

			// Além da faixa que o mapa conhece não se sabe se há tick inicializado: não se passa dela.
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

	// `SwapMath.computeSwapStep`, entrada exata: {√P seguinte, entrada usada, saída, taxa cobrada}.
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

	// `SqrtPriceMath.getAmount0Delta`.
	private static BigInteger delta0(BigInteger a, BigInteger b, BigInteger l, boolean paraCima) {

		BigInteger menor = a.min(b);
		BigInteger maior = a.max(b);
		BigInteger numerador1 = l.shiftLeft(96);
		BigInteger numerador2 = maior.subtract(menor);

		return paraCima ? divCima(divCima(numerador1.multiply(numerador2), maior), menor)
				: numerador1.multiply(numerador2).divide(maior).divide(menor);
	}

	// `SqrtPriceMath.getAmount1Delta`.
	private static BigInteger delta1(BigInteger a, BigInteger b, BigInteger l, boolean paraCima) {

		BigInteger diferenca = a.max(b).subtract(a.min(b));

		return paraCima ? divCima(l.multiply(diferenca), Q96) : l.multiply(diferenca).divide(Q96);
	}

	// `getNextSqrtPriceFromAmount0RoundingUp`, somando: √P' = ⌈L·2^96·√P / (L·2^96 + x·√P)⌉.
	private static BigInteger proximoPorToken0(BigInteger sqrt, BigInteger l, BigInteger quantia) {

		if (quantia.signum() == 0) {
			return sqrt;
		}

		BigInteger numerador1 = l.shiftLeft(96);

		return divCima(numerador1.multiply(sqrt), numerador1.add(quantia.multiply(sqrt)));
	}

	// `getNextSqrtPriceFromAmount1RoundingDown`, somando: √P' = √P + ⌊x·2^96 / L⌋.
	private static BigInteger proximoPorToken1(BigInteger sqrt, BigInteger l, BigInteger quantia) {
		return sqrt.add(quantia.shiftLeft(96).divide(l));
	}

	private static BigInteger divCima(BigInteger a, BigInteger b) {
		BigInteger[] qr = a.divideAndRemainder(b);
		return qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
	}

	// Os coeficientes saem de substituir `semTaxa(x) = x·k/E` nas duas fórmulas exatas do
	// contrato e reagrupar. O `semTaxa` real arredonda a taxa para CIMA, e aqui ela é
	// linear: a diferença é de um wei na entrada líquida, e serve para dimensionar, não
	// para prometer saída — quem promete é o `aplicar`.
	@Override
	public Curva curva(Direcao direcao) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquido = escala.subtract(BigInteger.valueOf(taxa));

		BigInteger quadradoDoPreco = sqrtPrecoX96.multiply(sqrtPrecoX96);
		BigInteger quadradoDeQ96 = Q96.multiply(Q96);

		// A simetria não é estética: entrar com token0 e entrar com token1 trocam √P² por
		// Q96² nos dois primeiros coeficientes, e é assim que o contrato é escrito.
		BigInteger deCima = direcao == Direcao.ZERO_PARA_UM ? quadradoDoPreco : quadradoDeQ96;
		BigInteger deBaixo = direcao == Direcao.ZERO_PARA_UM ? quadradoDeQ96 : quadradoDoPreco;

		return new Curva(liquidez.multiply(deCima).multiply(liquido), liquidez.multiply(deBaixo).multiply(escala),
				Q96.multiply(sqrtPrecoX96).multiply(liquido));
	}

	// O maior montante que ainda fica dentro da faixa de liquidez corrente. A faixa
	// vai de um múltiplo de tickSpacing ao seguinte, porque só ali a liquidez pode
	// mudar.
	public BigInteger maximoNoTick(Direcao direcao) {

		int inferior = Math.floorDiv(tick, tickSpacing) * tickSpacing;

		BigInteger borda = direcao == Direcao.ZERO_PARA_UM ? TickMath.paraSqrt(inferior)
				: TickMath.paraSqrt(inferior + tickSpacing);

		BigInteger liquida = direcao == Direcao.ZERO_PARA_UM ? token0Entre(borda, sqrtPrecoX96)
				: token1Entre(sqrtPrecoX96, borda);

		return comTaxa(liquida);
	}

	// A taxa sai da entrada antes do swap, e o contrato arredonda a taxa para CIMA.
	// Copiamos: entrada líquida para baixo é subestimar a saída, que é o lado certo.
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

	// token0 entra: o preço CAI. Forma exata do contrato, sem perder precisão.
	private BigInteger sqrtAposToken0(BigInteger entrada) {

		BigInteger numerador = liquidez.multiply(Q96);

		return numerador.multiply(sqrtPrecoX96).divide(numerador.add(entrada.multiply(sqrtPrecoX96)));
	}

	// token1 entra: o preço SOBE.
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
