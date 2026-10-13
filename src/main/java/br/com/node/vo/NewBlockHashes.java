package br.com.node.vo;

import java.util.List;

// Como a cabeça chega na prática. Medido: 256 destes contra 11 `NewBlock` — o par
// manda o bloco inteiro para um subconjunto pequeno e só o hash para o resto.
// Quem quer o conteúdo pede; quem quer saber que existe, já sabe.
public record NewBlockHashes(List<AnuncioBloco> anuncios) implements MensagemEth {

	public NewBlockHashes {
		anuncios = List.copyOf(anuncios);
	}

	@Override
	public int tipo() {
		return NEW_BLOCK_HASHES;
	}
}
