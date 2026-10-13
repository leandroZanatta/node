package br.com.node.sincronizacao;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import br.com.node.exception.PeerException;
import br.com.node.sessao.Sessao;
import br.com.node.sessao.Sessoes;
import br.com.node.util.Resources;
import br.com.node.vo.Bloco;
import br.com.node.vo.BlockHeaders;
import br.com.node.vo.Cabecalho;
import br.com.node.vo.GetBlockHeaders;
import br.com.node.vo.GetReceipts;
import br.com.node.vo.Receipts;

// Serve faixas de blocos usando qualquer par disponível, até todas voltarem.
//
// Reatribuir não é uma segunda responsabilidade: é o que "qualquer par disponível"
// significa quando o par escolhido falha.
//
// NENHUMA FAIXA É REFÉM DE UM PAR. Quem falha perde a faixa para outro, e é isso que faz
// a vazão degradar linear com a qualidade dos pares em vez de desabar. A rodada em série
// era refém do pior par: centenas de blocos por segundo caíam para dois ou três, e vazão
// bimodal é sempre assinatura de espera em fila por alguém.
//
// SÓ USA SESSÃO QUE JÁ ESTÁ ATIVA, nunca abre a sua. Dois motivos, e os dois vêm de
// medição: `pedir` exige um leitor vivo para correlacionar, e o leitor é da `Lotacao`; e
// abrir custa TCP + ECIES + Hello + Status com ~80% de recusa por "peers demais" — pagar
// isso na thread que devia estar trazendo bloco foi o que deu 3,3 blocos/s na primeira
// medição, com 76 reatribuições para 8 faixas servidas.
//
// Logo: a vazão desta classe é limitada pelo TAMANHO DO POOL, e quem responde por ele é a
// `Lotacao`. Com 4 ativas de 25, nenhum ajuste aqui muda o número.
//
// Mas tamanho de pool não é a mesma coisa que pares ÚTEIS nele: medido, 86 de 91
// reatribuições eram resposta vazia, sempre dos mesmos pares, porque quem responde vazio
// voltava ao pool e era escolhido de novo. Daí o `Desanimo` — ver `arrendar`.
//
// Cada trabalhador ARRENDA uma sessão, usa e devolve. Depois da correlação por id, mais
// de um pedido por sessão passou a ser possível — o arrendamento fica porque espalhar a
// carga entre pares é o objetivo, não porque o protocolo obrigue.
//
// O TETO REAL É BANDA, NÃO SLOT: medido, uma sessão puxa ~2,1 MB/s de recibo em
// catch-up, e 21 pedidos simultâneos pediriam ~350 Mbps. `sincronizacao.pedidos.em.voo`
// é esse orçamento, não uma contagem de sessões — subir o número sem banda só faz os
// pedidos brigarem entre si.
public final class Frente implements Closeable {

	private static final Logger LOG = Logger.getLogger(Frente.class.getName());

	// O prazo de um pedido cresce com o que ele pede. A BASE vem do projeto anterior, que chegou
	// a 2.500 ms para o recibo de UM bloco depois de ver 900 ms falharem em 1.824 corridas. O
	// ACRÉSCIMO por bloco sai do tamanho: recibo de bloco cheio da BSC tem centenas de kB, e uma
	// sessão puxa ~2,1 MB/s — então cada bloco a mais cabe em algumas centenas de ms.
	private static final long PRAZO_BASE_MS = Resources.inteiro("rlpx.prazo.base.ms");
	private static final long PRAZO_POR_BLOCO_MS = Resources.inteiro("rlpx.prazo.por.bloco.ms");

	private final Sessoes sessoes;
	private final Orcamentos orcamentos;
	private final Desanimo desanimo = new Desanimo();

