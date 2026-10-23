package br.com.node.transformacao.nucleo;

import java.math.BigInteger;

public record Curva(BigInteger a, BigInteger b, BigInteger c) {

	public Curva {

		if (a.signum() <= 0 || b.signum() <= 0 || c.signum() <= 0) {
			throw new IllegalArgumentException("Curva com coeficiente não positivo: " + a + " " + b + " " + c);
		}
	}

	public Curva compor(Curva seguinte) {

		BigInteger novoA = a.multiply(seguinte.a);
		BigInteger novoB = b.multiply(seguinte.b);
		BigInteger novoC = seguinte.b.multiply(c).add(a.multiply(seguinte.c));

		BigInteger mdc = novoA.gcd(novoB).gcd(novoC);

		return new Curva(novoA.divide(mdc), novoB.divide(mdc), novoC.divide(mdc));
	}

	public BigInteger aplicar(BigInteger entrada) {
		return a.multiply(entrada).divide(b.add(c.multiply(entrada)));
	}

	public boolean rende() {
		return a.compareTo(b) > 0;
	}

	public BigInteger entradaOtima() {
		return a.multiply(b).sqrt().subtract(b).divide(c);
	}
}