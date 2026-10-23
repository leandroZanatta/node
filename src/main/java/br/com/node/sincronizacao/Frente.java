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
import br.com.node.vo.BlockHeaders;
import br.com.node.vo.Bloco;
import br.com.node.vo.Cabecalho;
import br.com.node.vo.GetBlockHeaders;
import br.com.node.vo.GetReceipts;
import br.com.node.vo.Receipts;

public final class Frente implements Closeable {

	private static final Logger LOG = Logger.getLogger(Frente.class.getName());

	private static final long PRAZO_BASE_MS = Resources.inteiro("rlpx.prazo.base.ms");
	private static final long PRAZO_POR_BLOCO_MS = Resources.inteiro("rlpx.prazo.por.bloco.ms");

	private final Sessoes sessoes;
	private final Orcamentos orcamentos;
	private final Desanimo desanimo = new Desanimo();

	private final java.util.concurrent.LinkedBlockingQueue<Faixa> pendentes = new java.util.concurrent.LinkedBlockingQueue<>();

	private final Set<Sessao> arrendadas = ConcurrentHashMap.newKeySet();

	private final Map<String, Long> motivos = new ConcurrentHashMap<>();

	private final AtomicInteger emVoo = new AtomicInteger();
	private final AtomicInteger entregues = new AtomicInteger();
	private final AtomicInteger reatribuidas = new AtomicInteger();

	private final br.com.node.util.Amostras idaCabecalho = new br.com.node.util.Amostras(512);
	private final br.com.node.util.Amostras idaRecibos = new br.com.node.util.Amostras(512);

	private final AtomicLong maiorEntregue = new AtomicLong();

	private volatile Consumer<Bloco> aoMontar;
	private volatile ExecutorService trabalhadores;
	private volatile int pedidosEmVoo;
	private volatile boolean parado;

	public Frente(Sessoes sessoes, int loteMaximo) {
		this.sessoes = sessoes;
		this.orcamentos = new Orcamentos(loteMaximo);
	}

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

	private static final int TENTATIVAS_DO_ATALHO = 4;

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

	private static final boolean PARALELO = "1".equals(System.getenv("ATALHO_PARALELO"));

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

	public void anunciado(Sessao sessao, br.com.node.vo.AnuncioBloco anuncio) {

		Adiantamento a = adiantamento(anuncio.hash(), anuncio.numero());

		if (a == null) {
			return;
		}

		if (pedirReciboSeNinguemPediu(sessao, a)) {
			return;
		}

		if (a.cabecalho == null && !sessao.no().idHex().equals(a.quemDaORecibo)
				&& a.pediuCabecalho.compareAndSet(false, true)) {
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

		return adiantamentos.computeIfAbsent(java.util.HexFormat.of().formatHex(hash),
				chave -> new Adiantamento(hash, numero));
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

			for (Sessao outra : maisRapidas(1, sessao)) {
				atalhos.execute(() -> pedirRecibo(outra, a, false));
			}

			if (a.cabecalho == null && a.pediuCabecalho.compareAndSet(false, true)) {
				cabecalhosNaHora.incrementAndGet();
				for (Sessao outra : maisRapidas(PARES_PARA_CABECALHO, sessao)) {
					atalhos.execute(() -> pedirCabecalhoComInsistencia(outra, a));
				}
			}
		}

		return true;
	}

	private List<Sessao> maisRapidas(int quantas, Sessao exceto) {
		return sessoes.todas().stream().filter(Sessao::ativa).filter(s -> s != exceto)
				.sorted(java.util.Comparator.comparingDouble(Sessao::distancia)).limit(quantas).toList();
	}

	private void pedirCabecalhoComInsistencia(Sessao sessao, Adiantamento a) {

		for (int tentativa = 0; tentativa < TENTATIVAS_DO_CABECALHO && a.cabecalho == null && !parado; tentativa++) {

			try {

				BlockHeaders resposta = sessao.pedir(new br.com.node.vo.GetBlockHeadersDeHash(
						br.com.node.sessao.Pedidos.proximoId(), a.hash, 1, 0, false), BlockHeaders.class, prazo(1));

				if (!resposta.cabecalhos().isEmpty()
						&& java.util.Arrays.equals(resposta.cabecalhos().get(0).hash(), a.hash)) {
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

				Receipts recibos = sessao.pedir(
						new GetReceipts(br.com.node.sessao.Pedidos.proximoId(), List.of(a.hash)), Receipts.class,
						prazo(1));

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

	private void pedirCabecalho(Sessao sessao, Adiantamento a) {

		try {

			BlockHeaders resposta = sessao.pedir(new br.com.node.vo.GetBlockHeadersDeHash(
					br.com.node.sessao.Pedidos.proximoId(), a.hash, 1, 0, false), BlockHeaders.class, prazo(1));

			if (!resposta.cabecalhos().isEmpty()
					&& java.util.Arrays.equals(resposta.cabecalhos().get(0).hash(), a.hash)) {
				if (a.ficarComCabecalho(resposta.cabecalhos().get(0))) {
					cabecalhosPeloHash.incrementAndGet();
					tentarMontar(a);
				}
				return;
			}

		} catch (PeerException e) {

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

		if (cabecalhos.cabecalhos().isEmpty()) {

			naoVeio(sessao,
					faixa.deAltura() > maiorEntregue.get() ? "cabeçalho vazio na fronteira" : "cabeçalho vazio");

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

	private void naoVeio(Sessao sessao, String motivo) {

		anotar(motivo);

		desanimo.desanimar(sessao.no());
	}

	private void anotar(String motivo) {

		reatribuidas.incrementAndGet();

		motivos.merge(motivo, 1L, Long::sum);
	}

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