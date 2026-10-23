package br.com.node.transformacao.nucleo;

import java.math.BigInteger;

public record PoolV2(BigInteger reserva0, BigInteger reserva1, int taxa) implements Pool {

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
		BigInteger liquida = entrada.multiply(escala.subtract(BigInteger.valueOf(taxa)));

		BigInteger saida = liquida.multiply(reservaSai).divide(reservaEntra.multiply(escala).add(liquida));

		BigInteger novaEntra = reservaEntra.add(entrada);
		BigInteger novaSai = reservaSai.subtract(saida);

		PoolV2 depois = direcao == Direcao.ZERO_PARA_UM ? new PoolV2(novaEntra, novaSai, taxa)
				: new PoolV2(novaSai, novaEntra, taxa);

		return new Passo(saida, depois);
	}

	@Override
	public Curva curva(Direcao direcao) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquido = escala.subtract(BigInteger.valueOf(taxa));

		BigInteger reservaEntra = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger reservaSai = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		return new Curva(liquido.multiply(reservaSai), reservaEntra.multiply(escala), liquido);
	}
}