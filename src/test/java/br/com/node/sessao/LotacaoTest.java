package br.com.node.sessao;
import br.com.node.baixa.p2p.*;
import br.com.node.baixa.protocolo.*;


import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.baixa.sync.Crivo;

class LotacaoTest {

	private static final int ESPERA_PARA_ABRIR = 120;
	private static final int TEMPO_DE_SUSTENTACAO = 40;
	private static final int MINIMO = 2;

	@Test
	@DisplayName("abre conversas eth e as mantém abertas, repondo o que cai")
	void abreEMantem() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		try {

			node.iniciar();

			Lotacao lotacao = node.lotar(Crivo.corteDeHoje()::aceita);

			long fim = System.currentTimeMillis() + ESPERA_PARA_ABRIR * 1000L;

			while (System.currentTimeMillis() < fim && lotacao.ativas() < MINIMO) {
				Thread.sleep(1000);
			}

			long abertas = lotacao.ativas();

			assertTrue(abertas >= MINIMO, "só " + abertas + " conversas ativas em " + ESPERA_PARA_ABRIR
					+ "s (com ~20% de aceitação medida, o gargalo é candidato, não teto)");

			System.out.println("ativas depois de abrir: " + abertas);

			
			
			
			long menor = abertas;

			for (int segundo = 0; segundo < TEMPO_DE_SUSTENTACAO; segundo += 5) {

				Thread.sleep(5000);

				long agora = lotacao.ativas();

				menor = Math.min(menor, agora);

				System.out.println("   t=+" + segundo + "s  ativas=" + agora);
			}

			assertTrue(menor >= 1, "a lotação caiu a zero durante a sustentação");

			System.out.println("mínimo sustentado: " + menor);

		} finally {
			node.close();
		}
	}
}