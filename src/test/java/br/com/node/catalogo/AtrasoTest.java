package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.baixa.sync.Crivo;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.baixa.sync.Cursor;
import br.com.node.baixa.sync.Frente;
import br.com.node.util.Resources;
import br.com.node.vo.Log;

class AtrasoTest {

	private static final int ESPERA_DESCOBERTA = 120;
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");
	private static final int POR_LOTE = Resources.inteiro("sincronizacao.lote");
	private static final int LEQUE = Resources.inteiro("sincronizacao.leque.cabeca");
	private static final int RECUO_DE_PARTIDA = 64;

	private static final long DURACAO_MS = 20L * 60 * 1000;
	private static final long BLOCO_MS = 750;

	
	
	private final Map<Long, Long> anunciadaEm = new ConcurrentHashMap<>();
	private final Map<Long, Long> aplicadaEm = new ConcurrentHashMap<>();

	
	private final Map<Long, List<byte[]>> arbitragensDeTerceiros = new HashMap<>();

	@Test
	@DisplayName("mede se pegamos as arbitragens de terceiros e por quantos ms/blocos chegamos atrás")
	void medeOAtraso() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		Taxas taxas = new Taxas();
		Detector detector = new Detector(catalogo);
		Cabeca cabeca = new Cabeca();

		
		
		Map<Integer, List<Log>> daAltura = new HashMap<>();
		long[] alturaCorrente = { -1 };

		java.util.function.Consumer<Transicao> aoAplicar = transicao -> {
			taxas.observar(transicao);

			long a = transicao.altura();
			aplicadaEm.putIfAbsent(a, System.currentTimeMillis());

			if (a != alturaCorrente[0]) {
				fecharAltura(detector, alturaCorrente[0], daAltura);
				daAltura.clear();
				alturaCorrente[0] = a;
			}

			daAltura.computeIfAbsent(transicao.transacao(), q -> new ArrayList<>()).add(transicao.log());
		};

		long seguidos;

		try {

			node.iniciar();
			node.lotar(Crivo.corteDeHoje()::aceita, anuncio -> {
				anunciadaEm.putIfAbsent(anuncio.numero(), System.currentTimeMillis());
				cabeca.anunciar(anuncio);
			});

			System.out.println("pool: " + node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA) + " ativas");

			long fim = System.currentTimeMillis() + DURACAO_MS;
			long inicio;
			while ((inicio = cabeca.alvo()) == 0 && System.currentTimeMillis() < fim) {
				Thread.sleep(500);
			}

			if (inicio == 0) {
				System.out.println("ATRASO nenhum anúncio de cabeça — sem rede");
				return;
			}

			seguidos = seguir(node, catalogo, aoAplicar, cabeca, inicio - RECUO_DE_PARTIDA, fim);

		} finally {
			node.close();
		}

		relatar(catalogo, seguidos);
	}

	private long seguir(NodeDeTeste node, Catalogo catalogo, java.util.function.Consumer<Transicao> aoAplicar,
			Cabeca cabeca, long partida, long fim) throws InterruptedException {

		try (Frente frente = new Frente(node.sessoes, POR_LOTE);
				Cursor cursor = new Cursor(frente, NodeDeTeste.aplicador(catalogo, aoAplicar))) {

			frente.abrir(cursor::montar, POOL_MINIMO);

			ExecutorService seguidor = Executors.newSingleThreadExecutor(t -> new Thread(t, "atraso-cursor"));
			seguidor.submit(() -> cursor.seguir(partida, cabeca, POR_LOTE, LEQUE));

			while (System.currentTimeMillis() < fim) {
				Thread.sleep(5000);
				System.out.printf("ATRASO t=%dmin · cabeça=%d · arbitragens de terceiros vistas=%d%n",
						(DURACAO_MS - (fim - System.currentTimeMillis())) / 60000, cabeca.alvo(),
						arbitragensDeTerceiros.size());
			}

			seguidor.shutdownNow();
			seguidor.awaitTermination(10, TimeUnit.SECONDS);

			return cabeca.alvo() - partida;
		}
	}

	private void fecharAltura(Detector detector, long altura, Map<Integer, List<Log>> daAltura) {

		if (altura < 0) {
			return;
		}

		for (List<Log> transacao : daAltura.values()) {
			detector.em(transacao).ifPresent(
					arb -> arbitragensDeTerceiros.computeIfAbsent(altura, q -> new ArrayList<>(arb.pools())));
		}
	}

	private void relatar(Catalogo catalogo, long seguidos) {

		List<Long> atrasos = new ArrayList<>();
		List<Long> atrasosDeArbitragem = new ArrayList<>();
		int poolsConhecidas = 0;
		int totalPools = 0;

		for (Map.Entry<Long, Long> e : aplicadaEm.entrySet()) {
			Long anuncio = anunciadaEm.get(e.getKey());
			if (anuncio != null) {
				atrasos.add(e.getValue() - anuncio);
			}
		}

		for (Map.Entry<Long, List<byte[]>> arb : arbitragensDeTerceiros.entrySet()) {
			Long anuncio = anunciadaEm.get(arb.getKey());
			Long aplicada = aplicadaEm.get(arb.getKey());
			if (anuncio != null && aplicada != null) {
				atrasosDeArbitragem.add(aplicada - anuncio);
			}
			for (byte[] pool : arb.getValue()) {
				totalPools++;
				if (catalogo.conhece(pool)) {
					poolsConhecidas++;
				}
			}
		}

		System.out.println();
		System.out.println("==== ATRASO: pegamos, e por quanto chegamos atrás ====");
		System.out.println(
				"blocos seguidos: " + seguidos + " · arbitragens de terceiros: " + arbitragensDeTerceiros.size());
		System.out.printf("pools dessas arbitragens que CONHECÍAMOS: %d de %d (%.0f%%)%n", poolsConhecidas, totalPools,
				100.0 * poolsConhecidas / Math.max(1, totalPools));

		imprimir("atraso para SABER do bloco (anúncio -> aplicado), TODOS os blocos", atrasos);
		imprimir("atraso nos blocos que continham arbitragem de terceiro", atrasosDeArbitragem);

		System.out.println();
		System.out.println("Leitura: detectamos DEPOIS de aplicar o bloco, e a arbitragem estava DENTRO dele.");
		System.out.println("Mesmo com atraso baixo, agir só é possível no bloco seguinte — pelo menos " + "um bloco ("
				+ BLOCO_MS + " ms) atrás de quem capturou dentro do bloco. É a conta da mempool (P21).");
	}

	private void imprimir(String rotulo, List<Long> ms) {

		if (ms.isEmpty()) {
			System.out.println(rotulo + ": sem amostra");
			return;
		}

		ms.sort(null);

		long mediana = ms.get(ms.size() / 2);
		long p90 = ms.get((int) (ms.size() * 0.9));
		long maior = ms.get(ms.size() - 1);

		System.out.printf("%s: mediana %d ms (%.1f blocos) · p90 %d ms · máx %d ms · n=%d%n", rotulo, mediana,
				mediana / (double) BLOCO_MS, p90, maior, ms.size());
	}
}