	// Fila que ACORDA: o trabalhador espera nela em vez de dormir 50 ms e olhar de novo. A faixa da cabeça
	// esperava até 50 ms parada antes de alguém pegá-la (25/09).
	private final java.util.concurrent.LinkedBlockingQueue<Faixa> pendentes = new java.util.concurrent.LinkedBlockingQueue<>();

	// Arrendamento por identidade da sessão: `Sessoes` devolve a mesma instância para o
	// mesmo nó, então a instância é a chave.
	private final Set<Sessao> arrendadas = ConcurrentHashMap.newKeySet();

	// Por que cada faixa voltou para a fila, e quantas vezes. "Reatribuída" sozinho não diz
	// onde mexer: prazo estourado, par que derrubou, par sem a altura e par que não serve
	// recibo pedem quatro decisões diferentes.
	private final Map<String, Long> motivos = new ConcurrentHashMap<>();

	private final AtomicInteger emVoo = new AtomicInteger();
	private final AtomicInteger entregues = new AtomicInteger();
	private final AtomicInteger reatribuidas = new AtomicInteger();

	// As DUAS idas de um bloco, medidas separadas: cabeçalho e depois recibos, em série. Se a soma é o
	// atraso da cabeça, pedir as duas juntas corta metade; se o atraso é outro, isto diz que não é.
	private final br.com.node.util.Amostras idaCabecalho = new br.com.node.util.Amostras(512);
	private final br.com.node.util.Amostras idaRecibos = new br.com.node.util.Amostras(512);

	// PEDIR OS DOIS JUNTOS NÃO ADIANTA — medido, não suposto. Em 25/09 o recibo passou a sair junto com
	// o cabeçalho (pelo hash do anúncio) e o A/B no mesmo período, mesmo pool, deu 482 ms para os dois
	// prontos contra 217 + 249 = 466 ms em série: o par responde os pedidos de uma conexão em ordem, e o
	// recibo (~200 KB) custa o que custa. Revertido; o que decide o atraso é a propagação (bloco feito →
	// anúncio: 231 ms mediana daqui), medida na `Cabeca`.

	// A fronteira do que a rede já serviu: a maior altura que algum par entregou. Vazio ABAIXO dela
	// é par atrasado; ACIMA dela é bloco ainda em propagação — sem a frente saber o que é cabeça.
	//
	// Os dois DESANIMAM IGUAL, e isso é medição, não descuido: desanimar a propagação por 750 ms
	// em vez de 10 s levou o p90 da cabeça com leque 4 de 540 ms para 9.923 ms. O desânimo longo
	// empurra o pedido para quem JÁ recebeu o bloco; o curto devolve à roda justamente quem ainda
	// não recebeu. A separação fica para o relatório dizer qual dos dois vazios está acontecendo.
	private final AtomicLong maiorEntregue = new AtomicLong();

	// Escrito uma vez em `abrir`, lido pelas threads trabalhadoras. Quem escreve é a
	// thread que abre; quem lê são as `pedidosEmVoo` threads da frente.
	private volatile Consumer<Bloco> aoMontar;
	private volatile ExecutorService trabalhadores;
	private volatile int pedidosEmVoo;
	private volatile boolean parado;

	public Frente(Sessoes sessoes, int loteMaximo) {
		this.sessoes = sessoes;
		this.orcamentos = new Orcamentos(loteMaximo);
	}

	// O ouvinte entra aqui, não no construtor: quem monta o bloco é o cursor, e o cursor
	// precisa da frente para pedir. Receber o ouvinte na abertura corta o ciclo sem
	// inventar uma terceira classe só para segurar a referência.
	public void abrir(Consumer<Bloco> aoMontar, int pedidosEmVoo) {

		this.aoMontar = aoMontar;
		this.pedidosEmVoo = pedidosEmVoo;
		this.trabalhadores = Executors.newFixedThreadPool(pedidosEmVoo, tarefa -> new Thread(tarefa, "frente"));

		for (int i = 0; i < pedidosEmVoo; i++) {
			trabalhadores.execute(this::trabalhar);
		}
	}

