package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.Optional;

public record ReservasV2(BigInteger reserva0, BigInteger reserva1, long altura) {

	public Optional<Integer> taxaQueFecha(Direcao direcao, BigInteger entrada, BigInteger saida) {

		if (entrada.signum() <= 0 || saida.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger reservaEntra = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger reservaSai = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		if (reservaEntra.signum() <= 0 || reservaSai.compareTo(saida) <= 0) {
			return Optional.empty();
		}

		BigInteger escala = BigInteger.valueOf(Pool.ESCALA_DA_TAXA);
		BigInteger liquida = saida.multiply(reservaEntra).multiply(escala).divide(reservaSai.subtract(saida));
		BigInteger aproximada = escala.subtract(liquida.divide(entrada));

		if (aproximada.signum() < 0 || aproximada.compareTo(escala) >= 0) {
			return Optional.empty();
		}

		return exataPerto(aproximada.intValueExact(), direcao, entrada, saida);
	}

	private Optional<Integer> exataPerto(int centro, Direcao direcao, BigInteger entrada, BigInteger saida) {

		int menor = Math.max(0, centro - 3);
		int maior = Math.min(Pool.ESCALA_DA_TAXA - 1, centro + 3);

		Optional<Integer> primeira = Optional.empty();

		for (int taxa = menor; taxa <= maior; taxa++) {

			try {

				if (!new PoolV2(reserva0, reserva1, taxa).aplicar(direcao, entrada).saida().equals(saida)) {
					continue;
				}

			} catch (IllegalArgumentException recusou) {
				return Optional.empty();
			}

			if (taxa % Pool.PONTO_BASE == 0) {
				return Optional.of(taxa);
			}

			if (primeira.isEmpty()) {
				primeira = Optional.of(taxa);
			}
		}

		return primeira;
	}
}