package br.com.node.baixa.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.util.Resources;
import br.com.node.vo.NoRemoto;

public final class Desanimo {

	private static final Duration PRAZO = Duration.ofSeconds(Resources.inteiro("sincronizacao.desanimo.segundos"));

	private final Map<String, Instant> ate = new ConcurrentHashMap<>();

	public void desanimar(NoRemoto no) {
		ate.put(no.idHex(), Instant.now().plus(PRAZO));
	}

	public boolean animado(NoRemoto no) {

		Instant limite = ate.get(no.idHex());

		if (limite == null) {
			return true;
		}

		if (limite.isAfter(Instant.now())) {
			return false;
		}

		ate.remove(no.idHex(), limite);

		return true;
	}
}