package br.com.node.nucleo;

import java.math.BigInteger;

// O efeito de um swap: o que saiu, e a pool como ela fica.
//
// A pool nova vem junto porque a perna seguinte de um ciclo calcula sobre ela, e
// porque projetar uma transação pendente é exatamente aplicar isto e seguir.
public record Passo(BigInteger saida, Pool depois) {
}
