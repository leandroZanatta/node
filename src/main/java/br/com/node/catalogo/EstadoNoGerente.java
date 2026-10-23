package br.com.node.catalogo;

import br.com.node.nucleo.EstadoV3;

public record EstadoNoGerente(byte[] gerente, EstadoV3 estado, int taxa) {

	public EstadoNoGerente {

		if (taxa < 0 || taxa >= br.com.node.nucleo.Pool.ESCALA_DA_TAXA) {
			throw new IllegalArgumentException("taxa fora de [0, 100%): " + taxa);
		}
	}
}