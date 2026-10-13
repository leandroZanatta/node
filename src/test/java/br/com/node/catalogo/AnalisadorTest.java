package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.cadeia.Crivo;
import br.com.node.nucleo.Arbitragem;
import br.com.node.sincronizacao.Cursor;
import br.com.node.sincronizacao.Frente;
import br.com.node.util.Resources;
import br.com.node.vo.Log;

// O QUE VALE A PENA ANALISAR — medido, não opinado.
//
// Em vez de escolher onde procurar, este instrumento olha quem JÁ ARBITRA na cadeia,
// agora, e caracteriza: quantas por bloco, quantas pernas, V2 ou V3, em que token sobra,
// quanto sobra, e quais pools repetem. O analisador deve focar onde a arbitragem
// acontece, e isso é observável.
//
// É a resposta que a autópsia não deu: o projeto anterior observou 63.433 arbitragens
// alheias e capturou zero. Saber a FORMA delas é o que diz onde pôr esforço.
class AnalisadorTest {

	private static final int ESPERA_DESCOBERTA = 120;

	// UM PAR POR TRABALHADOR, derivado e não escolhido: a frente abre
	// `sincronizacao.pedidos.em.voo` threads, e cada uma arrenda uma sessão. Com menos par que
	// trabalhador, sobra trabalhador e o pior par volta a ditar o ritmo. Medido: com 4 ativas o
	// `AnalisadorTest` parava com zero blocos de forma intermitente.
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");
	private static final int BLOCOS = 48;
	private static final int POR_LOTE = 16;
	// 400, não 60. Com a frente, uma faixa é servida por QUALQUER par, e par atrás da cabeça
	// devolve cabeçalho vazio para altura recente: com poucos ativos, todos podem estar
	// atrás e a faixa nunca fecha. Recuar mais é pedir o que todo mundo já tem.
	private static final int RECUO = 400;

	private static final String WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
	private static final String USDT = "55d398326f99059ff775485246999027b3197955";
	private static final String BUSD = "e9e7cea3dedca5984780bafc599bd69add087d56";
	private static final String USDC = "8ac76a51cc950d9822d68b83fe1ad97b32cd580d";

	@Test
	@DisplayName("caracteriza a arbitragem que realmente acontece na cadeia")
	void caracterizaAArbitragemReal() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		Detector detector = new Detector(catalogo);

		// Uma passada só não serve: o par de cada pool é aprendido dos `Transfer`, e na
		// primeira vez que a pool aparece o par ainda não está montado. Guarda-se a
		// transação e julga-se no fim, com o catálogo completo.
		Map<String, List<Log>> porTransacao = new java.util.LinkedHashMap<>();

		int avancados = 0;

		try {

			node.iniciar();

			// A lotação é obrigatória, não conveniência de teste: `pedir` correlaciona
			// dentro do LEITOR, e o leitor é dela. Sem pool, a frente pagaria um aperto de
			// mão por faixa — foi o que deu 3,3 blocos/s.
			node.lotar(Crivo.corteDeHoje()::aceita);

			long ativas = node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA);

			System.out.println("pool: " + ativas + " sessões ativas");

			long cabeca = node.procurarCabeca(ESPERA_DESCOBERTA);

			assertTrue(cabeca > 0, "nenhum par da BSC respondeu");

