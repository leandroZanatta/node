package br.com.node.sincronizacao;
import br.com.node.baixa.sync.*;
import br.com.node.baixa.p2p.*;


import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.baixa.sync.Crivo;
import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Eventos;
import br.com.node.transformacao.catalogo.Transicao;
import br.com.node.transformacao.nucleo.Direcao;
import br.com.node.transformacao.nucleo.Grafo;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.transformacao.nucleo.PoolV2;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.transformacao.nucleo.TickMath;
import br.com.node.util.Resources;

class CursorTest {

	private static final int ESPERA_DESCOBERTA = 120;

	
	
	
	
	
	
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");
	private static final int BLOCOS = 128;
	private static final int POR_LOTE = 16;
	private static final int EM_VOO = Resources.inteiro("sincronizacao.pedidos.em.voo");

	
	private static final int RECUO = 40;

	@Test
	@DisplayName("percorre uma faixa contínua e o estado resultante prova taxa redonda")
	void percorreEProvaOEstado() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		List<Transicao> transicoes = new ArrayList<>();

		int avancados = 0;
		long partida = 0;

		try {

			node.iniciar();

			
			
			
			node.lotar(Crivo.corteDeHoje()::aceita);

			
			
			
			
			long ativas = node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA);

			System.out.println("pool: " + ativas + " sessões ativas");

			long cabeca = node.procurarCabeca(ESPERA_DESCOBERTA);

			assertTrue(cabeca > 0, "nenhum par da BSC respondeu um pedido de cabeçalho");

			partida = cabeca - RECUO;

