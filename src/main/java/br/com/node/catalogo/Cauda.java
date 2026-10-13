package br.com.node.catalogo;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;


import br.com.node.nucleo.Avaliador;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.GrafoDePrecos;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.EstadoV3;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.PoolV3;
import br.com.node.nucleo.ReservasV2;
import br.com.node.nucleo.Travessia;

// Garimpa a CAUDA PERSISTENTE: a cada bloco, procura a arb que sobrou não-disputada — por ciclo
// negativo sobre TODAS as pools precificáveis, não só as top-N que a enumeração alcançava.
//
// É a virada de desenho pedida quando o catálogo passou a milhares de pools: em vez de enumerar
// 3-ciclos das pools mais ativas (que escondia 93% delas — justo as esquecidas onde a arb caseira
// sobra), o `GrafoDePrecos` acha um ciclo negativo em O(V·E) sobre o grafo inteiro. O sensor é o bloco
// CONFIRMADO (a cauda é o que persiste ≥1 bloco); quem dispara manda a nossa tx sozinha (o Puissant
// só inclui se lucrar), sem alvo a bundlar.
//
// Roda FORA da thread do cursor, uma por vez (coalescente): se ainda processa o bloco anterior, dropa
// este. Assim o O(V·E) nunca atrasa o sync — foi o que afundou o node quando o eval por bloco crescia.
public final class Cauda implements Closeable {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final Set<String> NUMERARIOS = Set.of(HexFormat.of().formatHex(WBNB),
			HexFormat.of().formatHex(USDT),
			"e9e7cea3dedca5984780bafc599bd69add087d56",  // BUSD
			"8ac76a51cc950d9822d68b83fe1ad97b32cd580d",  // USDC
			"ce24439f2d9c6a2289f741120fe202248b666666"); // U (United Stables)

	private final Catalogo catalogo;
	private final Cotador cotador;
	private final Tarifario tarifario;
	private final Avaliador avaliador = new Avaliador();
	private final Consumer<Achado> aoAchar;
	// O VALOR de uma fenda, para ordená-las: em BNB, injetado por quem sabe cotar (o `CustoDeGas`). Sem ele,
	// o lucro cru em wei da raiz — que só compara fendas da MESMA raiz.
	private volatile java.util.function.Function<Achado, java.math.BigInteger> valor = a -> a.oportunidade().lucro();

	private static final Logger LOG = Logger.getLogger(Cauda.class.getName());

