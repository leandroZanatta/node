package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.cadeia.Crivo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.ReservasV2;
import br.com.node.sincronizacao.Cursor;
import br.com.node.sincronizacao.Frente;
import br.com.node.util.Resources;
import br.com.node.vo.ProvaDeTaxa;

class TaxaSobSuspeitaTest {

	private static final byte[] SUSPEITA = HexFormat.of().parseHex("7a3eb6e7ffbf9f8b65afd40c0ab0ac2bab0c5d8e");

	private static final int ESPERA_DESCOBERTA = 120;
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");

	private static final int BLOCOS = 768;
	private static final int POR_LOTE = 16;
	private static final int RECUO = BLOCOS + 100;

	
	private static final int TETO_DA_BUSCA = 100_000;

	@Test
	@DisplayName("a saída que a 7a3eb6e7 aceita diz qual taxa ela cobra, e de quem")
	void julgaATaxaPeloInvariante() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		Taxas taxas = new Taxas();
		Impostos impostos = new Impostos();

		List<TrocaObservada> trocas = new ArrayList<>();
		Map<String, ReservasV2> antesPorTransacao = new HashMap<>();

		
		
		List<ProvaDeTaxa> provas = new ArrayList<>();
		taxas.aoProvar(provas::add);

		int andados;

		try {

			node.iniciar();
			node.lotar(Crivo.corteDeHoje()::aceita);

			System.out.println("pool: " + node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA) + " sessões ativas");

			long cabeca = node.procurarCabeca(ESPERA_DESCOBERTA);

			assertTrue(cabeca > 0, "nenhum par da BSC respondeu um pedido de cabeçalho");

