package br.com.node.nucleo;

import java.math.BigInteger;

public record PoolSolidlyStable(BigInteger reserva0, BigInteger reserva1, int taxa) implements Pool {

	private static final BigInteger D = BigInteger.TEN.pow(18);

	@Override
	public Passo aplicar(Direcao direcao, BigInteger entrada) {

		if (entrada.signum() <= 0) {
			throw new IllegalArgumentException("Entrada tem de ser positiva: " + entrada);
		}

		BigInteger reservaEntra = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger reservaSai = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		if (reservaEntra.signum() <= 0 || reservaSai.signum() <= 0) {
			throw new IllegalArgumentException("Pool sem reserva não cota");
		}

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquida = entrada.multiply(escala.subtract(BigInteger.valueOf(taxa))).divide(escala);

		BigInteger xy = stableK(reservaEntra, reservaSai);
		BigInteger xNovo = reservaEntra.add(liquida);
		BigInteger yNovo = encontrarY(xNovo, xy, reservaSai);
		BigInteger saida = reservaSai.subtract(yNovo);

		if (saida.signum() <= 0) {
			throw new IllegalArgumentException("Stable pool: saída não positiva para entrada " + entrada);
		}

		BigInteger novaEntra = reservaEntra.add(entrada);
		BigInteger novaSai = reservaSai.subtract(saida);

		PoolSolidlyStable depois = direcao == Direcao.ZERO_PARA_UM ? new PoolSolidlyStable(novaEntra, novaSai, taxa)
				: new PoolSolidlyStable(novaSai, novaEntra, taxa);

		return new Passo(saida, depois);
	}

	@Override
	public Curva curva(Direcao direcao) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquido = escala.subtract(BigInteger.valueOf(taxa));

		BigInteger x = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger y = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		BigInteger x2 = x.multiply(x);
		BigInteger y2 = y.multiply(y);
		BigInteger rEntra = x.multiply(x2.add(y2.multiply(BigInteger.valueOf(3))));
		BigInteger rSai = y.multiply(x2.multiply(BigInteger.valueOf(3)).add(y2));

		return new Curva(liquido.multiply(rSai), rEntra.multiply(escala), liquido);
	}

	private static BigInteger stableK(BigInteger x, BigInteger y) {
		BigInteger a = x.multiply(y).divide(D);
		BigInteger b = x.multiply(x).divide(D).add(y.multiply(y).divide(D));
		return a.multiply(b).divide(D);
	}

	private static BigInteger stableF(BigInteger x0, BigInteger y) {
		BigInteger yy = y.multiply(y).divide(D);
		BigInteger yyy = yy.multiply(y).divide(D);
		BigInteger term1 = x0.multiply(yyy).divide(D);

		BigInteger x2 = x0.multiply(x0).divide(D);
		BigInteger x3 = x2.multiply(x0).divide(D);
		BigInteger term2 = x3.multiply(y).divide(D);

		return term1.add(term2);
	}

	private static BigInteger stableD(BigInteger x0, BigInteger y) {
		BigInteger y2 = y.multiply(y).divide(D);
		BigInteger term1 = BigInteger.valueOf(3).multiply(x0).multiply(y2).divide(D);
		BigInteger x2 = x0.multiply(x0).divide(D);
		BigInteger term2 = x2.multiply(x0).divide(D);
		return term1.add(term2);
	}

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