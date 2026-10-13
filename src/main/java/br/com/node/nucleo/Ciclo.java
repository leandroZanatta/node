package br.com.node.nucleo;

import java.util.List;

// Um caminho fechado: sai de um token e volta nele.
//
// Duas ou três pernas. Medido no histórico do projeto anterior: 44.190 ciclos com
// média de 2,93 pernas, nenhum com mais de três. Enumerar mais fundo multiplica o
// custo sem alcançar dinheiro que exista.
public record Ciclo(List<Perna> pernas) {

	public Ciclo {
		pernas = List.copyOf(pernas);
	}
}
