package br.com.node.transformacao.catalogo;

import java.util.Map;
import java.util.OptionalInt;

public record Fabrica(String nome, boolean v3, byte[] factory, byte[] deployer, byte[] hashDoCodigo,
		Map<Integer, Integer> espacamentoPorTaxa, OptionalInt taxaFixa, byte solidlyType) {

	public boolean solidlyStable() {
		return solidlyType == 2;
	}
}