	public void pedir(Faixa faixa) {
		pendentes.add(faixa);
	}

	// O ATALHO DA CABEÇA: montar o bloco novo com UMA ida de rede, não duas. O caminho normal pede a header e
	// SÓ DEPOIS o recibo, ao mesmo par — medido em 25/09 no servidor: ~142 + ~121 ms em série (anúncio →
	// aplicado ~250 ms). Pedir os dois juntos AO MESMO PAR não adianta (A/B de 25/09: ele responde em ordem),
	// então aqui as duas metades vêm de lugares diferentes, ao mesmo tempo:
	//   - o RECIBO, pelo hash, a quem primeiro mostrou ter o bloco (mandou o `NewBlock` ou anunciou o hash);
	//   - a HEADER vem de graça no `NewBlock`; se só houve anúncio, é pedida pelo hash a OUTRO par que também
	//     anunciou (outra conexão, outra fila).
	// Quando as duas chegam, o bloco vai ao cursor. O caminho normal continua correndo; o cursor fica com a
	// primeira cópia que chegar, pela altura. Quem repassa o `NewBlock` o faz ANTES de importar (é assim que
	// o geth propaga), então o recibo pode ainda não existir: vazio se tenta de novo, com espera curta.
	private static final int TENTATIVAS_DO_ATALHO = 4;
	// 10 ms entre tentativas quando o par ainda não importou o bloco (header/recibo vazio). Era 25: no p90 o atalho
	// tenta 2 vezes, e cada espera saía inteira do tempo até o envio (27/09: anúncio → aplicado com p90 de 103 ms).
	private static final long ESPERA_DO_ATALHO_MS = 10;

	private final ExecutorService atalhos = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<String, Adiantamento> adiantamentos = new ConcurrentHashMap<>();
	private final AtomicLong atalhosVenceram = new AtomicLong();
	private final AtomicLong atalhosChegaramDepois = new AtomicLong();
	private final AtomicLong atalhosSemRecibo = new AtomicLong();
	private final AtomicLong cabecalhosPeloHash = new AtomicLong();
	private final AtomicLong cabecalhosDoNewBlock = new AtomicLong();
	private final br.com.node.util.Amostras idaDoAtalho = new br.com.node.util.Amostras(512);
	private final br.com.node.util.Amostras tentativasDoAtalho = new br.com.node.util.Amostras(512);

	// PARALELO (26/09): o recibo pedido a quem anunciou E ao par mais rápido, valendo o primeiro; a header pedida
	// NA HORA aos dois mais rápidos, sem esperar um segundo anúncio. Medido no servidor na noite de 26/09: do
	// anúncio ao bloco montado 192 ms de mediana, com a ida do recibo em 123 ms e a latência mediana dos pares em
	// 223 ms (p10 121) — a espera pelo segundo anunciante e o par lento que anunciou primeiro eram o resto.
	//
	// MEDIDO E RECUSADO (26/09, A/B de 5 + 5 min, nas duas ordens): o paralelo foi PIOR — do anúncio ao bloco
	// montado 255 contra 219 ms e 326 contra 306 ms, com ~100 atalhos sem recibo contra 3 e 18. Os pedidos extras
	// enfileiram nas mesmas conexões e atrasam as outras respostas (a mesma lição do A/B de 25/09 na `Frente`).
	// Desligado por padrão; `ATALHO_PARALELO=1` liga para medir de novo.
	private static final boolean PARALELO = "1".equals(System.getenv("ATALHO_PARALELO"));

