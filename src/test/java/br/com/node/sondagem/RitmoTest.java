package br.com.node.sondagem;
import br.com.node.baixa.rpc.*;
import br.com.node.transformacao.sondagem.*;


import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RitmoTest {

	@Test
	@DisplayName("recusa corta pela metade; período limpo sobe um por segundo, nunca por salto")
	void encolheRapidoCresceDevagar() {

		Ritmo ritmo = new Ritmo();

		int inicial = ritmo.porSegundo();
		assertTrue(inicial > 1, "o ritmo inicial precisa ter de onde cair para o teste medir a queda");

		
		ritmo.recusou(1_000);
		assertEquals(inicial / 2, ritmo.porSegundo(), "recusa não cortou pela metade");

		
		
		int aposRecusa = ritmo.porSegundo();
		ritmo.passou(1_500);
		assertEquals(aposRecusa, ritmo.porSegundo(), "subiu com menos de um segundo limpo");

		
		ritmo.passou(2_600);
		assertEquals(aposRecusa + 1, ritmo.porSegundo(), "um segundo limpo não subiu exatamente um");

		
		
		for (long t = 3_700; t < 30_000; t += 1_100) {
			ritmo.passou(t);
		}
		assertEquals(inicial, ritmo.porSegundo(), "voltar à parede é ok; ultrapassá-la cedo não é");
	}

	private static void assertEquals(int esperado, int real, String porque) {
		org.junit.jupiter.api.Assertions.assertEquals(esperado, real, porque);
	}
}