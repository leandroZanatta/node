package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.Optional;

// As reservas de uma pool de produto constante, na altura em que valem.
//
// A altura vem junto porque todo dado carrega a altura em que vale. E a TAXA não vem:
// reserva é o que a cadeia diz, taxa é o que nós provamos — duas procedências
// diferentes, e misturá-las num record só é como "estimada" virou opinião no projeto
// anterior, guardada na mesma tabela que a leitura.
public record ReservasV2(BigInteger reserva0, BigInteger reserva1, long altura) {

	// A taxa que faz esta troca fechar EXATAMENTE, ou nada.
	//
	// É o inverso do `aplicar`, e é a única procedência aceitável para a taxa. Medido no
	// projeto anterior: a pool 7a3eb6e7… estava gravada com 5 bps a partir de 94.404
	// amostras médias, e cobra 1000 — inferência estatística errou por fator de 200 com 94
	// mil observações, e respondeu sozinha por 13 dos 13 reverts por invariante de um dia.
	//
	// Por que "exatamente" e não "aproximadamente": o par NÃO calcula a saída. Quem chama
	// escolhe `amountOut` e o par só confere o invariante K, então muitas trocas saem
	// abaixo do máximo e nenhuma taxa fecha nelas. Essas não dizem nada sobre a taxa, e
	// aceitar a mais próxima seria transformar "o chamador pediu menos" em "a taxa é
	// outra" — que é como se grava 5 bps numa pool de 1000.
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

	// A inversão acima erra por truncagem, nunca por muito: a janela de três milionésimos
	// em volta dela é onde a taxa exata está, se existir.
	//
	// E dentro da janela pode haver EMPATE: em troca pequena, 2497..2503 dão o mesmo wei de
	// saída truncada. Devolver a primeira exata devolvia 2497, que o cadastro recusa por
	// quebrada — e a pool de 2500 ficava fora do grafo por ordem de laço, não por evidência.
	// Havendo um múltiplo de ponto-base entre as exatas, é ele: a janela de sete milionésimos
	// cabe no máximo um.
	private Optional<Integer> exataPerto(int centro, Direcao direcao, BigInteger entrada,
			BigInteger saida) {

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
