package br.com.node.catalogo;

import java.util.List;

import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Travessia;

public record Achado(long altura, Ciclo ciclo, Oportunidade oportunidade, List<Travessia> travessias) {

	public Achado {
		travessias = List.copyOf(travessias);
	}
}
