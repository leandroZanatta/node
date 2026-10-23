package br.com.node.transformacao.nucleo;

import java.math.BigInteger;

public sealed interface Pool permits PoolV2, PoolV3, PoolSolidlyStable {

	int ESCALA_DA_TAXA = 1_000_000;

	int PONTO_BASE = 100;

	Passo aplicar(Direcao direcao, BigInteger entrada);

	Curva curva(Direcao direcao);
}