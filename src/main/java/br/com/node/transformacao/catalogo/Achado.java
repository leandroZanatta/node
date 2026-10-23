package br.com.node.transformacao.catalogo;

import java.util.List;

import br.com.node.transformacao.nucleo.Ciclo;
import br.com.node.transformacao.nucleo.Oportunidade;
import br.com.node.transformacao.nucleo.Travessia;

public record Achado(long altura, Ciclo ciclo, Oportunidade oportunidade, List<Travessia> travessias) {

	public Achado {
		travessias = List.copyOf(travessias);
	}
}