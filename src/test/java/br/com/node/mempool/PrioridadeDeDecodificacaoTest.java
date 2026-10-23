package br.com.node.mempool;
import br.com.node.transformacao.mempool.*;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class PrioridadeDeDecodificacaoTest {

	@Test
	void reservaTresDeQuatroSimulacoesParaSeletoresDesconhecidos() {

		AtomicLong agora = new AtomicLong(1_000);
		Simulador simulador = new Simulador((metodo, parametros) -> Optional.empty(), (hash, logs) -> {
		}, agora::get);

		assertTrue(simulador.cabe("a@contrato", false));
		assertFalse(simulador.cabe("b@contrato", false), "validações conhecidas ficam limitadas a uma por segundo");
		assertTrue(simulador.cabe("c@contrato", true));
		assertTrue(simulador.cabe("d@contrato", true));
		assertTrue(simulador.cabe("e@contrato", true));
		assertFalse(simulador.cabe("f@contrato", true), "o teto total continua sendo quatro por segundo");

		agora.addAndGet(1_000);
		assertTrue(simulador.cabe("g@contrato", true));
		assertTrue(simulador.cabe("h@contrato", true));
		assertTrue(simulador.cabe("i@contrato", true));
		assertTrue(simulador.cabe("j@contrato", false), "uma validação conhecida pode usar a vaga restante");
		assertFalse(simulador.cabe("k@contrato", false));

		simulador.close();
	}
}