package br.com.node.baixa.sync;

import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import br.com.node.util.Resources;
import br.com.node.vo.Bloco;

public final class Cursor implements Closeable {

	private static final Logger LOG = Logger.getLogger(Cursor.class.getName());

	private static final Duration PARADA = Duration.ofSeconds(Resources.inteiro("sincronizacao.parada.segundos"));

	private static final int JANELA_DE_REORG = Resources.inteiro("sincronizacao.reorg.janela");
	private static final int PASSO_DE_REORG = Resources.inteiro("sincronizacao.reorg.passo");

	private final Frente frente;
	private final Aplicador aplicador;

	private final Map<Long, Bloco> montagem = new ConcurrentHashMap<>();

	private volatile java.util.function.LongConsumer aoFecharBloco = altura -> {
	};

	private final java.util.concurrent.Semaphore chegou = new java.util.concurrent.Semaphore(0);

	private final Map<Long, byte[]> processados = new HashMap<>();

	private volatile long altura;
	private volatile int reorganizacoes;
	private volatile boolean parado;

	private byte[] hashProcessado;
	private long pedidaAte;

	private long nanosAplicando;

	public Cursor(Frente frente, Aplicador aplicador) {
		this.frente = frente;
		this.aplicador = aplicador;
	}

	public long altura() {
		return altura;
	}

	public int reorganizacoes() {
		return reorganizacoes;
	}

	public long milisAplicando() {
		return nanosAplicando / 1_000_000;
	}

	public int percorrer(long dePartida, int quantos, int porLote) {

		ancorar(dePartida);

		long fim = dePartida + quantos - 1;
		int entregues = frente.entregues();
		long desistirEm = System.currentTimeMillis() + PARADA.toMillis();

		while (altura < fim && !parado) {

			encher(fim, porLote, 1);

			if (frente.entregues() != entregues) {
				entregues = frente.entregues();
				desistirEm = System.currentTimeMillis() + PARADA.toMillis();
			}

			Bloco proximo = montagem.remove(altura + 1);

			if (proximo != null) {

				if (!avancar(proximo) && !recuar()) {
					break;
				}

				desistirEm = System.currentTimeMillis() + PARADA.toMillis();

			} else if (System.currentTimeMillis() > desistirEm) {

				LOG.warning(() -> "sem avanço em " + PARADA.toSeconds() + "s na altura " + (altura + 1));

				break;

			} else {
				esperar();
			}
		}

		return (int) (altura - (dePartida - 1));
	}

	public void seguir(long dePartida, Cabeca cabeca, int porLote, int leque) {

		ancorar(dePartida);
		cabeca.aoAvancar(chegou::release);

		while (!parado) {

			encher(cabeca.alvo(), porLote, leque);

			Bloco proximo = montagem.remove(altura + 1);

			if (proximo == null) {
				repedirSeTravado();
				esperar();
				continue;
			}

			if (avancar(proximo)) {
				cabeca.aplicada(altura, proximo.cabecalho().instanteMs());
				aoFecharBloco.accept(altura);
				podarAplicados();
				travadoDesde = 0;
				continue;
			}

			if (!recuar()) {
				return;
			}
		}
	}

	public void montar(Bloco bloco) {

		long numero = bloco.cabecalho().numero();

		if (numero <= altura) {
			return;
		}

		montagem.put(numero, bloco);
		chegou.release();
	}

	public void aoFecharBloco(java.util.function.LongConsumer aoFecharBloco) {
		this.aoFecharBloco = aoFecharBloco;
	}

	private void podarAplicados() {

		if (montagem.size() > MONTAGEM_FOLGADA) {
			long aplicada = altura;
			montagem.keySet().removeIf(numero -> numero <= aplicada);
		}
	}

	private static final int MONTAGEM_FOLGADA = 64;

	private static final long REPEDIDO_MS = 700;
	private long travadoDesde;
	private long ultimoRepedido;
	private long repedidos;

	private void repedirSeTravado() {

		long agora = System.currentTimeMillis();

		if (travadoDesde == 0) {
			travadoDesde = agora;
			return;
		}

		if (agora - travadoDesde >= REPEDIDO_MS && agora - ultimoRepedido >= REPEDIDO_MS && pedidaAte > altura) {
			frente.pedir(new Faixa(altura + 1, 1));
			ultimoRepedido = agora;
			repedidos++;
		}
	}

	public long repedidos() {
		return repedidos;
	}

	@Override
	public void close() {

		parado = true;

		montagem.clear();
	}

	private void ancorar(long dePartida) {

		altura = dePartida - 1;
		hashProcessado = null;
		pedidaAte = altura;

		processados.clear();
		montagem.clear();
	}

	private void encher(long fim, int porLote, int leque) {

		while (pedidaAte < fim && frente.folga() > 0) {

			int lote = (int) Math.min(porLote, fim - pedidaAte);

			Faixa faixa = new Faixa(pedidaAte + 1, lote);

			for (int copia = 0; copia < (lote == 1 ? leque : 1); copia++) {
				frente.pedir(faixa);
			}

			pedidaAte += lote;
		}
	}

	private void esperar() {

		try {
			chegou.drainPermits();
			if (!montagem.containsKey(altura + 1)) {
				chegou.tryAcquire(10, java.util.concurrent.TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			parado = true;
		}
	}

	private boolean avancar(Bloco bloco) {

		if (hashProcessado != null && !Arrays.equals(bloco.cabecalho().hashDoPai(), hashProcessado)) {

			LOG.warning(() -> "pai diferente do processado na altura " + (altura + 1));

			return false;
		}

		altura++;
		hashProcessado = bloco.cabecalho().hash();

		processados.put(altura, hashProcessado);
		processados.keySet().removeIf(guardada -> guardada <= altura - JANELA_DE_REORG);

		long comecou = System.nanoTime();

		aplicador.aplicar(bloco.recibos(), altura);

		nanosAplicando += System.nanoTime() - comecou;

		return true;
	}

	private boolean recuar() {

		long ancora = altura - PASSO_DE_REORG;
		byte[] hashDaAncora = processados.get(ancora);

		if (hashDaAncora == null) {

			LOG.severe(() -> "reorganização mais funda que " + JANELA_DE_REORG + " blocos na altura " + (altura + 1)
					+ ": o cursor para atrasado em vez de seguir furado");

			return false;
		}

		LOG.warning(() -> "recuando de " + altura + " para " + ancora);

		reorganizacoes++;

		montagem.clear();
		processados.keySet().removeIf(guardada -> guardada > ancora);

		altura = ancora;
		hashProcessado = hashDaAncora;
		pedidaAte = ancora;

		return true;
	}

}