			try (Frente frente = new Frente(node.sessoes, POR_LOTE);
					Cursor cursor = new Cursor(frente, catalogo,
							t -> porTransacao.computeIfAbsent(t.altura() + ":" + t.transacao(), k -> new ArrayList<>())
									.add(t.log()))) {

				frente.abrir(cursor::montar, Resources.inteiro("sincronizacao.pedidos.em.voo"));

				avancados = cursor.percorrer(cabeca - RECUO, BLOCOS, POR_LOTE);
			}

		} finally {
			node.close();
		}

		assertTrue(avancados > 0, "o cursor não avançou");

		List<Arbitragem> arbitragens = new ArrayList<>();

		for (List<Log> transacao : porTransacao.values()) {
			detector.em(transacao).ifPresent(arbitragens::add);
		}

		relatar(avancados, porTransacao.size(), catalogo, arbitragens);

		// O detector estrito é a produção; isto é o instrumento. Ele caracteriza a
		// POPULAÇÃO de transações com mais de um swap, porque é ela que diz onde há
		// oportunidade — e o detector estrito, por exigir intermediário exatamente zero,
		// descarta todo ciclo por token com imposto, que é onde o dinheiro da autópsia
		// estava.
		caracterizarPopulacao(catalogo, porTransacao);

		assertTrue(avancados >= BLOCOS / 2, "o cursor não percorreu a faixa");
	}

	private void relatar(int blocos, int transacoes, Catalogo catalogo, List<Arbitragem> arbitragens) {

		System.out.println();
		System.out.println("=== " + blocos + " blocos · " + transacoes + " transações com log · " + catalogo.poolsV2()
				+ " pools V2 · " + catalogo.poolsV3() + " pools V3 · " + catalogo.paresConhecidos() + " pares ===");

		System.out.println();
		System.out.printf("arbitragens detectadas: %d  (%.2f por bloco)%n", arbitragens.size(),
				(double) arbitragens.size() / blocos);

		Map<Integer, Integer> porPernas = new HashMap<>();
		Map<String, Integer> porComposicao = new HashMap<>();
		Map<String, Integer> porToken = new HashMap<>();
		Map<String, Integer> porPool = new HashMap<>();
		Map<String, BigInteger> sobraPorToken = new HashMap<>();

		for (Arbitragem a : arbitragens) {

			porPernas.merge(a.pernas(), 1, Integer::sum);
			porComposicao.merge(composicao(a), 1, Integer::sum);

			String token = HexFormat.of().formatHex(a.token());

			porToken.merge(nome(token), 1, Integer::sum);
			sobraPorToken.merge(nome(token), a.sobra(), BigInteger::add);

			a.pools().forEach(pool -> porPool.merge(HexFormat.of().formatHex(pool), 1, Integer::sum));
		}

		System.out.println();
		System.out.println("por número de pernas: " + porPernas);
		System.out.println("por composição:       " + porComposicao);
		System.out.println("token em que sobra:   " + porToken);

		System.out.println();
		System.out.println("sobra acumulada por token (unidades do próprio token):");
		sobraPorToken.forEach((token, sobra) -> System.out.println("   " + token + ": " + emUnidades(sobra)));

		System.out.println();
		System.out.println("as 8 pools que mais aparecem em arbitragem:");
		porPool.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
				.forEach(e -> System.out.println("   " + e.getKey() + "  " + e.getValue() + " vezes"));

		long emNumerario = arbitragens.stream().filter(a -> ehNumerario(HexFormat.of().formatHex(a.token()))).count();

		System.out.println();
		System.out.printf("sobra num numerário (WBNB/USDT/BUSD/USDC): %d de %d  (%.0f%%)%n", emNumerario,
				arbitragens.size(), 100.0 * emNumerario / arbitragens.size());
	}

	private void caracterizarPopulacao(Catalogo catalogo, Map<String, List<Log>> porTransacao) {

		int comSwap = 0;
		int multiSwap = 0;
		int poolDesconhecida = 0;
		int arbitragemLimpa = 0;
		int arbitragemComPerda = 0;
		int trocaRoteada = 0;
		int outro = 0;

		List<BigInteger> sobrasEmWbnb = new ArrayList<>();
		Map<Integer, Integer> swapsPorTx = new HashMap<>();

		for (List<Log> transacao : porTransacao.values()) {

			List<Log> swaps = new ArrayList<>();

			for (Log log : transacao) {

				if (log.assinadoPor(Eventos.SWAP_V2) || Eventos.ehSwapV3(log)) {
					swaps.add(log);
				}
			}

			if (swaps.isEmpty()) {
				continue;
			}

			comSwap++;
			swapsPorTx.merge(swaps.size(), 1, Integer::sum);

			if (swaps.size() < 2) {
				continue;
			}

			multiSwap++;

			Map<String, BigInteger> liquido = new HashMap<>();
			Map<String, BigInteger> bruto = new HashMap<>();
			boolean todasConhecidas = true;

			for (Log swap : swaps) {

				var par = catalogo.par(swap.endereco()).orElse(null);

				if (par == null) {
					todasConhecidas = false;
					break;
				}

				boolean ehV2 = swap.assinadoPor(Eventos.SWAP_V2);

				BigInteger delta0 = ehV2 ? Eventos.palavra(swap, 2).subtract(Eventos.palavra(swap, 0))
						: Eventos.palavraComSinalGrande(swap, 0).negate();
				BigInteger delta1 = ehV2 ? Eventos.palavra(swap, 3).subtract(Eventos.palavra(swap, 1))
						: Eventos.palavraComSinalGrande(swap, 1).negate();

				String t0 = HexFormat.of().formatHex(par.token0());
				String t1 = HexFormat.of().formatHex(par.token1());

				liquido.merge(t0, delta0, BigInteger::add);
				liquido.merge(t1, delta1, BigInteger::add);
				bruto.merge(t0, delta0.abs(), BigInteger::add);
				bruto.merge(t1, delta1.abs(), BigInteger::add);
			}

			if (!todasConhecidas) {
				poolDesconhecida++;
				continue;
			}

			int positivos = 0;
			int negativosGrandes = 0;
			int negativosPequenos = 0;
			String ganhador = null;

			for (Map.Entry<String, BigInteger> entrada : liquido.entrySet()) {

				int sinal = entrada.getValue().signum();

				if (sinal > 0) {
					positivos++;
					ganhador = entrada.getKey();
				} else if (sinal < 0) {

					// Perda pequena diante do que atravessou aquele token é imposto de
					// transferência, não gasto: cinco por cento cobre os tokens taxados que
					// apareceram na autópsia (100 a 200 bps, com folga).
					BigInteger limite = bruto.get(entrada.getKey()).divide(BigInteger.valueOf(20));

					if (entrada.getValue().abs().compareTo(limite) <= 0) {
						negativosPequenos++;
					} else {
						negativosGrandes++;
					}
				}
			}

			if (positivos == 1 && negativosGrandes == 0 && negativosPequenos == 0) {
				arbitragemLimpa++;
				juntarSeWbnb(sobrasEmWbnb, ganhador, liquido);
			} else if (positivos == 1 && negativosGrandes == 0) {
				arbitragemComPerda++;
				juntarSeWbnb(sobrasEmWbnb, ganhador, liquido);
			} else if (positivos == 1 && negativosGrandes == 1) {
				trocaRoteada++;
			} else {
				outro++;
			}
		}

		System.out.println();
		System.out.println("=== população de transações com swap ===");
		System.out.println("   com pelo menos um swap: " + comSwap);
		System.out.println("   com dois ou mais swaps: " + multiSwap);
		System.out.println("   swaps por transação:    " + swapsPorTx);
		System.out.println();
		System.out.println("   das multi-swap:");
		System.out.println("      pool desconhecida (cegueira nossa): " + poolDesconhecida);
		System.out.println("      ARBITRAGEM limpa (intermediário zero): " + arbitragemLimpa);
		System.out.println("      ARBITRAGEM com perda pequena (imposto): " + arbitragemComPerda);
		System.out.println("      troca roteada de usuário: " + trocaRoteada);
		System.out.println("      outro: " + outro);

		if (!sobrasEmWbnb.isEmpty()) {

			sobrasEmWbnb.sort(null);

			System.out.println();
			System.out.println("   sobra em WBNB das arbitragens (BNB): menor=" + emUnidades(sobrasEmWbnb.get(0))
					+ " mediana=" + emUnidades(sobrasEmWbnb.get(sobrasEmWbnb.size() / 2)) + " maior="
					+ emUnidades(sobrasEmWbnb.get(sobrasEmWbnb.size() - 1)) + "  (n=" + sobrasEmWbnb.size() + ")");
			System.out.println("   gas de uma execução ~ 0.000300 BNB");
		}
	}

	private void juntarSeWbnb(List<BigInteger> sobras, String ganhador, Map<String, BigInteger> liquido) {

		if (WBNB.equals(ganhador)) {
			sobras.add(liquido.get(ganhador));
		}
	}

	private static String composicao(Arbitragem a) {
		return a.v3() == 0 ? "só V2" : a.v2() == 0 ? "só V3" : "mista";
	}

	private static boolean ehNumerario(String token) {
		return WBNB.equals(token) || USDT.equals(token) || BUSD.equals(token) || USDC.equals(token);
	}

	// Nome só para o relatório, e por ENDEREÇO: há cerca de 180 tokens chamados "USDT"
	// no catálogo antigo, e um só é o real.
	private static String nome(String token) {

		if (WBNB.equals(token)) {
			return "WBNB";
		}

		if (USDT.equals(token)) {
			return "USDT";
		}

		if (BUSD.equals(token)) {
			return "BUSD";
		}

		if (USDC.equals(token)) {
			return "USDC";
		}

		return token.substring(0, 10) + "…";
	}

	private static String emUnidades(BigInteger wei) {
		return new BigDecimal(wei).movePointLeft(18).setScale(6, RoundingMode.DOWN).toPlainString();
	}
}
