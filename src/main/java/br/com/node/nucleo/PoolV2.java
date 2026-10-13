package br.com.node.nucleo;

import java.math.BigInteger;

// O estado de uma pool de produto constante.
//
// A taxa vem da FACTORY, nunca de inferência sobre trades observados. Medido no
// projeto anterior: a pool 7a3eb6e7… estava gravada com 5 bps e 94.404 amostras, e
// cobra 1000 — ela sozinha respondeu por 13 dos 13 reverts por invariante de um
// dia. Inferência errou por fator de 200 com 94 mil amostras.
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

		// A mesma conta do getAmountOut do par, em milionésimos em vez de milésimos. A
		// divisão inteira trunca, e truncar é arredondar para baixo — a favor da pool e
		// contra nós, que é o lado certo de errar.
		BigInteger saida = liquida.multiply(reservaSai).divide(reservaEntra.multiply(escala).add(liquida));

		BigInteger novaEntra = reservaEntra.add(entrada);
		BigInteger novaSai = reservaSai.subtract(saida);

		PoolV2 depois = direcao == Direcao.ZERO_PARA_UM ? new PoolV2(novaEntra, novaSai, taxa)
				: new PoolV2(novaSai, novaEntra, taxa);

		return new Passo(saida, depois);
	}

	// `saída = x·k·Rsai / (Rentra·E + x·k)`, com `k = E − taxa`. É a mesma expressão do
	// `aplicar` acima, só reagrupada — e é por isso que ela não precisa de teste próprio:
	// o teste compara as duas.
	@Override
	public Curva curva(Direcao direcao) {

		BigInteger escala = BigInteger.valueOf(ESCALA_DA_TAXA);
		BigInteger liquido = escala.subtract(BigInteger.valueOf(taxa));

		BigInteger reservaEntra = direcao == Direcao.ZERO_PARA_UM ? reserva0 : reserva1;
		BigInteger reservaSai = direcao == Direcao.ZERO_PARA_UM ? reserva1 : reserva0;

		return new Curva(liquido.multiply(reservaSai), reservaEntra.multiply(escala), liquido);
	}
}
