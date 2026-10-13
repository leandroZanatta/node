package br.com.node.descoberta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.vo.NoRemoto;

// Nenhuma parte isolada deste módulo estava errada no projeto anterior: o envelope
// assinava certo, o RLP fechava round-trip, a tabela ordenava bem — e o node não
// achava um peer sequer, porque descartava o datagrama de quem não estava num mapa
// e nunca respondia FindNode. Teste de unidade passa inteiro num node invisível na
// DHT. Só a rede real reprova isso.
class DescobertaTest {

	private static final int ESPERA_SEGUNDOS = 60;

	@Test
	@DisplayName("encontra um peer novo na rede a partir das sementes")
	void encontraPeerNovo() throws InterruptedException {

		Set<String> jaConhecidos = new HashSet<>();
		Queue<NoRemoto> novos = new ConcurrentLinkedQueue<>();
		CountDownLatch primeiro = new CountDownLatch(1);

		NodeDeTeste node = new NodeDeTeste(no -> {

			if (jaConhecidos.add(no.idHex())) {
				novos.add(no);
				primeiro.countDown();
			}
		});

		node.sementes.forEach(semente -> jaConhecidos.add(semente.idHex()));

		try {

			node.iniciar();

			assertTrue(primeiro.await(ESPERA_SEGUNDOS, TimeUnit.SECONDS), "nenhum peer novo em " + ESPERA_SEGUNDOS
					+ "s — sessões provadas: " + node.descoberta.prontas() + ", sessões: " + node.sessoes.quantas());

			assertFalse(novos.isEmpty(), "o contador disparou sem nó novo na fila");

			System.out.println("descobertos: " + novos.size() + " · primeiro: " + novos.peek());

		} finally {
			node.close();
		}
	}
}
