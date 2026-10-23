package br.com.node.sincronizacao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.cadeia.Crivo;
import br.com.node.catalogo.Catalogo;
import br.com.node.sessao.Lotacao;
import br.com.node.util.Resources;

class CabecaTest {

	private static final int ESPERA_DESCOBERTA = 120;
	private static final int ESPERA_ANUNCIO = 60;

	
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");
	private static final int EM_VOO = Resources.inteiro("sincronizacao.pedidos.em.voo");

	private static final int POR_LOTE = 16;

	
	
	
	
	private static final int AQUECIMENTO = 8;

	private static final int SEGUNDOS_POR_FASE = 90;
	private static final int PASSO_DO_RELATO = 10;

	@Test
	@DisplayName("segue a cabeça sem parar e mede do anúncio ao recibo aplicado, com leque de 1 e de 4")
	void segueACabecaEMedeALatencia() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Cabeca cabeca = new Cabeca();
		Map<Integer, List<Long>> porLeque = new LinkedHashMap<>();

		try {

			node.iniciar();

			Lotacao lotacao = node.lotar(Crivo.corteDeHoje()::aceita, cabeca::anunciar);

			System.out.println("pool: " + node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA) + " sessões ativas");

			assertTrue(esperarAnuncio(cabeca), "nenhum NewBlockHashes em " + ESPERA_ANUNCIO + "s com o pool ativo");

			for (int leque : List.of(1, 4)) {
				porLeque.put(leque, fase(node, lotacao, cabeca, leque));
			}

			System.out.println();
			System.out.println("quedas do pool: " + lotacao.quedas());
			System.out.println("códigos não tratados: " + lotacao.naoTratadas());
			System.out.println("anúncios ignorados por salto: " + cabeca.ignorados());

		} finally {
			node.close();
		}

		porLeque.forEach((leque, amostras) -> assertTrue(!amostras.isEmpty(),
				"com leque " + leque + " o cursor não alcançou a cabeça nenhuma vez"));
	}

	private List<Long> fase(NodeDeTeste node, Lotacao lotacao, Cabeca cabeca, int leque) throws InterruptedException {

		int antes = cabeca.latenciasEmMilis().size();

		try (Frente frente = new Frente(node.sessoes, POR_LOTE);
				Cursor cursor = new Cursor(frente, new Catalogo(), transicao -> {
				})) {

			frente.abrir(cursor::montar, EM_VOO);

			long partida = cabeca.alvo() - AQUECIMENTO;

			Thread seguidor = new Thread(() -> cursor.seguir(partida, cabeca, POR_LOTE, leque),
					"cursor-leque-" + leque);

			seguidor.start();

			for (int segundo = PASSO_DO_RELATO; segundo <= SEGUNDOS_POR_FASE; segundo += PASSO_DO_RELATO) {

				Thread.sleep(PASSO_DO_RELATO * 1000L);

				System.out.printf("   leque %d · t=+%ds · ativas=%d · alvo=%d · cursor=%d · atrás=%d%n", leque, segundo,
						lotacao.ativas(), cabeca.alvo(), cursor.altura(), cabeca.alvo() - cursor.altura());
			}

			cursor.close();
			seguidor.join(5000);

			List<Long> todas = cabeca.latenciasEmMilis();
			List<Long> daFase = new ArrayList<>(todas.subList(Math.min(antes, todas.size()), todas.size()));

			relatar(leque, daFase, cursor, frente);

			return daFase;
		}
	}

	private void relatar(int leque, List<Long> amostras, Cursor cursor, Frente frente) {

		System.out.println();

		if (amostras.isEmpty()) {
			System.out.println("leque " + leque + ": nenhuma amostra — o cursor não alcançou a cabeça");
			return;
		}

		List<Long> ordenadas = new ArrayList<>(amostras);
		ordenadas.sort(null);

		System.out.printf(
				"leque %d: %d blocos da cabeça · anúncio→recibo aplicado: mediana %d ms · p90 %d ms · máx %d ms%n",
				leque, ordenadas.size(), percentil(ordenadas, 50), percentil(ordenadas, 90),
				ordenadas.get(ordenadas.size() - 1));
		System.out.println("   entregues " + frente.entregues() + " · reatribuídas " + frente.reatribuidas() + " "
				+ frente.motivos() + " · reorganizações " + cursor.reorganizacoes());
	}

	private static long percentil(List<Long> ordenadas, int qual) {
		return ordenadas.get(Math.min(ordenadas.size() - 1, ordenadas.size() * qual / 100));
	}

	private static boolean esperarAnuncio(Cabeca cabeca) throws InterruptedException {

		long fim = System.currentTimeMillis() + ESPERA_ANUNCIO * 1000L;

		while (cabeca.alvo() == 0 && System.currentTimeMillis() < fim) {
			Thread.sleep(500);
		}

		return cabeca.alvo() > 0;
	}
}