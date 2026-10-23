package br.com.node.vo;

import java.util.Arrays;
import java.util.List;

public record NewPooledTransactionHashes(List<byte[]> hashes, List<Long> tamanhos) implements MensagemEth {

	public NewPooledTransactionHashes {
		hashes = hashes.stream().map(hash -> Arrays.copyOf(hash, hash.length)).toList();
		tamanhos = tamanhos == null || tamanhos.size() != hashes.size() ? List.of() : List.copyOf(tamanhos);
	}

	public NewPooledTransactionHashes(List<byte[]> hashes) {
		this(hashes, List.of());
	}

	public long tamanho(int i) {
		return tamanhos.isEmpty() ? -1 : tamanhos.get(i);
	}

	@Override
	public int tipo() {
		return 0x08;
	}
}