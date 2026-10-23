package br.com.node.transformacao.nucleo;

import java.util.List;

public record Ciclo(List<Perna> pernas) {

	public Ciclo {
		pernas = List.copyOf(pernas);
	}
}