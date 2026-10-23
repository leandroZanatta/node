package br.com.node.sessao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class VinculoRttTest {

	@Test
	void guardaAIdaEVoltaDoPing() throws InterruptedException {

		Vinculo vinculo = new Vinculo(Duration.ofHours(1));
		assertEquals(-1, vinculo.rttMs(), "sem ping, sem medida");

		byte[] hash = new byte[] { 1, 2, 3 };
		vinculo.pingEnviado(hash);
		Thread.sleep(15);

		assertTrue(vinculo.pongRecebido(hash, Instant.now()));
		assertTrue(vinculo.rttMs() >= 15, "rtt medido: " + vinculo.rttMs());
	}
}