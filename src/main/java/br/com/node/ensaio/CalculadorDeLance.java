package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.Optional;

final class CalculadorDeLance {

	static final int MARGEM_DE_ESTADO_MILESIMOS = Optional.ofNullable(System.getenv("MARGEM_DE_ESTADO_MILESIMOS"))
			.map(String::trim).filter(v -> !v.isBlank()).map(Integer::parseInt).orElse(100);

	static final BigInteger TETO_PAGAMENTO = Optional.ofNullable(System.getenv("PAGAMENTO_TETO_BNB")).map(String::trim)
			.filter(v -> !v.isBlank()).map(v -> new java.math.BigDecimal(v).movePointRight(18).toBigInteger())
			.orElse(new BigInteger("50000000000000000"));

	private CalculadorDeLance() {
	}

	static BigInteger lucroParaLance(BigInteger lucro, BigInteger piso, int margemMilesimos) {
		if (margemMilesimos < 0 || margemMilesimos > 900) {
			throw new IllegalStateException("MARGEM_DE_ESTADO_MILESIMOS fora de [0; 900]: " + margemMilesimos);
		}
		BigInteger excedente = lucro.subtract(piso).max(BigInteger.ZERO);
		return piso
				.add(excedente.multiply(BigInteger.valueOf(1000 - margemMilesimos)).divide(BigInteger.valueOf(1000)));
	}

	static BigInteger pagamentoAoBuilder(BigInteger necessario, BigInteger lucro, BigInteger piso, BigInteger teto) {
		BigInteger pagamento = necessario.min(maximoPagamento(lucro, piso)).min(teto);
		return pagamento.signum() > 0 ? pagamento : BigInteger.ZERO;
	}

	static BigInteger maximoPagamento(BigInteger lucro, BigInteger piso) {
		return lucro.subtract(piso).subtract(BigInteger.ONE).max(BigInteger.ZERO);
	}

	static BigInteger necessarioParaOAlvo(BigInteger alvoWeiPorGas, BigInteger precoDoGas, BigInteger gas) {
		BigInteger falta = alvoWeiPorGas.subtract(precoDoGas.multiply(BigInteger.valueOf(9)).divide(BigInteger.TEN));
		return falta.signum() > 0 ? falta.multiply(gas) : BigInteger.ZERO;
	}
}