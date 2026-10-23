package br.com.node.transformacao.nucleo;

import java.math.BigInteger;

public record Travessia(Pool pool, Direcao direcao, int impostoEntra, int impostoSai) {

	private static final BigInteger ESCALA = BigInteger.valueOf(10_000);

	public Travessia {

		if (impostoEntra < 0 || impostoEntra >= 10_000 || impostoSai < 0 || impostoSai >= 10_000) {
			throw new IllegalArgumentException(
					"imposto fora de [0, 10000) bps: entra " + impostoEntra + ", sai " + impostoSai);
		}
	}

	public Travessia(Pool pool, Direcao direcao) {
		this(pool, direcao, 0, 0);
	}

	public boolean taxada() {
		return impostoEntra > 0 || impostoSai > 0;
	}

	public Curva curva() {

		Curva base = pool.curva(direcao);

		if (!taxada()) {
			return base;
		}

		BigInteger entra = ESCALA.subtract(BigInteger.valueOf(impostoEntra));
		BigInteger sai = ESCALA.subtract(BigInteger.valueOf(impostoSai));

		return new Curva(base.a().multiply(entra).multiply(sai), base.b().multiply(ESCALA).multiply(ESCALA),
				base.c().multiply(entra).multiply(ESCALA));
	}

	public Passo aplicar(BigInteger entrada) {

		BigInteger chegaAPool = entrada.subtract(imposto(entrada, impostoEntra));
		Passo passo = pool.aplicar(direcao, chegaAPool);
		BigInteger chegaANos = passo.saida().subtract(imposto(passo.saida(), impostoSai));

		return new Passo(chegaANos, passo.depois());
	}

	private static BigInteger imposto(BigInteger quantia, int bps) {
		if (bps == 0) {
			return BigInteger.ZERO;
		}
		BigInteger[] divisao = quantia.multiply(BigInteger.valueOf(bps)).divideAndRemainder(ESCALA);
		return divisao[1].signum() == 0 ? divisao[0] : divisao[0].add(BigInteger.ONE);
	}
}