package br.com.node.sincronizacao;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import br.com.node.util.Resources;

public final class Sincronizacao implements Closeable {

	private static final Logger LOG = Logger.getLogger(Sincronizacao.class.getName());

	private static final int EM_VOO = Resources.inteiro("sincronizacao.pedidos.em.voo");
	private static final int POR_LOTE = Resources.inteiro("sincronizacao.lote");
	private static final int LEQUE = Resources.inteiro("sincronizacao.leque.cabeca");
	private static final int RECUO_DE_PARTIDA = Resources.inteiro("sincronizacao.partida.recuo");

	private static final long ESPERA_DO_ANUNCIO_MS = 500;

	private final Cabeca cabeca;
	private final Frente frente;
	private final Cursor cursor;

	private final ExecutorService seguidor = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "cursor"));

	public Sincronizacao(Cabeca cabeca, Frente frente, Cursor cursor) {
		this.cabeca = cabeca;
		this.frente = frente;
		this.cursor = cursor;
	}

	public void iniciar() {

		frente.abrir(cursor::montar, EM_VOO);

		seguidor.execute(this::seguir);
	}

	@Override
	public void close() {

		cursor.close();

		seguidor.shutdownNow();

		frente.close();
	}

	private void seguir() {

		try {

			while (cabeca.alvo() == 0) {
				Thread.sleep(ESPERA_DO_ANUNCIO_MS);
			}

			long partida = Math.max(1, cabeca.alvo() - RECUO_DE_PARTIDA);

			LOG.info(() -> "cursor parte de " + partida + " para seguir a cabeça em " + cabeca.alvo());

			cursor.seguir(partida, cabeca, POR_LOTE, LEQUE);

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException | Error e) {

			br.com.node.util.Falhas.anotar("cursor", e);
			LOG.log(java.util.logging.Level.SEVERE,
					"cursor morreu — o node sai para não ficar no ar sem aplicar blocos", e);
			System.exit(2);
		}
	}
}