package br.com.node.transformacao.mempool;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.nucleo.Arbitragem;
import br.com.node.transformacao.nucleo.Passo;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.baixa.p2p.Sessao;
import br.com.node.vo.GetPooledTransactions;
import br.com.node.vo.Log;
import br.com.node.vo.NewPooledTransactionHashes;
import br.com.node.vo.PooledTransactions;
import br.com.node.vo.SwapPendente;
import br.com.node.vo.Transactions;

public final class ObservatorioMempool implements Closeable {

	private static final int AMOSTRA_POR_ANUNCIO = 64;
	private static final long PRAZO_MS = 1_200;
	private static final int TETO_DEDUP = 200_000;

	private static final int TETO_BUSCAS = 512;

	private static final long TAMANHO_MINIMO = 200;
	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	private final Catalogo catalogo;
	private final Function<byte[], Optional<Integer>> taxaDaPool;
	private final Function<byte[], OptionalInt> espacamentoDaPool;
	private final Function<byte[], Boolean> solidlyStableFn;
	private final BiConsumer<Map<String, Pool>, byte[]> aoProjetar;
	private final DossieWbnb dossie;
	private final AtomicLong anuncios = new AtomicLong();
	private final AtomicLong hashes = new AtomicLong();
	private final AtomicLong pedidos = new AtomicLong();
	private final AtomicLong respostas = new AtomicLong();
	private final AtomicLong falhasPedido = new AtomicLong();

	private final AtomicLong pedidosEnviados = new AtomicLong();
	private final AtomicLong pedidosVazios = new AtomicLong();
	private final Map<String, AtomicLong> falhasPorMotivo = new ConcurrentHashMap<>();
	private final AtomicLong semChamada = new AtomicLong();

	private final br.com.node.util.Amostras esperaNaFila = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras respostaDoPeer = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras projecaoAteDespacho = new br.com.node.util.Amostras(2_000);
	private final AtomicLong transacoesIlegiveis = new AtomicLong();
	private final AtomicLong sessaoMorta = new AtomicLong();
	private final AtomicLong transacoesRecebidas = new AtomicLong();
	private final AtomicLong transacoesDecodificadas = new AtomicLong();
	private final AtomicLong swapsDecodificados = new AtomicLong();
	private final AtomicLong projecoes = new AtomicLong();
	private final AtomicLong correlacoesPorPool = new AtomicLong();
	private final AtomicLong arbitragensConfirmadas = new AtomicLong();
	private final AtomicLong arbitragensWbnb = new AtomicLong();
	private final AtomicLong adiantadas = new AtomicLong();
	private final AtomicLong atrasadas = new AtomicLong();
	private final Set<String> corposPedidos = ConcurrentHashMap.newKeySet();
	private final Map<String, Projecao> ultimaProjecaoPorPool = new ConcurrentHashMap<>();

	private final CadernoDeLayouts caderno = new CadernoDeLayouts(3, 24);
	private final SeletoresVistos seletores = new SeletoresVistos();