	private final ExecutorService laco = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "cauda"));
	private final AtomicBoolean ocupada = new AtomicBoolean();

	// DUAS CAMADAS (27/09). A RÁPIDA (thread `cauda`, a cada bloco) busca só no grafo das pools QUENTES e despacha
	// na hora. O ORGANIZADOR (thread `cauda-fria`, sem prazo) mantém o grafo inteiro, busca sobre tudo o que mudou,
	// PROMOVE ao quente as pools de todo ciclo com lucro e só despacha o que o quente não podia ver. Medido na
	// noite de 26–27/09: grafo de 13.799 pools, busca de 158 ms (mediana), estado aplicado → decisão de até 1,1 s;
	// as pools com histórico de arbitragem (~4 mil) cobriam 3/4 das fendas reais. Sem `Quentes` ligado, uma camada
	// só, como antes.
	private volatile Quentes quentes;
	private final ExecutorService lacoFrio = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "cauda-fria"));
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

	// Diagnóstico do funil — para SABER onde os candidatos somem, não adivinhar. Escritos na thread da
	// cauda, lidos na do relatório.
	private final AtomicLong blocos = new AtomicLong();
	// Blocos que chegaram com o garimpo do anterior ainda em curso: as tocadas deles ficam para a próxima
	// rodada, que só começa no bloco SEGUINTE — fenda achada um bloco tarde.
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

	// As pools que o cotador precifica AGORA, TODAS (sem teto). Reconstruída fora do bloco (a cobertura
	// cresce devagar); lida por bloco para montar o grafo com as reservas do momento. Escrita pela
	// thread agendada, lida pela do laço — referência trocada inteira, sem sincronização.
	private volatile List<byte[]> precificaveis = List.of();

	public void valorarCom(java.util.function.Function<Achado, java.math.BigInteger> valor) {
		this.valor = valor;
	}

	// Ligada quando o Executor publicado empresta também da ponta V3 ou de gerente (versão 3).
	private volatile boolean pontaLivre;

	public void pontaLivre(boolean livre) {
		this.pontaLivre = livre;
	}

	public void aoSemFlashAchar(Consumer<Achado> c) {
		this.aoSemFlashAchar = c;
	}

	// OS QUE O CONTRATO NÃO ALCANÇA: ciclos com lucro que a busca jogou fora por não ter V2 na ponta.
	// Contados com o valor em BNB, e a maior da janela vai ao log — para que "o node nunca viu" nunca mais
	// esconda "o node viu e o contrato não entra". Com a ponta livre, eles viram achados e isto fica em zero.
	// Para o gabarito medir a detecção (separado da execução), cada um também vai a aoSemFlashAchar.
	private volatile Consumer<Achado> aoSemFlashAchar = a -> {
	};
	private final AtomicLong semFlashComLucro = new AtomicLong();
	private final java.util.concurrent.atomic.AtomicReference<java.math.BigInteger> semFlashValorBnb =
			new java.util.concurrent.atomic.AtomicReference<>(java.math.BigInteger.ZERO);
	private static final long JANELA_SEM_FLASH_MS = 300_000;
	private long inicioDaJanelaSemFlash;
	private java.math.BigInteger maiorSemFlashDaJanela = java.math.BigInteger.ZERO;

	public Cauda(Catalogo catalogo, Cotador cotador, Tarifario tarifario, Consumer<Achado> aoAchar) {
		this.catalogo = catalogo;
		this.cotador = cotador;
		this.tarifario = tarifario;
		this.aoAchar = aoAchar;
	}

	// Refaz a lista de pools precificáveis — caro (checa cada pool do catálogo), roda fora do caminho
	// do bloco (thread agendada), como o `reconstruir` do Modelo.
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
		// Lista nova: o próximo bloco remonta o grafo inteiro (pools entraram ou saíram).
		blocosDesdeARemontagem = REMONTAR_A_CADA;
		blocosDesdeARemontagemQuente = REMONTAR_A_CADA;
	}

	private volatile Set<String> precificaveisPorChave = Set.of();

	// Um bloco fechou (o bloco INTEIRO: o cursor chama uma vez por bloco aplicado). Enfileira a garimpagem
	// se não houver uma em curso; se houver, dropa (coalesce) — nunca acumula, nunca atrasa o cursor.
	public void aoBloco(long altura) {

		if (!ocupada.compareAndSet(false, true)) {
			engolidos.incrementAndGet();
			return;
		}

		// `execute` + registro, e não `submit`: no `Future` que ninguém lê, uma exceção do garimpo sumia e o
		// bloco parecia só "sem fenda".
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

	// CONFIRMADO (cauda persistente): sem overlay, sobre o estado do bloco. Loga o que achar.
	// A fenda aberta num bloco passa, por definição, por uma pool que o bloco mexeu. ANCORAR nelas — e
	// avaliar as FENDAS_POR_RODADA melhores, não uma — substituiu o `cicloNegativo` do grafo inteiro, que
	// devolvia UM ciclo qualquer: a poeira persistente de algum canto tomava o lugar da fenda nova.
	// MEDIDO em 23/09 (62 min): 352 arbitragens alheias fecharam fenda de bloco anterior — o tipo exato
	// deste garimpo — e o gabarito casou UMA com fenda nossa.
	private final br.com.node.util.Amostras garimpoMs = new br.com.node.util.Amostras(2_000);

	private void garimpar(long altura) {
		long inicio = System.nanoTime();
		try {
			garimparMedido(altura);
		} finally {
			garimpoMs.anotar((System.nanoTime() - inicio) / 1_000_000);
			// O organizador só acorda DEPOIS do despacho. Medido em 27/09 no servidor (2 vCPUs): ele leva 44 ms de
			// mediana (p90 117) por bloco no grafo inteiro, e acordava ANTES da busca quente — as duas disputavam
			// os 2 núcleos com a avaliação e o cursor, justo nos ~20 ms que decidem o envio. Ele não tem prazo.
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

		// Duas camadas: o organizador recebe tudo o que mudou; a rápida olha só as tocadas QUENTES.
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
		List<Ciclo> ciclos = grafo.ciclosPela(ancoras, NUMERARIOS, PERNAS_CAUDA, FENDAS_POR_RODADA);
		long t2 = System.nanoTime();
		List<Ciclo> semFlash = grafo.ciclosSemFlash();
		garimpoMontagem.anotar((t1 - t0) / 1_000_000);
		garimpoBusca.anotar((t2 - t1) / 1_000_000);

		// MAIOR VALOR PRIMEIRO, e sem pool repetida. CICATRIZ de 24/09 14:08: 4 fendas do mesmo bloco saíram
		// na ordem em que foram achadas — a de 0,0147 WBNB por último, ~1,2 s depois da primeira — e três
		// dividiam pools, então competiam entre si (só uma entraria). Agora sai a mais valiosa, e a que toca
		// uma pool dela fica de fora nesta rodada.
		List<Achado> achados = avaliarEmParalelo(ciclos, altura);
		garimpoAvaliacao.anotar((System.nanoTime() - t2) / 1_000_000);
		emitir(achados, altura);

		// O diagnóstico do que o contrato não alcança vem DEPOIS do despacho e só em 1 bloco de cada
		// SEM_FLASH_A_CADA. CICATRIZ de 26/09: avaliava ~12 ciclos por bloco ANTES de emitir as fendas — era
		// conta dentro do `garimpo_avaliacao_ms` e atraso em todo envio da cauda, para um número de relatório.
		// Com duas camadas, quem o faz é o organizador, sobre o grafo inteiro.
		if (q == null && blocos.get() % SEM_FLASH_A_CADA == 0) {
			contarSemFlash(semFlash, altura);
		}
	}

	// MAIOR VALOR PRIMEIRO, e sem pool repetida. Pelo valor em BNB, não pelo wei cru: 1 USDT (10^18 wei) e 1 WBNB
	// (10^18 wei) empatavam, e a de USDT tomava as pools da de WBNB, que vale ~600× mais.
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

	// O ORGANIZADOR: coalescente como o garimpo — se está ocupado, as tocadas se acumulam para a próxima passada.
	//
	// E com TETO DE CPU. CICATRIZ de 28/09 (corrida de 23:13, 14 h, servidor de 2 vCPUs): o grafo inteiro cresceu
	// de 3,2 mil para 15,6 mil pools conforme o catálogo aprendia, e o organizador — acordado a CADA bloco — passou
	// de 8 para 1.733 ms de mediana (p90 6,6 s). Rodava sem parar e tomava um núcleo inteiro: a ida do atalho da
	// cabeça (rede, não conta) foi de 134 ms a 6,5 s no p90, a busca quente de 7 para 775 ms, e 17,6 mil blocos
	// foram engolidos. Ele não tem prazo; o que se toca entre passadas se acumula em `tocadasParaOFrio`. Então a
	// próxima passada só começa depois de DESCANSO × a duração da última (e no mínimo DESCANSO_MINIMO_MS): no
	// máximo ~1/(1+DESCANSO) de um núcleo, qualquer que seja o tamanho do grafo.
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

	// O grafo INTEIRO sobre tudo o que mudou desde a última passada. Promove ao quente as pools de todo ciclo com
	// lucro, e despacha só o que tem pool FRIA — o resto a camada rápida já viu. Mantém também o `grafoDoBloco`
	// completo que a projeção do N+1 usa como base (a avaliação exata lê sempre o estado fresco do catálogo).
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
		List<Ciclo> ciclos = grafo.ciclosPela(tocadas, NUMERARIOS, PERNAS_CAUDA, FENDAS_POR_RODADA);
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

	// O grafo da camada RÁPIDA: só pools quentes. Atualizado como o do bloco (sem as tocadas, religadas), mais as
	// recém-promovidas; remontado a cada REMONTAR_A_CADA blocos, ou quando a lista de precificáveis se refaz.
	private GrafoDePrecos grafoQuente(Set<String> tocadas, Quentes q) {

		GrafoDePrecos anterior = grafoQuente;
		List<String> novas = q.drenarNovas();

		if (anterior == null || ++blocosDesdeARemontagemQuente >= REMONTAR_A_CADA) {
			blocosDesdeARemontagemQuente = 0;
			GrafoDePrecos grafo = new GrafoDePrecos();
			long ligadas = 0;
			for (byte[] pool : precificaveis) {
				if (q.contem(HexFormat.of().formatHex(pool)) && catalogo.atividade(pool) > 0 && ligar(grafo, pool, null)) {
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

	// A avaliação dos ciclos do bloco em TODOS os núcleos. Cada ciclo é independente (o `Avaliador` não guarda
	// estado, e o catálogo já é lido em paralelo pelas projeções do N+1); medido em 26/09 no servidor, a thread
	// da cauda avaliava em série com a outra vCPU ~80% ociosa. Pool própria: o `commonPool` é de todo mundo.
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
	// Quantos ciclos distintos por bloco vão ao avaliador. A busca os ordena por PESO MARGINAL (borda no
	// preço de agora), e a poeira de borda alta ganha das fendas profundas: medido em 26/09 pela autópsia do
	// mercado, com 5 por rodada a busca emitiu 7 de ~191 ciclos alheios de bloco anterior que o nosso modelo
	// dava como lucrativos. O custo da busca não depende deste número; o do avaliador é pequeno por ciclo.
	// Medido em 26/09 com 64: a taxa de acerto dobrou (3,7% → 8,2% dos ciclos alheios alcançáveis) e o garimpo
	// não ficou mais lento (mediana 33 → 26 ms). `CAUDA_FENDAS` troca na subida.
	private static final int FENDAS_POR_RODADA = java.util.Optional.ofNullable(System.getenv("CAUDA_FENDAS"))
			.map(String::trim).filter(v -> v.matches("\\d{1,4}")).map(Integer::parseInt).filter(v -> v > 0).orElse(64);

	// As pools que os blocos mexeram desde a última garimpagem. O cursor chama por tx, e a garimpagem
	// coalesce: o que se acumula aqui é consumido na próxima, então nenhum bloco fica sem ser olhado.
	private final Set<String> tocadasDesdeAUltima = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public void tocou(List<br.com.node.vo.Log> logs) {
		for (br.com.node.vo.Log log : logs) {
			// No gerente, quem se mexeu é a pool do tópico 1, não o gerente que emitiu por todas.
			boolean deGerente = Eventos.ehSwapDeGerente(log) || Eventos.ehLiquidezDeGerente(log);
			tocadasDesdeAUltima.add(HexFormat.of().formatHex(deGerente ? log.topicos().get(1) : log.endereco()));
		}
	}

	// N+1 (competindo): a fenda que o swap PENDENTE (overlay = pós-swap das pools tocadas) ABRE. Roda
	// na hora que o swap chega, sobre o estado projetado — para o bundle [swap alvo, nossa] entrar no
	// MESMO bloco. É o sensor que COMPETE, não o pós-captura. Devolve o achado, ou vazio.
	// A busca é ANCORADA nas pools do overlay (`GrafoDePrecos.cicloPela`): a fenda que interessa é a
	// que ESTE swap abre, não a poeira que já estava no grafo. Ciclo de até PERNAS_N1 pernas.
	public Optional<Achado> projetar(Map<String, Pool> overlay, long altura) {
		projecoes.incrementAndGet();
		return detectar(overlay, altura, grafo -> {
			Optional<Ciclo> ciclo = grafo.cicloPela(overlay.keySet(), NUMERARIOS, PERNAS_N1);
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

	// O funil do N+1: projeção cuja pool tocada não estava no grafo, que estava mas não tinha caminho de
	// volta, e o melhor retorno marginal dos que tinham (10.000 bps = empata; a fenda é acima).
	private final AtomicLong projecoesSemAncora = new AtomicLong();
	private final AtomicLong projecoesSemVolta = new AtomicLong();
	private final br.com.node.util.Amostras retornoN1Bps = new br.com.node.util.Amostras(5_000);

	private static final int PERNAS_N1 = 4;

	// O funil comum: monta o grafo (overlay vence o catálogo nas pools tocadas), acha ciclo negativo
	// ancorado num numerário, e o Avaliador dimensiona sobre o mesmo overlay. Só CPU.
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

		// A recusa por ponta V3 é contada, não engolida: "não achou" e "achou, mas não dava para
		// entrar" são problemas OPOSTOS (o segundo pede outra entrada, não outro grafo).
		semV2NaPonta.addAndGet(grafo.ciclosSemV2NaPonta());

		// E o que a segunda busca SALVOU dessas recusas — bloco que antes era jogado fora inteiro. Se
		// este contador fica em zero, a segunda busca não paga o que custa e sai do grafo.
		repescados.addAndGet(grafo.ciclosRepescados());

		if (ciclo.isEmpty()) {
			return Optional.empty();
		}

		long t3 = System.nanoTime();
		Optional<Achado> achado = avaliar(ciclo.get(), overlay, altura);
		n1Avaliacao.anotar((System.nanoTime() - t3) / 1_000_000);
		return achado;
	}

	// ONDE o N+1 gasta, em ms, separado: montar o grafo, buscar o ciclo, avaliar o achado. Medido em 26/09 no
	// servidor: projeção → despacho com mediana de 108 ms (p90 244) — e o grafo sendo remontado do zero a cada
	// swap pendente, sobre um catálogo que cresce a noite toda. Estes três dizem qual das partes pagar.
	private final br.com.node.util.Amostras n1Montagem = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras n1Busca = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras n1Avaliacao = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras garimpoMontagem = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras garimpoBusca = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras garimpoAvaliacao = new br.com.node.util.Amostras(2_000);

	// Avalia os descartados SEM passar pelos contadores do funil (eles não entram), só para medir o que valem.
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
						+ String.join(",", ciclo.pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).toList()));
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
							+ " overlay=" + overlay.keySet()
							+ " caminho=" + caminho.get().stream()
									.map(t -> t.pool().getClass().getSimpleName()
											+ (t.pool() instanceof PoolV3 v3 ? "(atravessa=" + v3.atravessa() + ")" : ""))
									.toList());
				}
			}
			return Optional.empty();
		}

		emitidos.incrementAndGet();
		return Optional.of(new Achado(altura, ciclo, oportunidade.get(), caminho.get()));
	}

	// Grafo de preços sobre pools ATIVAS (atividade > 0, senão é dust/morta → fenda fantasma) mais as
	// que o overlay toca (o swap pendente as move AGORA). Overlay vence o catálogo nas reservas.
	// O grafo do último bloco garimpado. A projeção de cada swap pendente parte dele — copia sem as pools que o
	// swap toca e as religa com o estado projetado — em vez de remontar tudo: medido em 26/09, remontar era ~75%
	// do N+1 (8 de 11 ms com 2 mil pools) e crescia com o catálogo até os 108 ms vistos no servidor. Escrito pela
	// thread da cauda uma vez por bloco, lido pelas projeções; a cópia só lê listas que ninguém mais altera.
	private volatile GrafoDePrecos grafoDoBloco;

	// O GRAFO DO BLOCO É ATUALIZADO, NÃO REMONTADO. Num bloco só mudam as pools que ele tocou — as outras não
	// emitiram evento, e o estado delas é o mesmo (é a premissa do catálogo). Remontar tudo a cada bloco, com
	// catálogo, tarifário e logaritmo por pool, era o que fazia o garimpo crescer com o catálogo (medido na noite
	// de 26/09: de 40 para 120 ms em 8 h, com o grafo de 2.851 para 10.993 pools). Aqui: o grafo anterior sem as
	// pools tocadas, e elas religadas. A cada REMONTAR_A_CADA blocos, ou quando a lista de precificáveis se
	// refaz, remonta do zero — é o que tira do grafo quem entrou em quarentena ou deixou de precificar.
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

		// As pools que o swap pendente toca entram mesmo fora da lista de precificáveis, que só se refaz
		// a cada 90 s — desde que passem pelo MESMO portão do cotador. Sem isto a âncora do N+1 podia
		// nem estar no grafo, e a busca ancorada não teria por onde começar.
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

	// Liga UMA pool no grafo: projetada (overlay do swap pendente) ou pelo estado do catálogo. Falso quando ela
	// não entra (quarentena, sem par, sem taxa, sem estado).
	private boolean ligar(GrafoDePrecos grafo, byte[] pool, Pool projetada) {

		// Desmentida pela cadeia: fora do grafo JÁ, sem esperar a lista se refazer. Um fantasma no
		// grafo é o ciclo negativo de todo bloco — e esconde os outros.
		if (!cotador.livre(pool)) {
			return false;
		}

		Optional<Par> par = catalogo.par(pool);
		Optional<Integer> taxa = tarifario.taxa(pool);

		if (par.isEmpty() || taxa.isEmpty()) {
			return false;
		}

		// O imposto de cada sentido pesa a aresta como a taxa da pool; sentido barrado não vira aresta.
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
				// Tipo desconhecido (ex.: Nomiswap StableSwap): factory não identificada via CREATE2.
				// Modelar como xy=k causaria reverts no relay — ignora.
				return false;
			}
			return true;
		}

		Optional<EstadoV3> estado = catalogo.estado(pool);

		if (estado.isEmpty()) {
			return false;
		}

		// Pelos LADOS: na pool de gerente com BNB nativo o `Par` ordena diferente da pool.
		Optional<Catalogo.Lados> lados = catalogo.lados(pool);

		if (lados.isEmpty()) {
			return false;
		}

		// Pelos LADOS da pool — o imposto também, pelos tokens na ordem em que ela calcula.
		grafo.ligarV3(pool, lados.get().token0(), lados.get().token1(), estado.get().sqrtPrecoX96(),
				estado.get().liquidez(), taxa.get(),
				cotador.fatorDeImposto(pool, lados.get().token0(), lados.get().token1()),
				cotador.fatorDeImposto(pool, lados.get().token1(), lados.get().token0()));
		return true;
	}

	// O funil da cauda, para o relatório: onde os candidatos somem entre o grafo e o envio.
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
