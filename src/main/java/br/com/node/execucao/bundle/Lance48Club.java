package br.com.node.execucao.bundle;
import br.com.node.baixa.rpc.RpcDoEnsaio;


import java.math.BigInteger;

public final class Lance48Club {

	private static final BigInteger DEZ = BigInteger.TEN;
	private static final BigInteger NOVE = BigInteger.valueOf(9);

	private Lance48Club() {
	}

	public static BigInteger taxaGas(BigInteger gasUsado, BigInteger precoEfetivoWei) {
		if (gasUsado == null || precoEfetivoWei == null || gasUsado.signum() < 0 || precoEfetivoWei.signum() < 0) {
			return null;
		}
		return gasUsado.multiply(precoEfetivoWei);
	}

	public static BigInteger contribuicaoGas(BigInteger taxaGasWei) {
		if (taxaGasWei == null || taxaGasWei.signum() < 0) {
			return null;
		}
		return taxaGasWei.multiply(NOVE).divide(DEZ);
	}

	public static BigInteger pontuacaoMinima(BigInteger taxaGasWei, BigInteger bnbDiretoWei) {
		if (taxaGasWei == null || bnbDiretoWei == null || bnbDiretoWei.signum() < 0) {
			return null;
		}
		return contribuicaoGas(taxaGasWei).add(bnbDiretoWei);
	}
}