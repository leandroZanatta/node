package br.com.node.sessao;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

public final class Vinculo {

	private final Duration validade;

	private volatile byte[] hashDoPing;
	private volatile Instant provado;

	// Ida e volta do ping UDP da descoberta, em ms (-1 = nunca medido). É a distância ao nó ANTES de abrir
	// TCP — o que permite a lotação preferir os perto. O ping já acontecia; só não se guardava o tempo.
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
