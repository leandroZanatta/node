package br.com.node.vo;

import java.util.List;

public record NewBlockHashes(List<AnuncioBloco> anuncios) implements MensagemEth {

	public NewBlockHashes {
		anuncios = List.copyOf(anuncios);
	}

	@Override
	public int tipo() {
		return NEW_BLOCK_HASHES;
	}
}