	private static final int ANUNCIADAS_MAX = 20_000;
	private final Map<String, TransacaoAnunciada> anunciadasPorHash = java.util.Collections
			.synchronizedMap(new LinkedHashMap<>(1_024, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, TransacaoAnunciada> maisVelha) {
					return size() > ANUNCIADAS_MAX;
				}
			});

	private final ExecutorService buscadores = Executors.newVirtualThreadPerTaskExecutor();

	public static final int ANUNCIADO = 1;
	public static final int CORPO = 2;
	public static final int SWAP = 4;
	public static final int PROJETADO = 8;
	public static final int PULADO_PEQUENO = 16;
	private static final int VISTOS_MAX = 150_000;
	private final Map<String, Integer> vistos = java.util.Collections
			.synchronizedMap(new LinkedHashMap<>(4_096, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Integer> maisVelho) {
					return size() > VISTOS_MAX;
				}
			});

	private void marcar(String hash, int etapa) {
		vistos.merge(hash, etapa, (a, b) -> a | b);
	}

	public int etapas(String hash) {
		return vistos.getOrDefault(hash, 0);
	}

	private final AtomicLong ignoradosPequenos = new AtomicLong();
	private final AtomicLong ignoradosJaConhecidos = new AtomicLong();
	private final AtomicLong largadosPorTeto = new AtomicLong();
	private final AtomicLong liberadosParaOutroPeer = new AtomicLong();

	private static final int POR_PEER = 8;
	private final Map<Sessao, AtomicInteger> emVooPorPeer = new ConcurrentHashMap<>();
	private final AtomicLong largadosPorPeerOcupado = new AtomicLong();

	private static final long ESPERA_MAXIMA_MS = 750;
	private final AtomicLong descartadosPorIdade = new AtomicLong();
	private final AtomicInteger buscasEmVoo = new AtomicInteger();
	private volatile Instant primeiroAnuncio;
	private volatile Instant ultimoAnuncio;

	public ObservatorioMempool(Catalogo catalogo, Function<byte[], Optional<Integer>> taxaDaPool) {
		this(catalogo, taxaDaPool, pool -> OptionalInt.empty(), pool -> false, (overlay, crua) -> {
		});
	}

	public ObservatorioMempool(Catalogo catalogo, Function<byte[], Optional<Integer>> taxaDaPool,
			BiConsumer<Map<String, Pool>, byte[]> aoProjetar) {
		this(catalogo, taxaDaPool, pool -> OptionalInt.empty(), pool -> false, aoProjetar);
	}

	public ObservatorioMempool(Catalogo catalogo, Function<byte[], Optional<Integer>> taxaDaPool,
			Function<byte[], OptionalInt> espacamentoDaPool, BiConsumer<Map<String, Pool>, byte[]> aoProjetar) {
		this(catalogo, taxaDaPool, espacamentoDaPool, pool -> false, aoProjetar);
	}

	public ObservatorioMempool(Catalogo catalogo, Function<byte[], Optional<Integer>> taxaDaPool,
			Function<byte[], OptionalInt> espacamentoDaPool, Function<byte[], Boolean> solidlyStableFn,
			BiConsumer<Map<String, Pool>, byte[]> aoProjetar) {
		this.catalogo = catalogo;
		this.taxaDaPool = taxaDaPool;
		this.espacamentoDaPool = espacamentoDaPool;
		this.solidlyStableFn = solidlyStableFn;
		this.aoProjetar = aoProjetar;
		this.dossie = new DossieWbnb(catalogo);
	}

	public void observar(Sessao sessao, NewPooledTransactionHashes mensagem) {

		Instant agora = Instant.now();
		primeiroAnuncio = primeiroAnuncio == null ? agora : primeiroAnuncio;
		ultimoAnuncio = agora;
		anuncios.incrementAndGet();
		hashes.addAndGet(mensagem.hashes().size());

		if (corposPedidos.size() > TETO_DEDUP)
			corposPedidos.clear();

		if (sessao == null) {
			return;
		}

		List<byte[]> pedir = new ArrayList<>();
		List<byte[]> todos = mensagem.hashes();
		todos.forEach(hash -> marcar(hex(hash), ANUNCIADO));
		long agoraMs = System.currentTimeMillis();
		todos.forEach(hash -> primeiraVisao(hex(hash), agoraMs, "anuncio", sessao));

		for (int i = 0; i < todos.size() && pedir.size() < AMOSTRA_POR_ANUNCIO; i++) {

			long tamanho = mensagem.tamanho(i);

			if (tamanho >= 0 && tamanho < TAMANHO_MINIMO) {
				ignoradosPequenos.incrementAndGet();
				marcar(hex(todos.get(i)), PULADO_PEQUENO);
				continue;
			}

			if (corposPedidos.contains(hex(todos.get(i)))) {
				ignoradosJaConhecidos.incrementAndGet();
				continue;
			}

			pedir.add(todos.get(i));
		}

		if (pedir.isEmpty()) {
			return;
		}

		if (buscasEmVoo.get() >= TETO_BUSCAS) {
			largadosPorTeto.incrementAndGet();
			return;
		}

		AtomicInteger doPeer = emVooPorPeer.computeIfAbsent(sessao, s -> new AtomicInteger());

		if (doPeer.incrementAndGet() > POR_PEER) {
			doPeer.decrementAndGet();
			largadosPorPeerOcupado.incrementAndGet();
			return;
		}

		pedir.forEach(hash -> corposPedidos.add(hex(hash)));
		buscasEmVoo.incrementAndGet();
		long anunciadoEm = System.nanoTime();

		buscadores.submit(() -> {
			try {
				long esperou = (System.nanoTime() - anunciadoEm) / 1_000_000;
				esperaNaFila.anotar(esperou);
				if (esperou > ESPERA_MAXIMA_MS) {
					descartadosPorIdade.incrementAndGet();
					return;
				}
				pedir(sessao, pedir);
			} finally {
				buscasEmVoo.decrementAndGet();
				if (doPeer.decrementAndGet() <= 0) {
					emVooPorPeer.remove(sessao, doPeer);
				}
			}
		});
	}

	public void observar(Sessao sessao, Transactions mensagem) {

		Instant chegada = Instant.now();

		if (lotesEmVoo.incrementAndGet() > LOTES_EM_VOO_MAX) {
			lotesEmVoo.decrementAndGet();
			lotesLargados.incrementAndGet();
			return;
		}

		processadores.execute(() -> {
			long inicio = System.nanoTime();
			try {
				processar(mensagem.transacoes(), chegada, sessao);
			} finally {
				processamentoDoLote.anotar((System.nanoTime() - inicio) / 1_000_000);
				lotesEmVoo.decrementAndGet();
			}
		});
	}

	public void aguardarLotes() {
		long ate = System.currentTimeMillis() + 5_000;
		while (lotesEmVoo.get() > 0 && System.currentTimeMillis() < ate) {
			Thread.onSpinWait();
		}
	}

	private static final int LOTES_EM_VOO_MAX = 256;
	private final ExecutorService processadores = Executors.newVirtualThreadPerTaskExecutor();
	private final AtomicInteger lotesEmVoo = new AtomicInteger();
	private final AtomicLong lotesLargados = new AtomicLong();
	private final br.com.node.util.Amostras processamentoDoLote = new br.com.node.util.Amostras(2_000);

	public void aprenderDe(String hash, List<Log> logs) {

		TransacaoAnunciada anunciada = anunciadasPorHash.get(hash);

		if (anunciada == null || logs.isEmpty()) {
			return;
		}

		List<br.com.node.transformacao.nucleo.Perna> reais = Pareador.pernas(logs, catalogo::par);

		conferirProjetor(anunciada, reais, logs);

		boolean roteadorConhecido = Roteamento.decodificar(anunciada.calldata()).isPresent();

		if (!roteadorConhecido && !reais.isEmpty()) {
			List<ExemploDeMovimento> exemplos = Pareador
					.parear(Map.of(hash, anunciada), Map.of(hash, logs), catalogo::par).stream()
					.filter(e -> !e.pernas().isEmpty()).toList();

			if (!exemplos.isEmpty()) {
				exemplos.forEach(caderno::anotar);
				caderno.revisar();
				exemplosDaSimulacao.addAndGet(exemplos.size());
			}
		}

		caderno.conferir(anunciada.destino(), anunciada.calldata(), reais, true);
	}

	private void conferirProjetor(TransacaoAnunciada anunciada, List<br.com.node.transformacao.nucleo.Perna> reais, List<Log> logs) {

		if (reais.isEmpty()) {
			return;
		}

		Optional<SwapPendente> lido = caderno.ler(anunciada.destino(), anunciada.calldata());
		String origem = "caderno";

		if (lido.isEmpty()) {
			lido = Roteamento.decodificar(anunciada.calldata());
			origem = "roteamento";
		}

		if (lido.isEmpty()) {
			return;
		}

		long[] conta = conferenciaDoProjetor.computeIfAbsent(origem, k -> new long[5]);

		synchronized (conta) {

			conta[0]++;

			List<byte[]> caminho = lido.get().caminho();
			boolean caminhoCerto = caminho.size() == reais.size() + 1;
			for (int k = 0; caminhoCerto && k <= reais.size(); k++) {
				byte[] esperado = k == 0 ? reais.get(0).entra() : reais.get(k - 1).sai();
				caminhoCerto = Arrays.equals(caminho.get(k), esperado);
			}

			List<byte[]> pools = Roteamento.mapearParaPools(lido.get(), catalogo, anunciada.destino());
			boolean semPool = pools.isEmpty();
			boolean poolsCertas = !semPool && pools.size() == reais.size();
			for (int i = 0; poolsCertas && i < reais.size(); i++) {
				poolsCertas = Arrays.equals(pools.get(i), reais.get(i).pool());
			}

			java.math.BigInteger entradaReal = entradaDaPrimeiraTroca(logs);
			boolean montanteCerto = entradaReal.signum() > 0 && lido.get().entra().equals(entradaReal);

			if (caminhoCerto) {
				conta[1]++;
			}
			if (poolsCertas) {
				conta[2]++;
			}
			if (semPool) {
				conta[3]++;
			}
			if (montanteCerto) {
				conta[4]++;
			}
		}
	}

	private static java.math.BigInteger entradaDaPrimeiraTroca(List<Log> logs) {

		for (Log log : logs) {
			if (log.assinadoPor(br.com.node.transformacao.catalogo.Eventos.SWAP_V2)) {
				java.math.BigInteger a = br.com.node.transformacao.catalogo.Eventos.palavra(log, 0);
				return a.signum() > 0 ? a : br.com.node.transformacao.catalogo.Eventos.palavra(log, 1);
			}
			if (br.com.node.transformacao.catalogo.Eventos.ehSwapV3(log)) {
				java.math.BigInteger a = br.com.node.transformacao.catalogo.Eventos.palavraComSinalGrande(log, 0);
				return a.signum() > 0 ? a : br.com.node.transformacao.catalogo.Eventos.palavraComSinalGrande(log, 1);
			}
		}

		return java.math.BigInteger.ZERO;
	}

	private final Map<String, long[]> conferenciaDoProjetor = new ConcurrentHashMap<>();

	private final AtomicLong exemplosDaSimulacao = new AtomicLong();

	private volatile java.util.function.BiConsumer<String, byte[]> aoNaoDecodificar = (hash, crua) -> {
	};
	private volatile java.util.function.BiConsumer<String, byte[]> aoValidarDecodificado = (hash, crua) -> {
	};

	public void aoNaoDecodificar(java.util.function.BiConsumer<String, byte[]> simulador) {
		this.aoNaoDecodificar = simulador;
	}

	public void aoValidarDecodificado(java.util.function.BiConsumer<String, byte[]> simulador) {
		this.aoValidarDecodificado = simulador;
	}

	public void blocoFechado(Map<String, List<Log>> logsPorTx) {

		Map<String, TransacaoAnunciada> anunciadas;
		synchronized (anunciadasPorHash) {
			anunciadas = new java.util.HashMap<>(anunciadasPorHash);
		}
		List<ExemploDeMovimento> exemplos = Pareador.parear(anunciadas, logsPorTx, catalogo::par);

		if (!exemplos.isEmpty()) {
			exemplos.forEach(caderno::anotar);
			caderno.revisar();
		}

		logsPorTx.forEach((hash, logs) -> {

			TransacaoAnunciada anunciada = anunciadas.get(hash);

			if (anunciada == null || logs == null || logs.isEmpty()) {
				return;
			}

			List<byte[]> tokens = new ArrayList<>();
			for (Log log : logs) {
				catalogo.par(log.endereco()).ifPresent(par -> {
					juntar(tokens, par.token0());
					juntar(tokens, par.token1());
				});
			}

			if (tokens.size() >= 2) {
				caderno.conferir(anunciada.destino(), anunciada.calldata(), tokens);
			}
		});

		anunciadas.keySet().forEach(anunciadasPorHash::remove);
	}

	private static void juntar(List<byte[]> tokens, byte[] token) {

		for (byte[] ja : tokens) {
			if (Arrays.equals(ja, token)) {
				return;
			}
		}

		tokens.add(token);
	}

	public Optional<SwapPendente> lerDoCaderno(byte[] contrato, byte[] calldata) {
		return caderno.ler(contrato, calldata);
	}

	public Map<String, long[]> placarDoCaderno() {
		return caderno.placar();
	}

	public void confirmar(Arbitragem arbitragem, long altura) {

		Instant agora = Instant.now();
		arbitragensConfirmadas.incrementAndGet();
		Optional<Projecao> correlata = correlata(arbitragem, agora);
		Long adiantamentoMs = null;

		if (correlata.isPresent()) {
			correlacoesPorPool.incrementAndGet();
			adiantamentoMs = Duration.between(correlata.get().instante(), agora).toMillis();
			if (adiantamentoMs >= 0) {
				adiantadas.incrementAndGet();
			} else {
				atrasadas.incrementAndGet();
			}
		}

		if (java.util.Arrays.equals(arbitragem.token(), WBNB)) {
			arbitragensWbnb.incrementAndGet();
			dossie.registrar(arbitragem, altura, agora, correlata.orElse(null), adiantamentoMs);
		}
	}

	private static final int VISOES_MAX = 60_000;

	private record Visao(long ms, String via, String peer) {
	}

	private final Map<String, Visao> visoes = java.util.Collections
			.synchronizedMap(new java.util.LinkedHashMap<>(VISOES_MAX, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Visao> velha) {
					return size() > VISOES_MAX;
				}
			});
	private final java.util.Set<String> swapsVistos = ConcurrentHashMap.newKeySet();
	private final br.com.node.util.Amostras margemAnunciada = new br.com.node.util.Amostras(5_000);
	private final br.com.node.util.Amostras margemEmpurrada = new br.com.node.util.Amostras(5_000);
	private final AtomicLong swapsVistosAntesDoBloco = new AtomicLong();
	private final AtomicLong swapsVistosDepoisDoBloco = new AtomicLong();
	private final Map<String, AtomicLong> primeirosPorPeer = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> primeirosAntesDoBlocoPorPeer = new ConcurrentHashMap<>();

	public java.util.OptionalLong primeiraVisaoMs(String hash) {
		Visao visao = visoes.get(hash);
		return visao == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(visao.ms());
	}

	private void primeiraVisao(String hash, long ms, String via, Sessao sessao) {
		Visao antes = visoes.putIfAbsent(hash,
				new Visao(ms, via, sessao == null ? "?" : sessao.no().idHex().substring(0, 16)));
		if (antes == null && sessao != null) {
			sessao.anotarPrimeiroEntregue();
		}
	}

	private volatile java.util.function.BiConsumer<Long, List<java.math.BigInteger>> aoBlocoComPrecos = (altura,
			precos) -> {
	};

	public void aoBlocoComPrecos(java.util.function.BiConsumer<Long, List<java.math.BigInteger>> ouvinte) {
		this.aoBlocoComPrecos = ouvinte;
	}

	public void blocoAnunciado(br.com.node.vo.NewBlock bloco) {

		long instanteDoBloco = bloco.cabecalho().instanteMs();

		if (instanteDoBloco <= 0) {
			return;
		}

		long altura = bloco.cabecalho().numero();
		java.util.function.BiConsumer<Long, List<java.math.BigInteger>> ouvinte = aoBlocoComPrecos;
		processadores.execute(() -> {
			List<java.math.BigInteger> precos = new ArrayList<>(bloco.transacoes().size());
			for (byte[] crua : bloco.transacoes()) {
				precos.add(TransacaoPendente.precoPorGas(crua).orElse(null));
			}
			ouvinte.accept(altura, precos);
		});

		processadores.execute(() -> {
			for (byte[] crua : bloco.transacoes()) {
				try {
					TransacaoPendente.decodificar(crua).ifPresent(tx -> {
						String hash = hex(tx.hash());
						mineradas.add(hash);
						if (!swapsVistos.remove(hash)) {
							return;
						}
						Visao visao = visoes.get(hash);
						if (visao == null) {
							return;
						}
						long margem = instanteDoBloco - visao.ms();
						("empurrada".equals(visao.via()) ? margemEmpurrada : margemAnunciada).anotar(margem);
						(margem > 0 ? swapsVistosAntesDoBloco : swapsVistosDepoisDoBloco).incrementAndGet();
						primeirosPorPeer.computeIfAbsent(visao.peer(), k -> new AtomicLong()).incrementAndGet();
						if (margem > 0) {
							primeirosAntesDoBlocoPorPeer.computeIfAbsent(visao.peer(), k -> new AtomicLong())
									.incrementAndGet();
						}
					});
				} catch (RuntimeException e) {

				}
			}
		});
	}

	private static final int MINERADAS_MAX = 50_000;
	private final Set<String> mineradas = java.util.Collections.newSetFromMap(java.util.Collections
			.synchronizedMap(new java.util.LinkedHashMap<String, Boolean>(MINERADAS_MAX, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> maisVelha) {
					return size() > MINERADAS_MAX;
				}
			}));
	private final AtomicLong jaMineradasNaoProjetadas = new AtomicLong();

	private Map<String, Object> resumoDaChegada() {
		Map<String, Object> resumo = new java.util.LinkedHashMap<>();
		resumo.put("margem_anunciada_ms", margemAnunciada.resumo());
		resumo.put("margem_empurrada_ms", margemEmpurrada.resumo());
		resumo.put("vistos_antes_do_bloco", swapsVistosAntesDoBloco.get());
		resumo.put("vistos_depois_do_bloco", swapsVistosDepoisDoBloco.get());
		resumo.put("ja_mineradas_nao_projetadas", jaMineradasNaoProjetadas.get());
		Map<String, String> peers = new java.util.LinkedHashMap<>();
		primeirosPorPeer.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.limit(12)
				.forEach(e -> peers.put(e.getKey(),
						e.getValue().get() + " primeiros, "
								+ primeirosAntesDoBlocoPorPeer.getOrDefault(e.getKey(), new AtomicLong()).get()
								+ " antes do bloco"));
		resumo.put("quem_entrega_primeiro", peers);
		return resumo;
	}

	public Map<String, Object> resumo() {
		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("anuncios_hashes", anuncios.get());
		resumo.put("hashes_anunciados", hashes.get());
		resumo.put("primeiro_anuncio", primeiroAnuncio == null ? null : primeiroAnuncio.toString());
		resumo.put("ultimo_anuncio", ultimoAnuncio == null ? null : ultimoAnuncio.toString());
		resumo.put("pedidos_corpos", pedidos.get());
		resumo.put("respostas_corpos", respostas.get());
		resumo.put("falhas_pedido_corpos", falhasPedido.get());
		resumo.put("pedidos_enviados", pedidosEnviados.get());
		resumo.put("pedidos_respondidos_vazios", pedidosVazios.get());
		resumo.put("chegada_dos_swaps", resumoDaChegada());
		Map<String, Long> motivos = new LinkedHashMap<>();
		falhasPorMotivo.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.limit(8).forEach(e -> motivos.put(e.getKey(), e.getValue().get()));
		resumo.put("falhas_por_motivo", motivos);
		resumo.put("transacoes_sem_chamada", semChamada.get());
		resumo.put("lotes_7702", Map.of("vistos", lotes7702.get(), "com_swap", lotes7702ComSwap.get(),
				"com_varios_swaps_so_o_primeiro_projetado", lotes7702ComVariosSwaps.get()));
		Map<String, Object> tempos = new LinkedHashMap<>();
		tempos.put("espera_na_fila_ms", esperaNaFila.resumo());
		tempos.put("resposta_do_peer_ms", respostaDoPeer.resumo());
		tempos.put("projecao_ate_despacho_ms", projecaoAteDespacho.resumo());
		resumo.put("linha_do_tempo_n1", tempos);
		resumo.put("pedidos_descartados_por_idade", descartadosPorIdade.get());
		resumo.put("hashes_ignorados_pequenos", ignoradosPequenos.get());
		resumo.put("hashes_ignorados_ja_conhecidos", ignoradosJaConhecidos.get());
		resumo.put("buscas_largadas_por_teto", largadosPorTeto.get());
		resumo.put("hashes_liberados_para_outro_peer", liberadosParaOutroPeer.get());
		resumo.put("broadcast_processamento_do_lote_ms", processamentoDoLote.resumo());
		resumo.put("broadcast_lotes_largados", lotesLargados.get());
		resumo.put("buscas_largadas_por_peer_ocupado", largadosPorPeerOcupado.get());
		resumo.put("caderno_layouts_aprendidos", caderno.aprendidos());
		resumo.put("caderno_exemplos_da_simulacao", exemplosDaSimulacao.get());
		resumo.put("caderno_placar", caderno.placar());

		Map<String, Object> projetor = new LinkedHashMap<>();
		conferenciaDoProjetor.forEach((origem, conta) -> {
			synchronized (conta) {
				projetor.put(origem, Map.of("conferidas", conta[0], "caminho_certo", conta[1], "pools_certas", conta[2],
						"sem_pool_sabida", conta[3], "montante_certo", conta[4]));
			}
		});
		resumo.put("conferencia_do_projetor", projetor);
		resumo.put("transacoes_ilegiveis", transacoesIlegiveis.get());
		resumo.put("pedidos_descartados_sessao_morta", sessaoMorta.get());
		resumo.put("transacoes_recebidas", transacoesRecebidas.get());
		resumo.put("transacoes_decodificadas", transacoesDecodificadas.get());
		resumo.put("swaps_decodificados", swapsDecodificados.get());
		resumo.put("projecoes", projecoes.get());
		resumo.put("correlacoes_com_inclusao", correlacoesPorPool.get());
		resumo.put("arbitragens_confirmadas", arbitragensConfirmadas.get());
		resumo.put("arbitragens_wbnb", arbitragensWbnb.get());
		resumo.put("lucro_wbnb_wei", dossie.lucroWei());
		resumo.put("lucro_bnb", dossie.lucroBnb());
		resumo.put("adiantadas_por_pool", adiantadas.get());
		resumo.put("atrasadas_por_pool", atrasadas.get());
		resumo.put("chegada_antes_medido", adiantadas.get() > 0);
		resumo.put("maiores_arbitragens_wbnb", dossie.itens());
		resumo.put("seletores_nao_decodificados", seletores.top(20));
		resumo.put("seletores_por_contrato", seletores.topPorContrato(20));
		return resumo;
	}

	@Override
	public void close() {
		buscadores.shutdownNow();
		processadores.shutdownNow();
	}

	private void pedir(Sessao sessao, List<byte[]> hashes) {

		if (!sessao.ativa()) {
			sessaoMorta.incrementAndGet();
			hashes.forEach(hash -> corposPedidos.remove(hex(hash)));
			return;
		}

		try {
			pedidos.addAndGet(hashes.size());
			pedidosEnviados.incrementAndGet();
			long pedidoEm = System.nanoTime();
			PooledTransactions resposta = sessao.pedir(
					new GetPooledTransactions(br.com.node.baixa.p2p.Pedidos.proximoId(), hashes), PooledTransactions.class,
					PRAZO_MS);
			respostaDoPeer.anotar((System.nanoTime() - pedidoEm) / 1_000_000);
			respostas.addAndGet(resposta.transacoes().size());
			if (resposta.transacoes().isEmpty()) {
				pedidosVazios.incrementAndGet();
			}
			processar(resposta.transacoes(), Instant.now(), null);

			int naoEntregues = 0;
			for (byte[] hash : hashes) {
				String chave = hex(hash);
				if ((etapas(chave) & CORPO) == 0) {
					corposPedidos.remove(chave);
					naoEntregues++;
				}
			}
			liberadosParaOutroPeer.addAndGet(naoEntregues);
		} catch (RuntimeException e) {
			falhasPedido.incrementAndGet();
			String motivo = e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("[0-9a-fx]{8,}", "…"));
			falhasPorMotivo
					.computeIfAbsent(motivo.length() > 90 ? motivo.substring(0, 90) : motivo, k -> new AtomicLong())
					.incrementAndGet();
			hashes.forEach(hash -> corposPedidos.remove(hex(hash)));
		}
	}

	private void processar(List<byte[]> transacoes, Instant agora, Sessao deQuem) {

		transacoesRecebidas.addAndGet(transacoes.size());

		for (byte[] crua : transacoes) {

			try {
				processarUma(crua, agora, deQuem);
			} catch (RuntimeException e) {
				transacoesIlegiveis.incrementAndGet();
			}
		}
	}

	private void processarUma(byte[] crua, Instant agora, Sessao deQuem) {
		Optional<TransacaoPendente> tx = TransacaoPendente.decodificar(crua);
		if (tx.isEmpty()) {
			return;
		}

		if (deQuem != null) {
			primeiraVisao(hex(tx.get().hash()), agora.toEpochMilli(), "empurrada", deQuem);
		}

		String hash = hex(tx.get().hash());
		corposPedidos.add(hash);
		marcar(hash, CORPO);

		if (tx.get().destino() == null || tx.get().destino().length != 20 || tx.get().entrada() == null
				|| tx.get().entrada().length < 4) {
			semChamada.incrementAndGet();
			return;
		}

		transacoesDecodificadas.incrementAndGet();

		anunciadasPorHash.putIfAbsent(hash, new TransacaoAnunciada(tx.get().destino(), tx.get().entrada()));

		if (mineradas.contains(hash)) {
			jaMineradasNaoProjetadas.incrementAndGet();
			return;
		}

		byte[] roteador = tx.get().destino();
		Optional<SwapPendente> swap = caderno.ler(tx.get().destino(), tx.get().entrada());
		if (swap.isEmpty()) {
			swap = Roteamento.decodificar(tx.get().entrada(), tx.get().valor());
		}
		if (swap.isEmpty()) {
			Optional<LoteEip7702.Chamada> interna = swapDentroDoLote(tx.get().entrada());
			if (interna.isPresent()) {
				swap = Roteamento.decodificar(interna.get().dados(), interna.get().valor());
				roteador = interna.get().alvo();
			}
		}

		if (swap.isEmpty()) {
			swap = RoteamentoUniversal.decodificarComoV2(tx.get().entrada());
		}

		if (swap.isEmpty()) {
			swap = RoteadorAgregador.decodificar(tx.get().entrada());
		}

		if (swap.isEmpty()) {
			Optional<SwapPendente> bstocks = RoteadorBStocks.decodificar(tx.get().entrada(), catalogo::par);
			if (bstocks.isPresent() && !bstocks.get().pools().isEmpty()) {
				byte[] bPool = bstocks.get().pools().get(0);
				Optional<Integer> bFee = catalogo.estado(bPool).isPresent() ? taxaDaPool.apply(bPool)
						: Optional.empty();
				if (bFee.isPresent()) {
					RoteamentoUniversal.SwapV3 swapV3bS = new RoteamentoUniversal.SwapV3(bstocks.get().entra(),
							bstocks.get().caminho(), List.of(bFee.get()));
					swapsDecodificados.incrementAndGet();
					swapsVistos.add(hash);
					marcar(hash, SWAP);
					aoValidarDecodificado.accept(hash, crua);
					Map<String, Pool> overlayBs = RoteamentoUniversal.projetar(swapV3bS, catalogo, taxaDaPool,
							espacamentoDaPool);
					if (!overlayBs.isEmpty()) {
						List<byte[]> poolsBs = overlayBs.keySet().stream().map(HEX::parseHex).toList();
						projecoes.incrementAndGet();
						marcar(hash, PROJETADO);
						Projecao projecaoBs = new Projecao(hash, agora, poolsBs, swapV3bS.entra(),
								java.math.BigInteger.ZERO);
						poolsBs.forEach(p -> ultimaProjecaoPorPool.put(hex(p), projecaoBs));
						long projetadoEmBs = System.nanoTime();
						try {
							aoProjetar.accept(overlayBs, crua);
						} catch (RuntimeException e) {
							br.com.node.util.Falhas.anotar("projeção bStocks V3", e);
						}
						projecaoAteDespacho.anotar((System.nanoTime() - projetadoEmBs) / 1_000_000);
					}
					return;
				}

				if (catalogo.estado(bPool).isPresent()) {
					swapsDecodificados.incrementAndGet();
					swapsVistos.add(hash);
					marcar(hash, SWAP);
					return;
				}
			}
			swap = bstocks;
		}

		if (swap.isEmpty()) {
			Optional<RoteamentoUniversal.SwapV3> swapV3 = RoteamentoUniversal.decodificar(tx.get().entrada());
			if (swapV3.isPresent()) {
				swapsDecodificados.incrementAndGet();
				swapsVistos.add(hash);
				marcar(hash, SWAP);
				aoValidarDecodificado.accept(hash, crua);
				Map<String, Pool> overlayV3 = RoteamentoUniversal.projetar(swapV3.get(), catalogo, taxaDaPool,
						espacamentoDaPool);
				if (!overlayV3.isEmpty()) {
					List<byte[]> poolsV3 = overlayV3.keySet().stream().map(HEX::parseHex).toList();
					projecoes.incrementAndGet();
					marcar(hash, PROJETADO);
					Projecao projecao = new Projecao(hash, agora, poolsV3, swapV3.get().entra(),
							java.math.BigInteger.ZERO);
					poolsV3.forEach(pool -> ultimaProjecaoPorPool.put(hex(pool), projecao));
					long projetadoEm = System.nanoTime();
					try {
						aoProjetar.accept(overlayV3, crua);
					} catch (RuntimeException e) {
						br.com.node.util.Falhas.anotar("projeção N+1 V3", e);
					}
					projecaoAteDespacho.anotar((System.nanoTime() - projetadoEm) / 1_000_000);
				}
				return;
			}
			seletores.anotar(tx.get().entrada(), tx.get().destino());
			aoNaoDecodificar.accept(hash, crua);
			return;
		}

		swapsDecodificados.incrementAndGet();
		swapsVistos.add(hash);
		marcar(hash, SWAP);

		aoValidarDecodificado.accept(hash, crua);
		Optional<List<Passo>> passos = Roteamento.projetar(swap.get(), catalogo, taxaDaPool, roteador, solidlyStableFn);
		if (passos.isEmpty()) {
			return;
		}

		List<byte[]> pools = Roteamento.mapearParaPools(swap.get(), catalogo, roteador);
		if (pools.isEmpty()) {
			return;
		}

		projecoes.incrementAndGet();
		marcar(hash, PROJETADO);
		Projecao projecao = new Projecao(hash, agora, pools, swap.get().entra(), passos.get().getLast().saida());
		pools.forEach(pool -> ultimaProjecaoPorPool.put(hex(pool), projecao));

		Map<String, Pool> overlay = new LinkedHashMap<>();
		for (int i = 0; i < pools.size(); i++) {
			overlay.put(hex(pools.get(i)), passos.get().get(i).depois());
		}
		if (RoteamentoUniversal.ehUniversalRouter(tx.get().entrada())) {
			RoteamentoUniversal.decodificar(tx.get().entrada()).ifPresent(swapV3 -> {
				Map<String, Pool> overlayV3 = RoteamentoUniversal.projetar(swapV3, catalogo, taxaDaPool,
						espacamentoDaPool);
				overlayV3.forEach((p, estado) -> {
					overlay.put(p, estado);
					ultimaProjecaoPorPool.put(p, projecao);
				});
			});
		}
		long projetadoEm = System.nanoTime();

		try {
			aoProjetar.accept(overlay, crua);
		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("projeção N+1", e);
		}
		projecaoAteDespacho.anotar((System.nanoTime() - projetadoEm) / 1_000_000);
	}

	private Optional<LoteEip7702.Chamada> swapDentroDoLote(byte[] entrada) {

		Optional<List<LoteEip7702.Chamada>> lote;
		try {
			lote = LoteEip7702.desembrulhar(entrada);
		} catch (IllegalArgumentException torto) {
			br.com.node.util.Falhas.anotar("lote 7702", torto);
			return Optional.empty();
		}

		if (lote.isEmpty()) {
			return Optional.empty();
		}

		lotes7702.incrementAndGet();
		List<LoteEip7702.Chamada> comSwap = lote.get().stream()
				.filter(c -> Roteamento.decodificar(c.dados(), c.valor()).isPresent()).toList();

		if (comSwap.size() > 1) {
			lotes7702ComVariosSwaps.incrementAndGet();
		}

		if (!comSwap.isEmpty()) {
			lotes7702ComSwap.incrementAndGet();
		}

		return comSwap.stream().findFirst();
	}

	private final AtomicLong lotes7702 = new AtomicLong();
	private final AtomicLong lotes7702ComSwap = new AtomicLong();
	private final AtomicLong lotes7702ComVariosSwaps = new AtomicLong();

	private Optional<Projecao> correlata(Arbitragem arbitragem, Instant agora) {
		return arbitragem.pools().stream().map(pool -> ultimaProjecaoPorPool.get(hex(pool)))
				.filter(projecao -> projecao != null).min(Comparator
						.comparingLong(projecao -> Math.abs(Duration.between(projecao.instante(), agora).toMillis())));
	}

	private static final HexFormat HEX = HexFormat.of();

	private static String hex(byte[] bytes) {
		return HEX.formatHex(bytes);
	}
}