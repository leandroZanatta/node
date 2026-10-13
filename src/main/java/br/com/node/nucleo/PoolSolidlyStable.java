package br.com.node.nucleo;

import java.math.BigInteger;

// Pool de produto estável Solidly: x³y + xy³ = k, equivalente a xy(x² + y²) = k.
//
// Usada pela ThenaFi (BSC) e forks do protocolo Solidly de Andre Cronje. A curva é mais
// plana que xy=k perto da paridade — boa para pares de stablecoin — e mais inclinada longe
// dela. A pool usa a MESMA interface Uniswap V2 (`swap` com callback), então serve como
// ponta de flash loan da mesma forma que PoolV2.
//
// INVARIANTE: a implementação em Solidity normaliza cada token pelo seu decimal antes de
// computar k. Na BSC, USDT, USDC e BUSD têm 18 casas, então a normalização é 1:1 e os
// raw BigInteger são usados diretamente. Pares com casas decimais diferentes precisariam
// de ajuste — NÃO implementado aqui (anotado para o futuro).
//
// NEWTON: encontrar y_novo dado x_novo e k é resolver uma cúbica — sem forma fechada.
// O contrato usa Newton com até 255 iterações e converge em ~5 passos normalmente.
// Aqui replicamos a mesma lógica, incluindo as divisões por 1e18 intermediárias, para
// que o resultado bata wei a wei com o contrato.
//
// CURVA: a forma a·x/(b+c·x) não descreve exatamente este AMM. O `curva()` retorna
// uma aproximação linear em torno do estado atual (derivadas parciais do invariante),
// usada apenas como filtro rápido; o lucro real é calculado pelo `aplicar` exato.
public record PoolSolidlyStable(BigInteger reserva0, BigInteger reserva1, int taxa) implements Pool {

	private static final BigInteger D = BigInteger.TEN.pow(18);

	@Override
	public Passo aplicar(Direcao direcao, BigInteger entrada) {

		if (entrada.signum() <= 0) {
			throw new IllegalArgumentException("Entrada tem de ser positiva: " + entrada);
		}

		BigInteger reservaEntra = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger reservaSai   = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		if (reservaEntra.signum() <= 0 || reservaSai.signum() <= 0) {
			throw new IllegalArgumentException("Pool sem reserva não cota");
		}

		BigInteger escala  = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquida = entrada.multiply(escala.subtract(BigInteger.valueOf(taxa))).divide(escala);

		BigInteger xy     = stableK(reservaEntra, reservaSai);
		BigInteger xNovo  = reservaEntra.add(liquida);
		BigInteger yNovo  = encontrarY(xNovo, xy, reservaSai);
		BigInteger saida  = reservaSai.subtract(yNovo);

		if (saida.signum() <= 0) {
			throw new IllegalArgumentException("Stable pool: saída não positiva para entrada " + entrada);
		}

		BigInteger novaEntra = reservaEntra.add(entrada);
		BigInteger novaSai   = reservaSai.subtract(saida);

		PoolSolidlyStable depois = direcao == Direcao.ZERO_PARA_UM
				? new PoolSolidlyStable(novaEntra, novaSai, taxa)
				: new PoolSolidlyStable(novaSai, novaEntra, taxa);

		return new Passo(saida, depois);
	}

	// Aproximação linear: trata a pool como V2 virtual com reservas efetivas derivadas
	// das derivadas parciais do invariante em (reserva0, reserva1). Usado só como filtro.
	// 0→1: taxa marginal = df/dx / df/dy = [y*(3x²+y²)] / [x*(x²+3y²)] × (1-fee)
	@Override
	public Curva curva(Direcao direcao) {

		BigInteger escala  = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquido = escala.subtract(BigInteger.valueOf(taxa));

		BigInteger x = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger y = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		// df/dx ∝ y*(3x²+y²), df/dy ∝ x*(x²+3y²)  — sem normalização 1e18 (cancela)
		BigInteger x2 = x.multiply(x);
		BigInteger y2 = y.multiply(y);
		BigInteger rEntra = x.multiply(x2.add(y2.multiply(BigInteger.valueOf(3)))); // x*(x²+3y²)
		BigInteger rSai   = y.multiply(x2.multiply(BigInteger.valueOf(3)).add(y2)); // y*(3x²+y²)

		return new Curva(liquido.multiply(rSai), rEntra.multiply(escala), liquido);
	}

	// k = xy(x²+y²) / 1e54  — exatamente como o contrato Solidly computa _k.
	private static BigInteger stableK(BigInteger x, BigInteger y) {
		BigInteger a = x.multiply(y).divide(D);
		BigInteger b = x.multiply(x).divide(D).add(y.multiply(y).divide(D));
		return a.multiply(b).divide(D);
	}

	// f(x0, y) = x0*y³/1e54 + x0³*y/1e54  (_f do contrato)
	private static BigInteger stableF(BigInteger x0, BigInteger y) {
		BigInteger yy  = y.multiply(y).divide(D);
		BigInteger yyy = yy.multiply(y).divide(D);
		BigInteger term1 = x0.multiply(yyy).divide(D);

		BigInteger x2  = x0.multiply(x0).divide(D);
		BigInteger x3  = x2.multiply(x0).divide(D);
		BigInteger term2 = x3.multiply(y).divide(D);

		return term1.add(term2);
	}

	// Derivada de f w.r.t. y = 3x0*y²/1e36 + x0³/1e36  (_d do contrato)
	private static BigInteger stableD(BigInteger x0, BigInteger y) {
		BigInteger y2   = y.multiply(y).divide(D);
		BigInteger term1 = BigInteger.valueOf(3).multiply(x0).multiply(y2).divide(D);
		BigInteger x2   = x0.multiply(x0).divide(D);
		BigInteger term2 = x2.multiply(x0).divide(D);
		return term1.add(term2);
	}

	// Newton: acha y tal que f(x0, y) = xy (o invariante), partindo de yIni.
	// Converge em ~5 iterações; teto de 255 como no contrato.
	private static BigInteger encontrarY(BigInteger x0, BigInteger xy, BigInteger yIni) {
		BigInteger y = yIni;
		for (int i = 0; i < 255; i++) {
			BigInteger prev = y;
			BigInteger k = stableF(x0, y);
			BigInteger d = stableD(x0, y);
			if (d.signum() == 0) {
				break;
			}
			if (k.compareTo(xy) < 0) {
				y = y.add(xy.subtract(k).multiply(D).divide(d));
			} else {
				y = y.subtract(k.subtract(xy).multiply(D).divide(d));
			}
			if (y.subtract(prev).abs().compareTo(BigInteger.ONE) <= 0) {
				break;
			}
		}
		return y;
	}
}
