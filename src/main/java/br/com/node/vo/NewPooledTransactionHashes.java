package br.com.node.vo;

import java.util.Arrays;
import java.util.List;

// `tamanhos` é o tamanho de cada tx (eth/68), na MESMA ordem dos hashes; vazio quando o peer fala um
// formato antigo que não o manda. É o que deixa pedir só o que pode ser swap.
public record NewPooledTransactionHashes(List<byte[]> hashes, List<Long> tamanhos) implements MensagemEth {

	public NewPooledTransactionHashes {
		hashes = hashes.stream().map(hash -> Arrays.copyOf(hash, hash.length)).toList();
		tamanhos = tamanhos == null || tamanhos.size() != hashes.size() ? List.of() : List.copyOf(tamanhos);
	}

	public NewPooledTransactionHashes(List<byte[]> hashes) {
		this(hashes, List.of());
	}

	// O tamanho da i-ésima tx, ou -1 se o peer não disse.
	public long tamanho(int i) {
		return tamanhos.isEmpty() ? -1 : tamanhos.get(i);
	}

	@Override
	public int tipo() {
		return 0x08;
	}
}