			andados = percorrer(node, catalogo, transicao -> {
				taxas.observar(transicao);
				impostos.observar(transicao);
				registrar(transicao, antesPorTransacao, trocas);
			}, cabeca - RECUO);

		} finally {
			node.close();
		}

		relatarCobertura(andados, catalogo, taxas, impostos);
		relatarProvadores(catalogo, taxas);
		compararRegras(catalogo, taxas, provas);
		relatarFechadas(catalogo, taxas, provas);
		relatarSuspeita(trocas);
		conferirSemeadura(catalogo, taxas, provas);

		assertTrue(andados > 0, "o cursor não avançou bloco nenhum");
		assertTrue(!trocas.isEmpty(), "a 7a3eb6e7 não negociou com estado anterior conhecido: sem troca não há juiz");

		
		
		
		
		
		
		
		assertTrue(
				catalogo.pools().stream().filter(taxas::fechadaAChamadores)
						.noneMatch(pool -> taxas.provadoresPorTaxa(pool).values().stream()
								.flatMap(java.util.Set::stream).anyMatch(Taxas::roteadorPublico)),
				"pool com roteador público entre os chamadores foi tratada como fechada");
		assertTrue(taxas.fechadaAChamadores(SUSPEITA),
				"a 7a3eb6e7 negociou " + trocas.size() + " vezes e não foi reconhecida como fechada a chamadores");
	}

	private int percorrer(NodeDeTeste node, Catalogo catalogo, Consumer<Transicao> aoAplicar, long partida) {

		try (Frente frente = new Frente(node.sessoes, POR_LOTE);
				Cursor cursor = new Cursor(frente, catalogo, aoAplicar)) {

			frente.abrir(cursor::montar, POOL_MINIMO);

			return cursor.percorrer(partida, BLOCOS, POR_LOTE);
		}
	}

	
	
	
	
	
	private void registrar(Transicao transicao, Map<String, ReservasV2> antesPorTransacao,
			List<TrocaObservada> trocas) {

		if (!Arrays.equals(transicao.log().endereco(), SUSPEITA)) {
			return;
		}

		String transacao = transicao.altura() + ":" + transicao.transacao();

		if (transicao.log().assinadoPor(Eventos.SYNC_V2)) {
			transicao.antes().ifPresent(reservas -> antesPorTransacao.put(transacao, reservas));
			return;
		}

		ReservasV2 antes = antesPorTransacao.remove(transacao);

		if (!transicao.log().assinadoPor(Eventos.SWAP_V2) || antes == null) {
			return;
		}

		BigInteger entrada0 = Eventos.palavra(transicao.log(), 0);
		BigInteger entrada1 = Eventos.palavra(transicao.log(), 1);

		if (entrada0.signum() > 0 == entrada1.signum() > 0) {
			return;
		}

		Direcao direcao = entrada0.signum() > 0 ? Direcao.ZERO_PARA_UM : Direcao.UM_PARA_ZERO;
		BigInteger entrada = entrada0.signum() > 0 ? entrada0 : entrada1;
		BigInteger saida = entrada0.signum() > 0 ? Eventos.palavra(transicao.log(), 3)
				: Eventos.palavra(transicao.log(), 2);

		trocas.add(new TrocaObservada(transicao.altura(),
				HexFormat.of().formatHex(Eventos.enderecoDoTopico(transicao.log(), 1)), direcao, entrada, saida,
				antes.taxaQueFecha(direcao, entrada, saida), teto(antes, direcao, entrada, saida)));
	}

	
	
	
	private int teto(ReservasV2 antes, Direcao direcao, BigInteger entrada, BigInteger saida) {

		for (int taxa = 0; taxa <= TETO_DA_BUSCA; taxa += Pool.PONTO_BASE) {

			BigInteger maxima = new PoolV2(antes.reserva0(), antes.reserva1(), taxa).aplicar(direcao, entrada).saida();

			if (maxima.compareTo(saida) < 0) {
				return taxa - Pool.PONTO_BASE;
			}
		}

		return TETO_DA_BUSCA;
	}

	private void relatarCobertura(int blocos, Catalogo catalogo, Taxas taxas, Impostos impostos) {

		int pools = catalogo.poolsV2() + catalogo.poolsV3();

		System.out.println();
		System.out.println("cobertura em " + blocos + " blocos");
		System.out.println("   catálogo: " + catalogo.poolsV2() + " V2, " + catalogo.poolsV3() + " V3 · "
				+ catalogo.poolsDescartadas() + " V3 descartadas por estado impossível");
		System.out.printf("   taxas provadas: %d de %d pools (%.0f%%) · %d ambíguas%n", taxas.quantasProvadas(), pools,
				100.0 * taxas.quantasProvadas() / Math.max(1, pools), taxas.quantasAmbiguas());
		System.out.println("   motivos: " + new TreeMap<>(taxas.motivos()));
		System.out.println("   impostos: " + impostos.quantosLimpos() + " limpos, " + impostos.quantosTributados()
				+ " tributados");
	}

	
	
	
	
	
	
	private void relatarProvadores(Catalogo catalogo, Taxas taxas) {

		System.out.println();
		System.out.println("pools com mais de uma taxa: taxa → chamadores que a provaram → decidida");

		taxas.poolsComMaisDeUmaTaxa().forEach(pool -> {

			Map<Integer, java.util.Set<String>> porTaxa = taxas.provadoresPorTaxa(HexFormat.of().parseHex(pool));

			System.out.println("   0x" + pool + " → "
					+ taxas.de(HexFormat.of().parseHex(pool)).map(String::valueOf).orElse("ambígua"));
			porTaxa.forEach((taxa, quem) -> System.out.println("      " + taxa + " · " + quem.size() + " chamadores · "
					+ quem.stream().limit(3).map(chamador -> "0x" + chamador.substring(0, 8)).toList()));
		});

		Map<String, Integer> distribuicao = new TreeMap<>();

		for (byte[] pool : catalogo.pools()) {

			long chamadores = taxas.provadoresPorTaxa(pool).values().stream().flatMap(java.util.Set::stream).distinct()
					.count();

			if (chamadores > 0) {
				distribuicao.merge(chamadores >= 6 ? "6+" : String.valueOf(chamadores), 1, Integer::sum);
			}
		}

		System.out.println("pools por número de chamadores que provaram taxa: " + distribuicao);

		List<String> fechadas = catalogo.pools().stream().filter(taxas::fechadaAChamadores)
				.map(pool -> "0x" + HexFormat.of().formatHex(pool)).toList();

		System.out.println("pools movimentadas e fechadas a poucos chamadores: " + fechadas.size() + " " + fechadas);
	}

	
	
	
	private void compararRegras(Catalogo catalogo, Taxas taxas, List<ProvaDeTaxa> provas) {

		Map<String, java.util.Set<Integer>> taxasPorPool = new HashMap<>();

		provas.forEach(prova -> taxasPorPool
				.computeIfAbsent(HexFormat.of().formatHex(prova.pool()), pool -> new java.util.HashSet<>())
				.add(prova.taxa()));

		long velhaProva = taxasPorPool.values().stream().filter(conjunto -> conjunto.size() == 1).count();
		long comMaisDeUma = taxasPorPool.size() - velhaProva;
		long novaProva = catalogo.pools().stream().filter(pool -> taxas.de(pool).isPresent()).count();
		long fechadas = catalogo.pools().stream().filter(taxas::fechadaAChamadores).count();
		long precificaveisPelaTaxa = catalogo.pools().stream()
				.filter(pool -> taxas.de(pool).isPresent() && !taxas.fechadaAChamadores(pool)).count();

		System.out.println();
		System.out.println("regras sobre a mesma janela");
		System.out.println(
				"   pools com evidência de taxa: " + taxasPorPool.size() + " · com mais de uma taxa: " + comMaisDeUma);
		System.out.println("   'contradição apaga' provaria:  " + velhaProva);
		System.out
				.println("   'o K ordena' prova:            " + novaProva + " · ambíguas: " + taxas.quantasAmbiguas());
		System.out.println("   e sem as fechadas a chamadores: " + precificaveisPelaTaxa + " (tira " + fechadas + ")");
	}

	
	
	
	private void relatarFechadas(Catalogo catalogo, Taxas taxas, List<ProvaDeTaxa> provas) {

		System.out.println();
		System.out.println("fechadas a chamadores: pool · par · taxa · fechamentos · chamadores");

		for (byte[] pool : catalogo.pools()) {

			if (!taxas.fechadaAChamadores(pool)) {
				continue;
			}

			List<ProvaDeTaxa> dela = provas.stream().filter(prova -> Arrays.equals(prova.pool(), pool)).toList();

			String par = catalogo.par(pool).map(p -> "0x" + HexFormat.of().formatHex(p.token0()).substring(0, 8) + "/0x"
					+ HexFormat.of().formatHex(p.token1()).substring(0, 8)).orElse("?");

			System.out.println("   0x" + HexFormat.of().formatHex(pool) + " · " + par + " · "
					+ taxas.de(pool).map(String::valueOf).orElse("ambígua") + " · "
					+ dela.stream().mapToLong(ProvaDeTaxa::fechamentos).sum() + " · "
					+ dela.stream().map(ProvaDeTaxa::chamador).distinct()
							.map(c -> "0x" + c.substring(0, 8) + (Taxas.roteadorPublico(c) ? "(roteador)" : ""))
							.toList());
		}
	}

	
	
	
	
	
	private void conferirSemeadura(Catalogo catalogo, Taxas taxas, List<ProvaDeTaxa> provas) {

		Map<String, Long> agregadas = new TreeMap<>();

		provas.forEach(prova -> agregadas.merge(
				HexFormat.of().formatHex(prova.pool()) + "|" + prova.taxa() + "|" + prova.chamador(),
				prova.fechamentos(), Long::sum));

		List<ProvaDeTaxa> linhas = agregadas.entrySet().stream().map(entrada -> {
			String[] partes = entrada.getKey().split("\\|");
			return new ProvaDeTaxa(HexFormat.of().parseHex(partes[0]), Integer.parseInt(partes[1]), partes[2],
					entrada.getValue());
		}).toList();

		Taxas semeada = new Taxas();
		semeada.semear(linhas);

		long divergentes = catalogo.pools().stream().filter(pool -> !semeada.de(pool).equals(taxas.de(pool))
				|| semeada.fechadaAChamadores(pool) != taxas.fechadaAChamadores(pool)).count();

		System.out.println();
		System.out.println("semeadura: " + provas.size() + " fechamentos viram " + linhas.size()
				+ " linhas (pool, taxa, chamador) · decisões divergentes: " + divergentes);

		assertTrue(divergentes == 0, divergentes + " pools decidem diferente quando semeadas da evidência guardada");
	}

	private void relatarSuspeita(List<TrocaObservada> trocas) {

		System.out.println();
		System.out.println("7a3eb6e7: " + trocas.size() + " trocas com estado anterior da mesma transação");
		System.out.println("   altura · chamador · direção · taxa exata · teto compatível (milionésimos)");

		trocas.stream().limit(60)
				.forEach(troca -> System.out
						.println("   " + troca.altura() + " · 0x" + troca.chamador() + " · " + troca.direcao() + " · "
								+ troca.exata().map(String::valueOf).orElse("—") + " · " + troca.teto()));

		Map<String, List<TrocaObservada>> porChamador = new TreeMap<>();

		trocas.forEach(troca -> porChamador.computeIfAbsent(troca.chamador(), chave -> new ArrayList<>()).add(troca));

		System.out.println();
		System.out.println("   por chamador: trocas · menor teto · taxas exatas");

		porChamador.forEach((chamador, dele) -> {

			int menorTeto = dele.stream().mapToInt(TrocaObservada::teto).min().orElse(-1);

			Map<Integer, Long> exatas = new TreeMap<>();
			dele.forEach(troca -> troca.exata().ifPresent(taxa -> exatas.merge(taxa, 1L, Long::sum)));

			System.out.println("   0x" + chamador + " · " + dele.size() + " · " + menorTeto + " · " + exatas);
		});
	}
}