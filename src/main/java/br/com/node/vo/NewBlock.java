package br.com.node.vo;

import java.util.Arrays;
import java.util.List;

// O bloco inteiro empurrado (`NewBlock`, eth 0x07). Medido: ~13 por minuto — o par manda o
// corpo para um subconjunto pequeno e só o hash (`NewBlockHashes`) para o resto. É o caminho
// quente da cabeça: a header chega de graça, sem `pedir`.
//
// A dissecção é a do `BlockHeaders`: a altura é o campo 8 da header e o hash é o keccak do RLP
// canônico dela. As transações ficam cruas — o desfecho (tirar da projeção o swap pendente que o
// bloco incluiu) é quem decodifica, com `TransacaoPendente`.
public record NewBlock(Cabecalho cabecalho, List<byte[]> transacoes) implements MensagemEth {

	public NewBlock {
		transacoes = transacoes.stream().map(tx -> Arrays.copyOf(tx, tx.length)).toList();
	}

	@Override
	public int tipo() {
		return NEW_BLOCK;
	}
}