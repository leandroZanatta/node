package br.com.node.catalogo;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import br.com.node.nucleo.Avaliador;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.EstadoV3;
import br.com.node.nucleo.GrafoDePrecos;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.PoolV3;
import br.com.node.nucleo.ReservasV2;
import br.com.node.nucleo.Travessia;

public final class Cauda implements Closeable {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final Set<String> NUMERARIOS = Set.of(HexFormat.of().formatHex(WBNB), HexFormat.of().formatHex(USDT),
			"e9e7cea3dedca5984780bafc599bd69add087d56", "8ac76a51cc950d9822d68b83fe1ad97b32cd580d",
			"ce24439f2d9c6a2289f741120fe202248b666666");

	private final Catalogo catalogo;
	private final Cotador cotador;
	private final Tarifario tarifario;
	private final Avaliador avaliador = new Avaliador();
	private final Consumer<Achado> aoAchar;

	private volatile java.util.function.Function<Achado, java.math.BigInteger> valor = a -> a.oportunidade().lucro();

	private static final Logger LOG = Logger.getLogger(Cauda.class.getName());

	private final ExecutorService laco = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "cauda"));
	private final AtomicBoolean ocupada = new AtomicBoolean();

	private volatile Quentes quentes;
	private volatile Quarentena quarentena;
	private final ExecutorService lacoFrio = Executors
			.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "cauda-fria"));
	private final AtomicBoolean friaOcupada = new AtomicBoolean();
	private final Set<String> tocadasParaOFrio = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile GrafoDePrecos grafoQuente;
	private int blocosDesdeARemontagemQuente = Integer.MAX_VALUE;
	private final br.com.node.util.Amostras garimpoFrioMs = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras poolsNoGrafoQuente = new br.com.node.util.Amostras(500);
	private final AtomicLong achadosDoFrioForaDoQuente = new AtomicLong();
	private final AtomicLong blocosFrios = new AtomicLong();

	public void quentesDe(Quentes quentes) {
		this.quentes = quentes;
	}

	public void quarentena(Quarentena quarentena) {
		this.quarentena = quarentena;
	}

	private boolean rotaLivre(Ciclo ciclo) {
		Quarentena q = quarentena;
		return q == null || q.livreRota(ciclo);
	}

	private final AtomicLong blocos = new AtomicLong();

	private final AtomicLong engolidos = new AtomicLong();
	private final AtomicLong projecoes = new AtomicLong();
	private final AtomicLong poolsNoGrafo = new AtomicLong();
	private final AtomicLong ciclosAchados = new AtomicLong();
	private final AtomicLong semV2NaPonta = new AtomicLong();
	private final AtomicLong repescados = new AtomicLong();
	private final AtomicLong semTravessia = new AtomicLong();
	private final AtomicLong semLucro = new AtomicLong();
	private final AtomicLong emitidos = new AtomicLong();
	private final AtomicLong n1SemTravessia = new AtomicLong();
	private final AtomicLong n1SemLucro = new AtomicLong();
	private volatile boolean n1FalhouTravessiaLogado = false;
	private volatile boolean n1FalhouLucroLogado = false;

	private volatile List<byte[]> precificaveis = List.of();

	public void valorarCom(java.util.function.Function<Achado, java.math.BigInteger> valor) {
		this.valor = valor;
	}

	private volatile boolean pontaLivre;

	public void pontaLivre(boolean livre) {
		this.pontaLivre = livre;
	}

	public void aoSemFlashAchar(Consumer<Achado> c) {
		this.aoSemFlashAchar = c;
	}

	private volatile Consumer<Achado> aoSemFlashAchar = a -> {
	};
	private final AtomicLong semFlashComLucro = new AtomicLong();
	private final java.util.concurrent.atomic.AtomicReference<java.math.BigInteger> semFlashValorBnb = new java.util.concurrent.atomic.AtomicReference<>(
			java.math.BigInteger.ZERO);
	private static final long JANELA_SEM_FLASH_MS = 300_000;
	private long inicioDaJanelaSemFlash;
	private java.math.BigInteger maiorSemFlashDaJanela = java.math.BigInteger.ZERO;

	public Cauda(Catalogo catalogo, Cotador cotador, Tarifario tarifario, Consumer<Achado> aoAchar) {
		this.catalogo = catalogo;
		this.cotador = cotador;
		this.tarifario = tarifario;
		this.aoAchar = aoAchar;
	}

	public void atualizarPools() {

		List<byte[]> novo = new ArrayList<>();

		for (byte[] pool : catalogo.pools()) {
			if (cotador.precifica(pool)) {
				novo.add(pool);
			}
		}

		precificaveis = novo;
		Set<String> chaves = new java.util.HashSet<>();
		novo.forEach(pool -> chaves.add(HexFormat.of().formatHex(pool)));
		precificaveisPorChave = chaves;

		blocosDesdeARemontagem = REMONTAR_A_CADA;
		blocosDesdeARemontagemQuente = REMONTAR_A_CADA;
	}

	private volatile Set<String> precificaveisPorChave = Set.of();

	public void aoBloco(long altura) {

		if (!ocupada.compareAndSet(false, true)) {
			engolidos.incrementAndGet();
			return;
		}

		laco.execute(() -> {
			try {
				garimpar(altura);
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("garimpo da cauda", e);
			} finally {
				ocupada.set(false);
			}
		});
	}

	@Override
	public void close() {
		laco.shutdownNow();
		lacoFrio.shutdownNow();
		avaliacao.shutdownNow();
	}

	private final br.com.node.util.Amostras garimpoMs = new br.com.node.util.Amostras(2_000);

	private void garimpar(long altura) {
		long inicio = System.nanoTime();
		try {
			garimparMedido(altura);
		} finally {
			garimpoMs.anotar((System.nanoTime() - inicio) / 1_000_000);

			if (quentes != null) {
				acordarOrganizador(altura);
			}
		}
	}

	private void garimparMedido(long altura) {

		blocos.incrementAndGet();

		Set<String> tocadas = new java.util.HashSet<>();
		for (java.util.Iterator<String> it = tocadasDesdeAUltima.iterator(); it.hasNext();) {
			tocadas.add(it.next());
			it.remove();
		}

		if (tocadas.isEmpty()) {
			return;
		}

		Quentes q = quentes;

		if (q != null) {
			tocadasParaOFrio.addAll(tocadas);
		}

		Set<String> ancoras = tocadas;
		if (q != null) {
			ancoras = new java.util.HashSet<>();
			for (String chave : tocadas) {
				if (q.contem(chave)) {
					ancoras.add(chave);
				}
			}
			if (ancoras.isEmpty()) {
				return;
			}
		}

		long t0 = System.nanoTime();
		GrafoDePrecos grafo = q != null ? grafoQuente(tocadas, q) : grafoDoBloco(tocadas);
		grafo.pontaLivre(pontaLivre);
		long t1 = System.nanoTime();
		List<Ciclo> ciclos = grafo.ciclosPela(ancoras, NUMERARIOS, PERNAS_CAUDA, FENDAS_POR_RODADA, this::rotaLivre);
		long t2 = System.nanoTime();
		List<Ciclo> semFlash = grafo.ciclosSemFlash();
		garimpoMontagem.anotar((t1 - t0) / 1_000_000);
		garimpoBusca.anotar((t2 - t1) / 1_000_000);

		List<Achado> achados = avaliarEmParalelo(ciclos, altura);
		garimpoAvaliacao.anotar((System.nanoTime() - t2) / 1_000_000);
		emitir(achados, altura);

		if (q == null && blocos.get() % SEM_FLASH_A_CADA == 0) {
			contarSemFlash(semFlash, altura);
		}
	}

	private void emitir(List<Achado> achados, long altura) {

		java.util.function.Function<Achado, java.math.BigInteger> valor = this.valor;
		Map<Achado, java.math.BigInteger> valores = new java.util.IdentityHashMap<>();
		achados.forEach(a -> valores.put(a, valor.apply(a)));
		achados.sort((a, b) -> valores.get(b).compareTo(valores.get(a)));

		Set<String> poolsTomadas = new java.util.HashSet<>();

		for (Achado achado : achados) {

			List<String> pools = poolsDe(achado);

			if (pools.stream().anyMatch(poolsTomadas::contains)) {
				continue;
			}

			poolsTomadas.addAll(pools);
			LOG.fine(() -> "cauda: fenda em " + achado.ciclo().pernas().size() + " pernas, lucro "
					+ achado.oportunidade().lucro() + " wei (raiz "
					+ HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()) + "), altura " + altura);
			aoAchar.accept(achado);
		}
	}

	private static List<String> poolsDe(Achado achado) {
		return achado.ciclo().pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).toList();
	}

	private static final int DESCANSO = 2;
	private static final long DESCANSO_MINIMO_MS = 1_000;
	private volatile long proximaPassadaMs;
	private final AtomicLong organizadorAdiado = new AtomicLong();

	private void acordarOrganizador(long altura) {
		if (System.currentTimeMillis() < proximaPassadaMs) {
			organizadorAdiado.incrementAndGet();
			return;
		}
		if (friaOcupada.compareAndSet(false, true)) {
			lacoFrio.execute(() -> {
				long inicio = System.nanoTime();
				try {
					organizar(altura);
				} catch (RuntimeException e) {
					br.com.node.util.Falhas.anotar("organizador da cauda", e);
				} finally {
					long duracao = (System.nanoTime() - inicio) / 1_000_000;
					garimpoFrioMs.anotar(duracao);
					proximaPassadaMs = System.currentTimeMillis() + Math.max(DESCANSO_MINIMO_MS, DESCANSO * duracao);
					friaOcupada.set(false);
				}
			});
		}
	}

	private void organizar(long altura) {

		Set<String> tocadas = new java.util.HashSet<>();
		for (java.util.Iterator<String> it = tocadasParaOFrio.iterator(); it.hasNext();) {
			tocadas.add(it.next());
			it.remove();
		}

		if (tocadas.isEmpty()) {
			return;
		}

		blocosFrios.incrementAndGet();
		Quentes q = quentes;
		GrafoDePrecos grafo = grafoDoBloco(tocadas);
		grafo.pontaLivre(pontaLivre);
		List<Ciclo> ciclos = grafo.ciclosPela(tocadas, NUMERARIOS, PERNAS_CAUDA, FENDAS_POR_RODADA, this::rotaLivre);
		List<Ciclo> semFlash = grafo.ciclosSemFlash();

		List<Achado> foraDoQuente = new ArrayList<>();
		for (Achado achado : avaliarEmParalelo(ciclos, altura)) {
			List<String> pools = poolsDe(achado);
			if (pools.stream().anyMatch(pool -> !q.contem(pool))) {
				foraDoQuente.add(achado);
				q.promover(pools);
			}
		}

		achadosDoFrioForaDoQuente.addAndGet(foraDoQuente.size());
		emitir(foraDoQuente, altura);

		if (blocosFrios.get() % SEM_FLASH_A_CADA == 0) {
			contarSemFlash(semFlash, altura);
		}
	}

	private GrafoDePrecos grafoQuente(Set<String> tocadas, Quentes q) {

		GrafoDePrecos anterior = grafoQuente;
		List<String> novas = q.drenarNovas();

		if (anterior == null || ++blocosDesdeARemontagemQuente >= REMONTAR_A_CADA) {
			blocosDesdeARemontagemQuente = 0;
			GrafoDePrecos grafo = new GrafoDePrecos();
			long ligadas = 0;
			for (byte[] pool : precificaveis) {
				if (q.contem(HexFormat.of().formatHex(pool)) && catalogo.atividade(pool) > 0
						&& ligar(grafo, pool, null)) {
					ligadas++;
				}
			}
			poolsNoGrafoQuente.anotar(ligadas);
			grafoQuente = grafo;
			return grafo;
		}

		Set<String> naLista = precificaveisPorChave;
		Set<String> mudaram = new java.util.HashSet<>();
		for (String chave : tocadas) {
			if (naLista.contains(chave) && q.contem(chave)) {
				mudaram.add(chave);
			}
		}
		for (String chave : novas) {
			if (naLista.contains(chave)) {
				mudaram.add(chave);
			}
		}

		GrafoDePrecos grafo = anterior.semAsPools(mudaram);
		for (String chave : mudaram) {
			ligar(grafo, HexFormat.of().parseHex(chave), null);
		}

		grafoQuente = grafo;
		return grafo;
	}

	private static final int SEM_FLASH_A_CADA = 10;

	private final java.util.concurrent.ForkJoinPool avaliacao = new java.util.concurrent.ForkJoinPool(
			Math.max(1, Runtime.getRuntime().availableProcessors()));

	private List<Achado> avaliarEmParalelo(List<Ciclo> ciclos, long altura) {

		if (ciclos.size() < 2) {
			List<Achado> achados = new ArrayList<>();
			ciclos.forEach(ciclo -> avaliar(ciclo, Map.of(), altura).ifPresent(achados::add));
			return achados;
		}

		try {
			return avaliacao.submit(() -> ciclos.parallelStream().map(ciclo -> avaliar(ciclo, Map.of(), altura))
					.flatMap(Optional::stream).collect(java.util.stream.Collectors.toCollection(ArrayList::new))).get();
		} catch (InterruptedException interrompida) {
			Thread.currentThread().interrupt();
			return new ArrayList<>();
		} catch (java.util.concurrent.ExecutionException falhou) {
			throw new IllegalStateException(falhou.getCause());
		}
	}

	private static final int PERNAS_CAUDA = 4;

	private static final int FENDAS_POR_RODADA = java.util.Optional.ofNullable(System.getenv("CAUDA_FENDAS"))
			.map(String::trim).filter(v -> v.matches("\\d{1,4}")).map(Integer::parseInt).filter(v -> v > 0).orElse(64);

	private final Set<String> tocadasDesdeAUltima = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public void tocou(List<br.com.node.vo.Log> logs) {
		for (br.com.node.vo.Log log : logs) {

			boolean deGerente = Eventos.ehSwapDeGerente(log) || Eventos.ehLiquidezDeGerente(log);
			tocadasDesdeAUltima.add(HexFormat.of().formatHex(deGerente ? log.topicos().get(1) : log.endereco()));
		}
	}

	public Optional<Achado> projetar(Map<String, Pool> overlay, long altura) {
		projecoes.incrementAndGet();
		return detectar(overlay, altura, grafo -> {
			Optional<Ciclo> ciclo = grafo.cicloPela(overlay.keySet(), NUMERARIOS, PERNAS_N1, this::rotaLivre);
			if (grafo.ancorasNoGrafo() == 0) {
				projecoesSemAncora.incrementAndGet();
			} else if (grafo.melhorRetornoBps() >= 0) {
				retornoN1Bps.anotar(grafo.melhorRetornoBps());
			} else {
				projecoesSemVolta.incrementAndGet();
			}
			return ciclo;
		});
	}

	private final AtomicLong projecoesSemAncora = new AtomicLong();
	private final AtomicLong projecoesSemVolta = new AtomicLong();
	private final br.com.node.util.Amostras retornoN1Bps = new br.com.node.util.Amostras(5_000);

	private static final int PERNAS_N1 = 4;

	private Optional<Achado> detectar(Map<String, Pool> overlay, long altura,
			java.util.function.Function<GrafoDePrecos, Optional<Ciclo>> busca) {

		long t0 = System.nanoTime();
		GrafoDePrecos grafo = montarGrafo(overlay);
		grafo.pontaLivre(pontaLivre);
		long t1 = System.nanoTime();
		Optional<Ciclo> ciclo = busca.apply(grafo);
		long t2 = System.nanoTime();
		n1Montagem.anotar((t1 - t0) / 1_000_000);
		n1Busca.anotar((t2 - t1) / 1_000_000);

		semV2NaPonta.addAndGet(grafo.ciclosSemV2NaPonta());

		repescados.addAndGet(grafo.ciclosRepescados());

		if (ciclo.isEmpty()) {
			return Optional.empty();
		}

		long t3 = System.nanoTime();
		Optional<Achado> achado = avaliar(ciclo.get(), overlay, altura);
		n1Avaliacao.anotar((System.nanoTime() - t3) / 1_000_000);
		return achado;
	}

	private final br.com.node.util.Amostras n1Montagem = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras n1Busca = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras n1Avaliacao = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras garimpoMontagem = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras garimpoBusca = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras garimpoAvaliacao = new br.com.node.util.Amostras(2_000);

	private void contarSemFlash(List<Ciclo> descartados, long altura) {

		for (Ciclo ciclo : descartados) {

			Optional<Oportunidade> oportunidade = cotador.travessias(ciclo, Map.of()).flatMap(avaliador::melhor);

			if (oportunidade.isEmpty()) {
				continue;
			}

			Achado achado = new Achado(altura, ciclo, oportunidade.get(), List.of());
			aoSemFlashAchar.accept(achado);
			java.math.BigInteger emBnb = valor.apply(achado);
			semFlashComLucro.incrementAndGet();
			semFlashValorBnb.accumulateAndGet(emBnb, java.math.BigInteger::add);

			long agora = System.currentTimeMillis();
			if (agora - inicioDaJanelaSemFlash > JANELA_SEM_FLASH_MS) {
				inicioDaJanelaSemFlash = agora;
				maiorSemFlashDaJanela = java.math.BigInteger.ZERO;
			}
			if (emBnb.compareTo(maiorSemFlashDaJanela) > 0) {
				maiorSemFlashDaJanela = emBnb;
				LOG.info(() -> "cauda: SEM FLASH — fenda de " + oportunidade.get().lucro() + " wei (raiz "
						+ HexFormat.of().formatHex(ciclo.pernas().get(0).entra()) + ", ~" + emBnb + " wei BNB) em "
						+ ciclo.pernas().size() + " pernas sem V2 na ponta, altura " + altura + " · pools "
						+ String.join(",",
								ciclo.pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).toList()));
			}
		}
	}

	private Optional<Achado> avaliar(Ciclo ciclo, Map<String, Pool> overlay, long altura) {

		ciclosAchados.incrementAndGet();
		Optional<List<Travessia>> caminho = cotador.travessias(ciclo, overlay);

		if (caminho.isEmpty()) {
			semTravessia.incrementAndGet();
			if (!overlay.isEmpty()) {
				n1SemTravessia.incrementAndGet();
				if (!n1FalhouTravessiaLogado) {
					n1FalhouTravessiaLogado = true;
					LOG.warning(() -> "n1: sem-travessia na primeira falha · pernas="
							+ ciclo.pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).toList()
							+ " overlay=" + overlay.keySet());
				}
			}
			return Optional.empty();
		}

		Optional<Oportunidade> oportunidade = avaliador.melhor(caminho.get());

		if (oportunidade.isEmpty()) {
			semLucro.incrementAndGet();
			if (!overlay.isEmpty()) {
				n1SemLucro.incrementAndGet();
				if (!n1FalhouLucroLogado) {
					n1FalhouLucroLogado = true;
					LOG.warning(() -> "n1: sem-lucro na primeira falha · pernas="
							+ ciclo.pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).toList()
							+ " overlay=" + overlay.keySet() + " caminho="
							+ caminho.get().stream().map(t -> t.pool().getClass().getSimpleName()
									+ (t.pool() instanceof PoolV3 v3 ? "(atravessa=" + v3.atravessa() + ")" : ""))
									.toList());
				}
			}
			return Optional.empty();
		}

		emitidos.incrementAndGet();
		return Optional.of(new Achado(altura, ciclo, oportunidade.get(), caminho.get()));
	}

	private volatile GrafoDePrecos grafoDoBloco;

	private static final int REMONTAR_A_CADA = 20;
	private volatile int blocosDesdeARemontagem = REMONTAR_A_CADA;

	private GrafoDePrecos grafoDoBloco(Set<String> tocadas) {

		GrafoDePrecos anterior = grafoDoBloco;

		if (anterior == null || ++blocosDesdeARemontagem >= REMONTAR_A_CADA) {
			blocosDesdeARemontagem = 0;
			return montarGrafo(Map.of());
		}

		Set<String> naLista = precificaveisPorChave;
		Set<String> mudaram = new java.util.HashSet<>();
		for (String chave : tocadas) {
			if (naLista.contains(chave)) {
				mudaram.add(chave);
			}
		}

		GrafoDePrecos grafo = anterior.semAsPools(mudaram);
		for (String chave : mudaram) {
			ligar(grafo, HexFormat.of().parseHex(chave), null);
		}

		grafoDoBloco = grafo;
		return grafo;
	}

	private GrafoDePrecos montarGrafo(Map<String, Pool> overlay) {

		GrafoDePrecos base = grafoDoBloco;

		if (!overlay.isEmpty() && base != null) {
			GrafoDePrecos grafo = base.semAsPools(overlay.keySet());
			for (String chave : overlay.keySet()) {
				byte[] pool = HexFormat.of().parseHex(chave);
				if (precificaveisPorChave.contains(chave) || cotador.precifica(pool)) {
					ligar(grafo, pool, overlay.get(chave));
				}
			}
			return grafo;
		}

		GrafoDePrecos grafo = new GrafoDePrecos();
		long ligadas = 0;

		List<byte[]> pools = precificaveis;
		Set<String> naLista = precificaveisPorChave;
		List<byte[]> extras = new ArrayList<>();

		for (String chave : overlay.keySet()) {
			byte[] pool = HexFormat.of().parseHex(chave);
			if (!naLista.contains(chave) && cotador.precifica(pool)) {
				extras.add(pool);
			}
		}

		if (!extras.isEmpty()) {
			pools = new ArrayList<>(precificaveis);
			pools.addAll(extras);
		}

		for (byte[] pool : pools) {

			Pool projetada = overlay.get(HexFormat.of().formatHex(pool));

			if (projetada == null && catalogo.atividade(pool) <= 0) {
				continue;
			}

			if (ligar(grafo, pool, projetada)) {
				ligadas++;
			}
		}

		poolsNoGrafo.set(ligadas);

		if (overlay.isEmpty()) {
			grafoDoBloco = grafo;
		}

		return grafo;
	}

	private boolean ligar(GrafoDePrecos grafo, byte[] pool, Pool projetada) {

		if (!cotador.livre(pool)) {
			return false;
		}

		Optional<Par> par = catalogo.par(pool);
		Optional<Integer> taxa = tarifario.taxa(pool);

		if (par.isEmpty() || taxa.isEmpty()) {
			return false;
		}

		double imposto01 = cotador.fatorDeImposto(pool, par.get().token0(), par.get().token1());
		double imposto10 = cotador.fatorDeImposto(pool, par.get().token1(), par.get().token0());

		if (projetada instanceof br.com.node.nucleo.PoolSolidlyStable ps) {
			grafo.ligarSolidlyStable(pool, par.get(), ps.reserva0(), ps.reserva1(), taxa.get(), imposto01, imposto10);
			return true;
		}

		if (projetada instanceof PoolV2 pv) {
			grafo.ligarV2(pool, par.get(), pv.reserva0(), pv.reserva1(), taxa.get(), imposto01, imposto10);
			return true;
		}

		if (projetada instanceof PoolV3 pv3) {
			grafo.ligarV3(pool, par.get().token0(), par.get().token1(), pv3.sqrtPrecoX96(), pv3.liquidez(), taxa.get(),
					imposto01, imposto10);
			return true;
		}

		Optional<ReservasV2> reservas = catalogo.reservas(pool);

		if (reservas.isPresent()) {
			if (tarifario.solidlyStable(pool)) {
				grafo.ligarSolidlyStable(pool, par.get(), reservas.get().reserva0(), reservas.get().reserva1(),
						taxa.get(), imposto01, imposto10);
			} else if (tarifario.identificada(pool)) {
				grafo.ligarV2(pool, par.get(), reservas.get().reserva0(), reservas.get().reserva1(), taxa.get(),
						imposto01, imposto10);
			} else {

				return false;
			}
			return true;
		}

		Optional<EstadoV3> estado = catalogo.estado(pool);

		if (estado.isEmpty()) {
			return false;
		}

		Optional<Catalogo.Lados> lados = catalogo.lados(pool);

		if (lados.isEmpty()) {
			return false;
		}

		grafo.ligarV3(pool, lados.get().token0(), lados.get().token1(), estado.get().sqrtPrecoX96(),
				estado.get().liquidez(), taxa.get(),
				cotador.fatorDeImposto(pool, lados.get().token0(), lados.get().token1()),
				cotador.fatorDeImposto(pool, lados.get().token1(), lados.get().token0()));
		return true;
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("blocos_garimpados", blocos.get());
		resumo.put("blocos_engolidos_pelo_garimpo_em_curso", engolidos.get());
		resumo.put("garimpo_ms", garimpoMs.resumo());
		Quentes q = quentes;
		if (q != null) {
			Map<String, Object> camadas = new LinkedHashMap<>(q.resumo());
			camadas.put("pools_no_grafo_quente", poolsNoGrafoQuente.resumo());
			camadas.put("organizador_ms", garimpoFrioMs.resumo());
			camadas.put("organizador_passadas", blocosFrios.get());
			camadas.put("organizador_adiado_pelo_teto_de_cpu", organizadorAdiado.get());
			camadas.put("achados_do_organizador_fora_do_quente", achadosDoFrioForaDoQuente.get());
			resumo.put("camadas", camadas);
		}
		resumo.put("projecoes_n1", projecoes.get());
		resumo.put("pools_no_grafo", poolsNoGrafo.get());
		resumo.put("ciclos_negativos", ciclosAchados.get());
		resumo.put("ciclos_sem_v2_na_ponta", semV2NaPonta.get());
		resumo.put("garimpo_sem_flash_amostragem", "1 bloco em " + SEM_FLASH_A_CADA);
		resumo.put("garimpo_sem_flash_com_lucro", semFlashComLucro.get());
		resumo.put("garimpo_sem_flash_valor_bnb", semFlashValorBnb.get().toString());
		resumo.put("ponta_livre", pontaLivre);
		resumo.put("ciclos_repescados", repescados.get());
		resumo.put("sem_travessia", semTravessia.get());
		resumo.put("sem_lucro", semLucro.get());
		resumo.put("n1_sem_travessia", n1SemTravessia.get());
		resumo.put("n1_sem_lucro", n1SemLucro.get());
		resumo.put("achados_emitidos", emitidos.get());
		resumo.put("n1_sem_ancora_no_grafo", projecoesSemAncora.get());
		resumo.put("n1_sem_caminho_de_volta", projecoesSemVolta.get());
		resumo.put("n1_melhor_retorno_bps", retornoN1Bps.resumo());
		resumo.put("n1_montagem_ms", n1Montagem.resumo());
		resumo.put("n1_busca_ms", n1Busca.resumo());
		resumo.put("n1_avaliacao_ms", n1Avaliacao.resumo());
		resumo.put("garimpo_montagem_ms", garimpoMontagem.resumo());
		resumo.put("garimpo_busca_ms", garimpoBusca.resumo());
		resumo.put("garimpo_avaliacao_ms", garimpoAvaliacao.resumo());
		return resumo;
	}
}