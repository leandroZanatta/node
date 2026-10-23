package br.com.node.baixa.p2p;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

public final class Vinculo {

	private final Duration validade;

	private volatile byte[] hashDoPing;
	private volatile Instant provado;

	private volatile long enviadoEmNanos;
	private volatile long rttMs = -1;

	public Vinculo(Duration validade) {
		this.validade = validade;
	}

	public void pingEnviado(byte[] hash) {
		this.enviadoEmNanos = System.nanoTime();
		this.hashDoPing = hash;
	}

	public long rttMs() {
		return rttMs;
	}

	public boolean pongRecebido(byte[] eco, Instant agora) {

		byte[] esperado = hashDoPing;

		if (esperado == null || !Arrays.equals(esperado, eco)) {
			return false;
		}

		provado = agora;
		hashDoPing = null;
		rttMs = (System.nanoTime() - enviadoEmNanos) / 1_000_000;

		return true;
	}

	public boolean valido(Instant agora) {

		Instant quando = provado;

		return quando != null && quando.plus(validade).isAfter(agora);
	}
}