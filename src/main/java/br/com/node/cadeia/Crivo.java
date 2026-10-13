package br.com.node.cadeia;

import java.time.Instant;
import java.util.Set;

import br.com.node.vo.ForkId;

// Um conjunto de cortes de fork aceitos. Quem escolhe o conjunto é a fábrica, e
// são duas perguntas diferentes sobre o mesmo dado:
//
//   bsc()          — "é da BSC?"        para catalogar par
//   corteDeHoje()  — "vai conversar?"   para abrir conversa
//
// Confundir as duas custou uma rodada: um par num corte anterior é BSC e recusa a
// conversa, porque não conhece o nosso hash. E bootnode é BSC e não fala eth.
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

	// Só o hash entra na comparação. O campo `proximo` varia entre pares no mesmo
	// corte e compará-lo recusaria quem é compatível.
	private static long valorDe(ForkId fork) {

		byte[] hash = fork.hash();

		if (hash.length != 4) {
			return -1;
		}

		return ((long) (hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16) | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff);
	}
}