	// O CABEÇALHO NA HORA, SOZINHO (27/09). No anúncio só de hash (metade dos blocos: 11.311 de 23.679 numa corrida
	// de 3 h), a header esperava um SEGUNDO par anunciar — e essa espera saía inteira do tempo até o envio. O A/B de
	// 26/09 recusou o PARALELO, que fazia duas coisas: recibos extras a outros pares (o que pesou) e a header na hora.
	// Aqui só a segunda: UM pedido de header ao par ativo mais rápido que não é o anunciante, no mesmo instante do
	// pedido de recibo ao anunciante. `ATALHO_CABECALHO_NA_HORA=0` volta ao jeito antigo, para medir.
	private static final boolean CABECALHO_NA_HORA = !"0".equals(System.getenv("ATALHO_CABECALHO_NA_HORA"));
	private static final int PARES_PARA_CABECALHO = 2;
	private static final int TENTATIVAS_DO_CABECALHO = 4;
	private final AtomicLong recibosDoAnunciante = new AtomicLong();
	private final AtomicLong recibosDeOutro = new AtomicLong();
	private final AtomicLong cabecalhosNaHora = new AtomicLong();

	private static final class Adiantamento {

		final byte[] hash;
		final long numero;
		final long inicio = System.nanoTime();
		final java.util.concurrent.atomic.AtomicBoolean pediuRecibo = new java.util.concurrent.atomic.AtomicBoolean();
		final java.util.concurrent.atomic.AtomicBoolean pediuCabecalho = new java.util.concurrent.atomic.AtomicBoolean();
		final java.util.concurrent.atomic.AtomicBoolean montado = new java.util.concurrent.atomic.AtomicBoolean();
		volatile String quemDaORecibo = "";
		volatile Cabecalho cabecalho;
		volatile byte[] recibo;

		// Duas corridas podem trazer a mesma metade: vale a primeira.
		synchronized boolean ficarComRecibo(byte[] cru) {
			if (recibo != null) {
				return false;
			}
			recibo = cru;
			return true;
		}

		synchronized boolean ficarComCabecalho(Cabecalho c) {
			if (cabecalho != null) {
				return false;
			}
			cabecalho = c;
			return true;
		}

		Adiantamento(byte[] hash, long numero) {
			this.hash = hash;
			this.numero = numero;
		}
	}

	// O par mandou o bloco inteiro: a header está aqui.
	public void adiantar(Sessao sessao, Cabecalho cabecalho) {

		Adiantamento a = adiantamento(cabecalho.hash(), cabecalho.numero());

		if (a == null) {
			return;
		}

		if (a.ficarComCabecalho(cabecalho)) {
			cabecalhosDoNewBlock.incrementAndGet();
		}

		pedirReciboSeNinguemPediu(sessao, a);
		tentarMontar(a);
	}

	// O par anunciou o hash: o primeiro que anuncia dá o recibo; um SEGUNDO, a header.
	public void anunciado(Sessao sessao, br.com.node.vo.AnuncioBloco anuncio) {

		Adiantamento a = adiantamento(anuncio.hash(), anuncio.numero());

		if (a == null) {
			return;
		}

		if (pedirReciboSeNinguemPediu(sessao, a)) {
			return;
		}

		if (a.cabecalho == null && !sessao.no().idHex().equals(a.quemDaORecibo) && a.pediuCabecalho.compareAndSet(false, true)) {
			atalhos.execute(() -> pedirCabecalho(sessao, a));
		}
	}

	private Adiantamento adiantamento(byte[] hash, long numero) {

		if (aoMontar == null || parado || numero <= maiorEntregue.get()) {
			return null;
		}

		if (adiantamentos.size() > 2_048) {
			long corte = maiorEntregue.get();
			adiantamentos.values().removeIf(velho -> velho.numero <= corte);
		}

		return adiantamentos.computeIfAbsent(java.util.HexFormat.of().formatHex(hash), chave -> new Adiantamento(hash, numero));
	}

