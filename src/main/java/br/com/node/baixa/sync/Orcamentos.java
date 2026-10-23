package br.com.node.baixa.sync;

import java.util.concurrent.ConcurrentHashMap;

import br.com.node.vo.NoRemoto;

public final class Orcamentos {

	private final ConcurrentHashMap<String, Integer> porPar = new ConcurrentHashMap<>();

	private final int teto;

	public Orcamentos(int teto) {
		this.teto = teto;
	}

	public int quanto(NoRemoto no) {
		return porPar.getOrDefault(no.idHex(), teto);
	}

	public void aprender(NoRemoto no, int pedidos, int vieram) {

		if (vieram <= 0) {
			return;
		}

		if (vieram == pedidos && pedidos < quanto(no)) {
			return;
		}

		int proximo = vieram < pedidos ? vieram : Math.min(teto, pedidos + pedidos / 4 + 1);

		porPar.put(no.idHex(), proximo);
	}
}