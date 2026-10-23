package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public record EstadoV3(BigInteger sqrtPrecoX96, BigInteger liquidez, int tick, long altura) {

	private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);

	private static final BigInteger LIQUIDEZ_MAXIMA = BigInteger.ONE.shiftLeft(128);
	private static final BigInteger SQRT_MAXIMO = BigInteger.ONE.shiftLeft(160);

	public EstadoV3 {

		if (sqrtPrecoX96.signum() <= 0 || sqrtPrecoX96.compareTo(SQRT_MAXIMO) >= 0) {
			throw new IllegalArgumentException("sqrtPriceX96 fora do uint160: " + sqrtPrecoX96);
		}

		if (liquidez.signum() < 0 || liquidez.compareTo(LIQUIDEZ_MAXIMA) >= 0) {
			throw new IllegalArgumentException("liquidez fora do uint128: " + liquidez);
		}

		if (Math.abs(tick) > TickMath.TICK_MAXIMO) {
			throw new IllegalArgumentException("tick fora da faixa do contrato: " + tick);
		}
	}

	public static Optional<Integer> taxaEntre(EstadoV3 antes, EstadoV3 depois, BigInteger quantia0,
			BigInteger quantia1) {

		if (antes == null || !antes.liquidez().equals(depois.liquidez()) || depois.liquidez().signum() <= 0
				|| antes.sqrtPrecoX96().equals(depois.sqrtPrecoX96())) {
			return Optional.empty();
		}

		BigInteger l = depois.liquidez();
		BigInteger s0 = antes.sqrtPrecoX96();
		BigInteger s1 = depois.sqrtPrecoX96();
		boolean entraToken0 = s1.compareTo(s0) < 0;

		BigInteger bruta = entraToken0 ? quantia0 : quantia1;
		BigInteger liquida = entraToken0 ? l.multiply(Q96).multiply(s0.subtract(s1)).divide(s0.multiply(s1))
				: l.multiply(s1.subtract(s0)).divide(Q96);

		if (bruta.signum() <= 0 || liquida.signum() <= 0 || liquida.compareTo(bruta) > 0) {
			return Optional.empty();
		}

		long taxa = bruta.subtract(liquida).multiply(BigInteger.valueOf(Pool.ESCALA_DA_TAXA)).add(bruta.shiftRight(1))
				.divide(bruta).longValue();

		return taxa >= 0 && taxa < Pool.ESCALA_DA_TAXA ? Optional.of((int) taxa) : Optional.empty();
	}

	private static final List<Integer> DEGRAUS = List.of(100, 500, 2500, 3000, 10000);

	public Optional<Integer> taxaQueFecha(BigInteger quantia0, BigInteger quantia1) {

		if (quantia0.signum() == quantia1.signum()) {
			return Optional.empty();
		}

		boolean entraToken0 = quantia0.signum() > 0;

		BigInteger bruta = entraToken0 ? quantia0 : quantia1;
		BigInteger saida = entraToken0 ? quantia1.negate() : quantia0.negate();

		if (bruta.signum() <= 0 || saida.signum() <= 0 || liquidez.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger liquidaExigida = entraToken0 ? entradaDeToken0(saida) : entradaDeToken1(saida);

		if (liquidaExigida.signum() <= 0 || liquidaExigida.compareTo(bruta) > 0) {
			return Optional.empty();
		}

		return degrauQueExplica(bruta, liquidaExigida);
	}

	private BigInteger entradaDeToken0(BigInteger saida) {

		BigInteger sqrtAntes = sqrtPrecoX96.add(saida.multiply(Q96).divide(liquidez));

		return liquidez.multiply(Q96).multiply(sqrtAntes.subtract(sqrtPrecoX96))
				.divide(sqrtAntes.multiply(sqrtPrecoX96));
	}

	private BigInteger entradaDeToken1(BigInteger saida) {

		BigInteger numerador = liquidez.multiply(Q96);
		BigInteger sqrtAntes = numerador.multiply(sqrtPrecoX96).divide(numerador.add(saida.multiply(sqrtPrecoX96)));

		return liquidez.multiply(sqrtPrecoX96.subtract(sqrtAntes)).divide(Q96);
	}

	private Optional<Integer> degrauQueExplica(BigInteger bruta, BigInteger liquidaExigida) {

		BigInteger escala = BigInteger.valueOf(Pool.ESCALA_DA_TAXA);

		Integer unico = null;

		for (int taxa : DEGRAUS) {

			BigInteger cobrada = bruta.multiply(BigInteger.valueOf(taxa)).add(escala).subtract(BigInteger.ONE)
					.divide(escala);

			if (bruta.subtract(cobrada).subtract(liquidaExigida).abs().compareTo(BigInteger.ONE) > 0) {
				continue;
			}

			if (unico != null) {
				return Optional.empty();
			}

			unico = taxa;
		}

		return Optional.ofNullable(unico);
	}
}