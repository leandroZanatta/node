package br.com.node.transformacao.catalogo;

import br.com.node.transformacao.nucleo.EstadoV3;

public record EstadoNoGerente(byte[] gerente, EstadoV3 estado, int taxa) {

	public EstadoNoGerente {

		if (taxa < 0 || taxa >= br.com.node.transformacao.nucleo.Pool.ESCALA_DA_TAXA) {
			throw new IllegalArgumentException("taxa fora de [0, 100%): " + taxa);
		}
	}
}