	private boolean pedirReciboSeNinguemPediu(Sessao sessao, Adiantamento a) {

		if (!a.pediuRecibo.compareAndSet(false, true)) {
			return false;
		}

		a.quemDaORecibo = sessao.no().idHex();
		atalhos.execute(() -> pedirRecibo(sessao, a, true));

		if (!PARALELO && CABECALHO_NA_HORA && a.cabecalho == null && a.pediuCabecalho.compareAndSet(false, true)) {
			cabecalhosNaHora.incrementAndGet();
			for (Sessao outra : maisRapidas(1, sessao)) {
				atalhos.execute(() -> pedirCabecalhoComInsistencia(outra, a));
			}
		}

		if (PARALELO) {
			// A corrida: o par mais rápido que não é o anunciante pede o mesmo recibo.
			for (Sessao outra : maisRapidas(1, sessao)) {
				atalhos.execute(() -> pedirRecibo(outra, a, false));
			}

			// E a header, se ainda falta (anúncio sem `NewBlock`): na hora, aos mais rápidos, sem esperar o
			// segundo anúncio.
			if (a.cabecalho == null && a.pediuCabecalho.compareAndSet(false, true)) {
				cabecalhosNaHora.incrementAndGet();
				for (Sessao outra : maisRapidas(PARES_PARA_CABECALHO, sessao)) {
					atalhos.execute(() -> pedirCabecalhoComInsistencia(outra, a));
				}
			}
		}

		return true;
	}

	// As sessões ativas mais próximas (menor latência medida), fora `exceto`. Sem arrendar: o pedido é
	// correlacionado pelo id, e dividir a sessão com uma faixa não atrapalha nenhum dos dois.
	private List<Sessao> maisRapidas(int quantas, Sessao exceto) {
		return sessoes.todas().stream().filter(Sessao::ativa).filter(s -> s != exceto)
				.sorted(java.util.Comparator.comparingDouble(Sessao::distancia)).limit(quantas).toList();
	}

