package br.com.node.sessao;

// QUEM ENTREGA PRIMEIRO. Conta as vezes em que um par foi o primeiro dos nossos a nos mandar um hash pendente
// (o observatório de mempool anota). Com meia-vida: o que o par entregou há `meiaVidaMs` pesa a metade. Assim
// a lotação prefere quem entrega AGORA, e não quem entregou bem numa hora que já passou.
//
// Sem sinal de tempo no construtor: quem chama passa o instante, o que deixa o teste determinístico.
final class PlacarDePrimeiros {

	private final long meiaVidaMs;
	private double pontos;
	private long em;

	PlacarDePrimeiros(long meiaVidaMs) {
		this.meiaVidaMs = meiaVidaMs;
	}

	synchronized void anotar(long agoraMs) {
		pontos = valor(agoraMs) + 1;
		em = agoraMs;
	}

	synchronized double valor(long agoraMs) {
		if (pontos == 0) {
			return 0;
		}
		double idade = Math.max(0, agoraMs - em);
		return pontos * Math.pow(0.5, idade / meiaVidaMs);
	}
}
