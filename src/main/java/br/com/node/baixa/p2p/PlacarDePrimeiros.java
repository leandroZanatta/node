package br.com.node.baixa.p2p;

public final class PlacarDePrimeiros {

	private final long meiaVidaMs;
	private double pontos;
	private long em;

	public PlacarDePrimeiros(long meiaVidaMs) {
		this.meiaVidaMs = meiaVidaMs;
	}

	public synchronized void anotar(long agoraMs) {
		pontos = valor(agoraMs) + 1;
		em = agoraMs;
	}

	public synchronized double valor(long agoraMs) {
		if (pontos == 0) {
			return 0;
		}
		double idade = Math.max(0, agoraMs - em);
		return pontos * Math.pow(0.5, idade / meiaVidaMs);
	}
}