	// O par pode ainda não ter importado o bloco (header vazia): tenta de novo, com espera curta.
	private void pedirCabecalhoComInsistencia(Sessao sessao, Adiantamento a) {

		for (int tentativa = 0; tentativa < TENTATIVAS_DO_CABECALHO && a.cabecalho == null && !parado; tentativa++) {

			try {

				BlockHeaders resposta = sessao.pedir(
						new br.com.node.vo.GetBlockHeadersDeHash(br.com.node.sessao.Pedidos.proximoId(), a.hash, 1, 0, false),
						BlockHeaders.class, prazo(1));

				if (!resposta.cabecalhos().isEmpty() && java.util.Arrays.equals(resposta.cabecalhos().get(0).hash(), a.hash)) {
					if (a.ficarComCabecalho(resposta.cabecalhos().get(0))) {
						cabecalhosPeloHash.incrementAndGet();
						tentarMontar(a);
					}
					return;
				}

				Thread.sleep(ESPERA_DO_ATALHO_MS);

			} catch (PeerException e) {
				br.com.node.util.Falhas.anotar("atalho: cabeçalho pelo hash", e);
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void pedirRecibo(Sessao sessao, Adiantamento a, boolean doAnunciante) {

		for (int tentativa = 1; tentativa <= TENTATIVAS_DO_ATALHO && !parado; tentativa++) {

			try {

				Receipts recibos = sessao.pedir(new GetReceipts(br.com.node.sessao.Pedidos.proximoId(), List.of(a.hash)),
						Receipts.class, prazo(1));

				if (!recibos.crusPorBloco().isEmpty()) {
					if (a.ficarComRecibo(recibos.crusPorBloco().get(0))) {
						(doAnunciante ? recibosDoAnunciante : recibosDeOutro).incrementAndGet();
						tentativasDoAtalho.anotar(tentativa);
						tentarMontar(a);
					}
					return;
				}

				if (a.recibo != null) {
					return;
				}

				Thread.sleep(ESPERA_DO_ATALHO_MS);

			} catch (PeerException e) {
				br.com.node.util.Falhas.anotar("atalho: recibo", e);
				break;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		atalhosSemRecibo.incrementAndGet();
	}

	// Header vazia é par que ainda não importou: devolve a vez, para o próximo que anunciar tentar.
	private void pedirCabecalho(Sessao sessao, Adiantamento a) {

		try {

			BlockHeaders resposta = sessao.pedir(
					new br.com.node.vo.GetBlockHeadersDeHash(br.com.node.sessao.Pedidos.proximoId(), a.hash, 1, 0, false),
					BlockHeaders.class, prazo(1));

			if (!resposta.cabecalhos().isEmpty() && java.util.Arrays.equals(resposta.cabecalhos().get(0).hash(), a.hash)) {
				if (a.ficarComCabecalho(resposta.cabecalhos().get(0))) {
					cabecalhosPeloHash.incrementAndGet();
					tentarMontar(a);
				}
				return;
			}

		} catch (PeerException e) {
			// a vez volta para o próximo par que anunciar — contada e à vista
			br.com.node.util.Falhas.anotar("atalho: cabeçalho", e);
		}

		a.pediuCabecalho.set(false);
	}

	private void tentarMontar(Adiantamento a) {

		Consumer<Bloco> montar = aoMontar;

		if (montar == null || a.cabecalho == null || a.recibo == null || !a.montado.compareAndSet(false, true)) {
			return;
		}

		boolean venceu = a.numero > maiorEntregue.get();

		montar.accept(new Bloco(a.cabecalho, a.recibo));
		entregues.incrementAndGet();
		maiorEntregue.accumulateAndGet(a.numero, Math::max);

		(venceu ? atalhosVenceram : atalhosChegaramDepois).incrementAndGet();
		idaDoAtalho.anotar((System.nanoTime() - a.inicio) / 1_000_000);
	}

	// Quantas faixas ainda cabem sem deixar trabalhador parado: é a frente que sabe o
	// tamanho da janela, porque é ela que sabe quantos pedidos aguenta — o cursor só sabe
	// o que falta. Duas por trabalhador, uma sendo servida e uma esperando: janela maior
	// só antecipa pedido que o par ainda não vai ler, e janela de uma deixa trabalhador
	// ocioso no intervalo entre a entrega e o próximo enchimento.
	public int folga() {
		return pedidosEmVoo * 2 - pendentes.size() - emVoo.get();
	}

	public int entregues() {
		return entregues.get();
	}

	public int reatribuidas() {
		return reatribuidas.get();
	}

	public Map<String, Object> resumo() {
		Map<String, Object> resumo = new java.util.LinkedHashMap<>();
		resumo.put("entregues", entregues.get());
		resumo.put("reatribuidas", reatribuidas.get());
		resumo.put("motivos", motivos());
		resumo.put("ida_cabecalho_ms", idaCabecalho.resumo());
		// O atalho do `NewBlock`: quantas vezes ele montou o bloco ANTES do caminho normal (venceram), depois
		// (chegaram depois — a cópia do caminho normal já tinha entrado) ou não conseguiu o recibo.
		resumo.put("atalho_venceu", atalhosVenceram.get());
		resumo.put("atalho_chegou_depois", atalhosChegaramDepois.get());
		resumo.put("atalho_sem_recibo", atalhosSemRecibo.get());
		resumo.put("atalho_ida_ms", idaDoAtalho.resumo());
		resumo.put("atalho_tentativas", tentativasDoAtalho.resumo());
		resumo.put("atalho_cabecalho_do_newblock", cabecalhosDoNewBlock.get());
		resumo.put("atalho_cabecalho_pelo_hash", cabecalhosPeloHash.get());
		resumo.put("atalho_paralelo", PARALELO);
		resumo.put("atalho_cabecalho_na_hora_ligado", CABECALHO_NA_HORA);
		resumo.put("atalho_recibo_do_anunciante", recibosDoAnunciante.get());
		resumo.put("atalho_recibo_de_outro", recibosDeOutro.get());
		resumo.put("atalho_cabecalho_na_hora", cabecalhosNaHora.get());
		resumo.put("ida_recibos_ms", idaRecibos.resumo());
		return resumo;
	}

	public Map<String, Long> motivos() {
		return Map.copyOf(motivos);
	}

	@Override
	public void close() {

		parado = true;
		atalhos.shutdownNow();

		if (trabalhadores != null) {
			trabalhadores.shutdownNow();
		}
	}

	private void trabalhar() {

		while (!parado) {

			Faixa faixa;
			try {
				faixa = pendentes.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				parado = true;
				return;
			}

			if (faixa == null) {
				continue;
			}

			emVoo.incrementAndGet();

			try {
				buscar(faixa);
			} finally {
				emVoo.decrementAndGet();
			}
		}
	}

	private void buscar(Faixa faixa) {

		Sessao sessao = arrendar();

		if (sessao == null) {

			// Sem par agora não é sem par nunca: a faixa volta para a fila e a lotação repõe.
			// Descartá-la aqui abriria um furo que ninguém teria a tarefa de tampar.
			pendentes.add(faixa);
			dormir();

			return;
		}

		try {

			int vieram = pedirFaixa(sessao, faixa);

			if (vieram < faixa.quantos()) {
				pendentes.add(new Faixa(faixa.deAltura() + vieram, faixa.quantos() - vieram));
			}

		} catch (PeerException e) {

			anotar(resumir(e));

			pendentes.add(faixa);

			LOG.fine(() -> "faixa " + faixa.deAltura() + " reatribuída: " + e.getMessage());

		} catch (RuntimeException e) {

			// CICATRIZ de 27/09 (22:18–22:46, servidor): o trabalhador só pegava `PeerException`, e o laço roda num
			// `submit` que ninguém lê — qualquer outra exceção matava a thread CALADA. Mortos os trabalhadores, a
			// fila enche, a `folga` zera, o cursor para de pedir: 25 min parado na altura da partida, sem uma
			// linha de log, só o atalho da cabeça entregando blocos que ninguém aplicava. Agora a exceção vai às
			// `Falhas` com a pilha, a faixa volta à fila e o trabalhador segue.
			br.com.node.util.Falhas.anotar("frente: faixa " + faixa.deAltura() + "+" + faixa.quantos(), e);
			anotar("exceção " + e.getClass().getSimpleName());

			pendentes.add(faixa);

		} finally {
			arrendadas.remove(sessao);
		}
	}

	private int pedirFaixa(Sessao sessao, Faixa faixa) {

		int quantos = Math.min(faixa.quantos(), orcamentos.quanto(sessao.no()));

		long inicio = System.nanoTime();
		BlockHeaders cabecalhos = sessao.pedir(
				new GetBlockHeaders(br.com.node.sessao.Pedidos.proximoId(), faixa.deAltura(), quantos, 0, false),
				BlockHeaders.class, prazo(quantos));
		idaCabecalho.anotar((System.nanoTime() - inicio) / 1_000_000);

		// CABEÇALHO VAZIO e RECIBO VAZIO são diagnósticos opostos, e contá-los juntos
		// esconde qual dos dois está acontecendo. Sem cabeçalho, o par não tem aquela
		// altura — está atrás de nós. Com cabeçalho e sem recibo, ele TEM o bloco e não
		// serve o recibo: ou podou, ou nos trata como leech.
		if (cabecalhos.cabecalhos().isEmpty()) {

			naoVeio(sessao, faixa.deAltura() > maiorEntregue.get() ? "cabeçalho vazio na fronteira" : "cabeçalho vazio");

			return 0;
		}

		List<byte[]> hashes = cabecalhos.cabecalhos().stream().map(Cabecalho::hash).toList();

		long pedidoDosRecibos = System.nanoTime();
		Receipts recibos = sessao.pedir(new GetReceipts(br.com.node.sessao.Pedidos.proximoId(), hashes), Receipts.class,
				prazo(hashes.size()));
		idaRecibos.anotar((System.nanoTime() - pedidoDosRecibos) / 1_000_000);

		int vieram = Math.min(recibos.crusPorBloco().size(), cabecalhos.cabecalhos().size());

		if (vieram == 0) {
			naoVeio(sessao, "recibo vazio");
		}

		orcamentos.aprender(sessao.no(), quantos, vieram);

		for (int i = 0; i < vieram; i++) {
			aoMontar.accept(new Bloco(cabecalhos.cabecalhos().get(i), recibos.crusPorBloco().get(i)));
			entregues.incrementAndGet();
			maiorEntregue.accumulateAndGet(cabecalhos.cabecalhos().get(i).numero(), Math::max);
		}

		return vieram;
	}

	private static long prazo(int blocos) {
		return PRAZO_BASE_MS + PRAZO_POR_BLOCO_MS * Math.max(0, blocos - 1);
	}

	// Pedido que não rendeu bloco: conta-se o motivo, para quem lê o relatório, e desanima
	// o par, para quem escolhe o próximo. É o mesmo fato servindo às duas decisões.
	private void naoVeio(Sessao sessao, String motivo) {

		anotar(motivo);

		desanimo.desanimar(sessao.no());
	}

	private void anotar(String motivo) {

		reatribuidas.incrementAndGet();

		motivos.merge(motivo, 1L, Long::sum);
	}

	// A mensagem carrega o par e o número; o motivo é a forma. Agrupar pela forma é o que
	// transforma 71 linhas de log em uma linha que decide.
	private String resumir(PeerException e) {

		String texto = e.getMessage() == null ? "sem mensagem" : e.getMessage();

		if (texto.contains("não chegou em")) {
			return "prazo estourado";
		}

		if (texto.contains("derrubou")) {
			return "par derrubou";
		}

		if (texto.contains("não está ativa") || texto.contains("Conversa com")) {
			return "sessão morreu";
		}

		return texto;
	}

	// Prefere quem não acabou de responder vazio; só cai no desanimado quando não há outro.
	// É essa queda que faz o desânimo ser ordem de escolha em vez de exclusão — sem ela,
	// um momento em que todo par está desanimado viraria frente parada, que é o defeito do
	// disjuntor antigo com outro nome.
	// O MAIS RÁPIDO primeiro. Antes era o primeiro livre na ordem da lista — os mesmos peers de sempre,
	// rápidos ou lentos. Agora, entre os animados, o de menor distância medida (latência de pedido, ou
	// ping UDP); o desanimado segue sendo o último recurso.
	private Sessao arrendar() {

		Sessao desanimado = null;
		List<Sessao> animados = new java.util.ArrayList<>();

		for (Sessao sessao : sessoes.todas()) {

			if (!servivel(sessao) || arrendadas.contains(sessao)) {
				continue;
			}

			if (!desanimo.animado(sessao.no())) {

				if (desanimado == null) {
					desanimado = sessao;
				}

				continue;
			}

			animados.add(sessao);
		}

		animados.sort(java.util.Comparator.comparingDouble(Sessao::distancia));

		for (Sessao sessao : animados) {
			if (arrendadas.add(sessao)) {
				return sessao;
			}
		}

		if (desanimado == null || !arrendadas.add(desanimado)) {
			return null;
		}

		return desanimado;
	}

	// Sessão ATIVA basta. Quem decide se o par é da nossa cadeia é a `Lotacao`, pelo
	// `Status` do aperto de mão — reconferir aqui pelo ENR reprovaria justamente os pares
	// que não publicam registro, que são a maioria.
	private boolean servivel(Sessao sessao) {
		return sessao.ativa();
	}

	private void dormir() {

		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			parado = true;
		}
	}
}
