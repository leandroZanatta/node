package br.com.node.nucleo;

import java.math.BigInteger;

// Quanto entrar e quanto volta, no token de partida.
//
// A saída é a que as pools pagam de verdade, com as truncagens delas — não a que a curva
// prevê. `lucro` é bruto: não desconta gas, porque gas é preço de mercado no instante do
// envio e não é propriedade do ciclo.
public record Oportunidade(BigInteger entrada, BigInteger saida) {

	public BigInteger lucro() {
		return saida.subtract(entrada);
	}
}
