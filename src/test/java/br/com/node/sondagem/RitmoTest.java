package br.com.node.sondagem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// A parede adaptativa, conferida sem rede — porque a regra é do old e custou uma tarde: um teto fixo
// derruba os nós públicos (403 em 13 de 14 a ~190/s) ou deixa a fila parada.
//
// Sem relógio próprio: o instante entra por parâmetro, e é isso que torna "cresce um por segundo" e
// "corta pela metade na recusa" conferível aqui em vez de numa tarde de 403.
class RitmoTest {

	@Test
	@DisplayName("recusa corta pela metade; período limpo sobe um por segundo, nunca por salto")
	void encolheRapidoCresceDevagar() {

		Ritmo ritmo = new Ritmo();

		int inicial = ritmo.porSegundo();
		assertTrue(inicial > 1, "o ritmo inicial precisa ter de onde cair para o teste medir a queda");

		// recusa: metade, de uma vez
		ritmo.recusou(1_000);
		assertEquals(inicial / 2, ritmo.porSegundo(), "recusa não cortou pela metade");

		// menos de um segundo limpo não sobe: crescer a cada chamada acharia a parede num salto
		int aposRecusa = ritmo.porSegundo();
		ritmo.passou(1_500);
		assertEquals(aposRecusa, ritmo.porSegundo(), "subiu com menos de um segundo limpo");

		// um segundo limpo: sobe exatamente um
		ritmo.passou(2_600);
		assertEquals(aposRecusa + 1, ritmo.porSegundo(), "um segundo limpo não subiu exatamente um");

		// e não passa da parede (o ritmo em que se bateu) só por insistir segundo a segundo
		for (long t = 3_700; t < 30_000; t += 1_100) {
			ritmo.passou(t);
		}
		assertEquals(inicial, ritmo.porSegundo(), "voltar à parede é ok; ultrapassá-la cedo não é");
	}

	private static void assertEquals(int esperado, int real, String porque) {
		org.junit.jupiter.api.Assertions.assertEquals(esperado, real, porque);
	}
}
