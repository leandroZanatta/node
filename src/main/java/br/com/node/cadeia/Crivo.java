package br.com.node.cadeia;

import java.time.Instant;
import java.util.Set;

import br.com.node.vo.ForkId;

public final class Crivo {

	private final Set<Long> aceitos;

	public static Crivo bsc() {
		return new Crivo(Forks.validosDaBsc());
	}

	public static Crivo corteDeHoje() {
		return new Crivo(Set.of(valorDe(Forks.doBsc(Long.MAX_VALUE, Instant.now().getEpochSecond()))));
	}

	private Crivo(Set<Long> aceitos) {
		this.aceitos = aceitos;
	}

	public boolean aceita(ForkId declarado) {

		byte[] hash = declarado.hash();

		return hash.length == 4 && aceitos.contains(valorDe(declarado));
	}

	private static long valorDe(ForkId fork) {

		byte[] hash = fork.hash();

		if (hash.length != 4) {
			return -1;
		}

		return ((long) (hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16) | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff);
	}
}