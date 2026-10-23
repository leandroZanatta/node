package br.com.node.sondagem;

public final class Ritmo {

	private static final int MINIMO = 1;
	private static final int TETO = 50;

	private static final long JANELA_ALEM_DA_PAREDE_MS = 30_000;

	private int porSegundo = 5;
	private int parede = TETO;
	private long ultimoCrescimentoMs;
	private long paredeDesdeMs;

	public synchronized long intervaloMs() {
		return 1000L / porSegundo;
	}

	public synchronized void recusou(long agoraMs) {
		parede = porSegundo;
		paredeDesdeMs = agoraMs;
		ultimoCrescimentoMs = agoraMs;
		porSegundo = Math.max(MINIMO, porSegundo / 2);
	}

	public synchronized void passou(long agoraMs) {

		if (agoraMs - ultimoCrescimentoMs < 1000) {
			return;
		}

		ultimoCrescimentoMs = agoraMs;

		if (porSegundo < parede) {
			porSegundo++;
		} else if (parede < TETO && agoraMs - paredeDesdeMs >= JANELA_ALEM_DA_PAREDE_MS) {
			parede++;
			porSegundo++;
			paredeDesdeMs = agoraMs;
		}
	}

	public synchronized int porSegundo() {
		return porSegundo;
	}
}