			try (Frente frente = new Frente(node.sessoes, POR_LOTE);
					Cursor cursor = new Cursor(frente, catalogo, transicoes::add)) {

				frente.abrir(cursor::montar, EM_VOO);

				long comecou = System.nanoTime();

				avancados = cursor.percorrer(partida, BLOCOS, POR_LOTE);

				double segundos = (System.nanoTime() - comecou) / 1e9;

				System.out.println(
						"cursor: partiu de " + partida + ", avançou " + avancados + " blocos até " + cursor.altura());
				System.out.printf("vazão: %.1f blocos/s com %d pedidos em voo (%d entregues, %d reatribuídas)%n",
						avancados / segundos, EM_VOO, frente.entregues(), frente.reatribuidas());
				System.out.println("   motivos de reatribuição: " + frente.motivos());
				System.out.printf("   aplicando: %d ms de %.0f ms (%.0f%%) · %.1f ms por bloco%n",
						cursor.milisAplicando(), segundos * 1000, 100.0 * cursor.milisAplicando() / (segundos * 1000),
						cursor.milisAplicando() / (double) avancados);
			}

		} finally {
			node.close();
		}

		assertTrue(avancados > 0, "o cursor não avançou bloco nenhum");

		conferirContinuidade(transicoes, partida, avancados);

		System.out.println("catálogo: " + catalogo.poolsV2() + " pools V2, " + catalogo.poolsV3() + " pools V3");
		System.out.println("transições aplicadas: " + transicoes.size());

		conferirLegibilidade(catalogo, transicoes, partida, avancados);

		provarTaxas(transicoes);

		montarGrafo(catalogo, transicoes);

		provarTickMath(transicoes);
	}

	
	
	
	
	
	
	
	private void provarTickMath(List<Transicao> transicoes) {

		int conferidos = 0;
		int fora = 0;

		for (Transicao t : transicoes) {

			if (!t.log().assinadoPor(Eventos.SWAP_V3)) {
				continue;
			}

			BigInteger sqrt = Eventos.palavra(t.log(), 2);
			int tick = (int) Eventos.palavraComSinal(t.log(), 4);

			if (Math.abs(tick) > TickMath.TICK_MAXIMO) {
				continue;
			}

			conferidos++;

			if (sqrt.compareTo(TickMath.paraSqrt(tick)) < 0 || sqrt.compareTo(TickMath.paraSqrt(tick + 1)) >= 0) {
				fora++;
			}
		}

		System.out.println();
		System.out.println("TickMath: " + conferidos + " swaps V3 conferidos, " + fora + " fora do próprio tick");

		assertTrue(fora == 0, "a TickMath discorda do contrato em " + fora + " de " + conferidos);
	}

	
	
	
	
	private void montarGrafo(Catalogo catalogo, List<Transicao> transicoes) {

		Grafo grafo = new Grafo();
		java.util.Set<String> ligadas = new java.util.HashSet<>();

		for (Transicao t : transicoes) {

			String pool = HexFormat.of().formatHex(t.log().endereco());

			if (!ligadas.contains(pool)) {
				catalogo.par(t.log().endereco()).ifPresent(par -> {
					grafo.ligar(t.log().endereco(), par);
					ligadas.add(pool);
				});
			}
		}

		System.out.println();
		System.out.println("grafo: " + ligadas.size() + " pools ligadas, " + grafo.tokens() + " tokens, de "
				+ catalogo.paresConhecidos() + " pares aprendidos");

		
		
		Map<String, byte[]> numerarios = Map.of("WBNB",
				HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"), "USDT",
				HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955"));

		int total = 0;

		for (Map.Entry<String, byte[]> numerario : numerarios.entrySet()) {

			List<br.com.node.transformacao.nucleo.Ciclo> ciclos = grafo.ciclos(numerario.getValue(), 3);

			long duas = ciclos.stream().filter(c -> c.pernas().size() == 2).count();
			long tres = ciclos.stream().filter(c -> c.pernas().size() == 3).count();

			System.out.println("   de " + numerario.getKey() + ": " + ciclos.size() + " ciclos (" + duas
					+ " de duas pernas, " + tres + " de três)");

			total += ciclos.size();
		}

		assertTrue(total > 0, "o grafo não achou ciclo nenhum a partir dos numerários");
	}

	
	
	
	
	
	private void conferirLegibilidade(Catalogo catalogo, List<Transicao> transicoes, long partida, int avancados) {

		int lidasV2 = 0;
		int lidasV3 = 0;

		for (Transicao t : transicoes) {

			if (t.log().assinadoPor(Eventos.SYNC_V2)) {

				ReservasV2 reservas = catalogo.reservas(t.log().endereco()).orElse(null);

				assertTrue(reservas != null, "pool com Sync não ficou no catálogo");
				assertTrue(reservas.altura() >= partida && reservas.altura() < partida + avancados,
						"reserva gravada fora da faixa percorrida: " + reservas.altura());

				lidasV2++;
			}

			if (t.log().assinadoPor(Eventos.SWAP_V3)) {

				assertTrue(catalogo.estado(t.log().endereco()).isPresent(), "pool V3 com Swap não ficou no catálogo");

				lidasV3++;
			}
		}

		System.out.println("legibilidade: " + lidasV2 + " leituras V2 e " + lidasV3 + " V3, todas com altura na faixa");
	}

	
	private void conferirContinuidade(List<Transicao> transicoes, long partida, int avancados) {

		long anterior = 0;

		for (Transicao t : transicoes) {

			assertTrue(t.altura() >= anterior, "altura andou para trás: " + t.altura() + " depois de " + anterior);
			assertTrue(t.altura() >= partida && t.altura() < partida + avancados,
					"altura fora da faixa percorrida: " + t.altura());

			anterior = t.altura();
		}

		System.out.println("continuidade: ok, alturas de " + partida + " a " + (partida + avancados - 1));
	}

	
	
	
	
	private void provarTaxas(List<Transicao> transicoes) {

		Map<String, ReservasV2> antesDoSwap = new HashMap<>();
		Map<Integer, Integer> taxas = new HashMap<>();

		int conferidos = 0;
		int semFechar = 0;

		for (Transicao t : transicoes) {

			String pool = HexFormat.of().formatHex(t.log().endereco());

			
			
			if (t.log().assinadoPor(Eventos.SYNC_V2)) {
				t.antes().ifPresent(reservas -> antesDoSwap.put(pool, reservas));
				continue;
			}

			if (!t.log().assinadoPor(Eventos.SWAP_V2)) {
				continue;
			}

			ReservasV2 antes = antesDoSwap.remove(pool);

			if (antes == null) {
				continue;
			}

			BigInteger entrada0 = Eventos.palavra(t.log(), 0);
			BigInteger entrada1 = Eventos.palavra(t.log(), 1);
			BigInteger saida0 = Eventos.palavra(t.log(), 2);
			BigInteger saida1 = Eventos.palavra(t.log(), 3);

			if (entrada0.signum() > 0 == entrada1.signum() > 0) {
				continue;
			}

			Direcao direcao = entrada0.signum() > 0 ? Direcao.ZERO_PARA_UM : Direcao.UM_PARA_ZERO;
			BigInteger entrada = entrada0.signum() > 0 ? entrada0 : entrada1;
			BigInteger saida = entrada0.signum() > 0 ? saida1 : saida0;

			if (saida.signum() <= 0) {
				continue;
			}

			conferidos++;

			int taxa = recuperarTaxa(antes, direcao, entrada, saida);

			if (taxa < 0) {
				semFechar++;
			} else {
				taxas.merge(taxa, 1, Integer::sum);
			}
		}

		int redondos = taxas.entrySet().stream().filter(e -> e.getKey() % 100 == 0).mapToInt(Map.Entry::getValue).sum();
		int quebrados = taxas.entrySet().stream().filter(e -> e.getKey() % 100 != 0).mapToInt(Map.Entry::getValue)
				.sum();

		System.out.println();
		System.out.println("swaps com estado anterior conhecido: " + conferidos);
		System.out.println("   igualdade exata com taxa REDONDA: " + redondos);
		System.out.println("   igualdade exata com taxa quebrada: " + quebrados);
		System.out.println("   sem igualdade (chamador pediu menos): " + semFechar);
		System.out.println("   taxas: " + taxas);

		if (redondos + quebrados > 0) {
			assertTrue(redondos > quebrados * 4, "taxa quebrada demais (" + quebrados + " contra " + redondos
					+ " redondas): o estado do cursor não está certo");
		}
	}

	private int recuperarTaxa(ReservasV2 antes, Direcao direcao, BigInteger entrada, BigInteger saida) {

		BigInteger reservaEntra = direcao == Direcao.ZERO_PARA_UM ? antes.reserva0() : antes.reserva1();
		BigInteger reservaSai = direcao == Direcao.ZERO_PARA_UM ? antes.reserva1() : antes.reserva0();

		if (reservaEntra.signum() <= 0 || reservaSai.compareTo(saida) <= 0) {
			return -1;
		}

		BigInteger escala = BigInteger.valueOf(Pool.ESCALA_DA_TAXA);
		BigInteger liquida = saida.multiply(reservaEntra).multiply(escala).divide(reservaSai.subtract(saida));
		BigInteger aproximada = escala.subtract(liquida.divide(entrada));

		if (aproximada.signum() < 0 || aproximada.compareTo(escala) >= 0) {
			return -1;
		}

		int centro = aproximada.intValueExact();

		for (int taxa = Math.max(0, centro - 3); taxa <= Math.min(Pool.ESCALA_DA_TAXA - 1, centro + 3); taxa++) {

			try {

				if (new PoolV2(antes.reserva0(), antes.reserva1(), taxa).aplicar(direcao, entrada).saida()
						.equals(saida)) {
					return taxa;
				}

			} catch (RuntimeException ignorado) {
				return -1;
			}
		}

		return -1;
	}
}