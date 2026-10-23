package br.com.node.sessao;

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