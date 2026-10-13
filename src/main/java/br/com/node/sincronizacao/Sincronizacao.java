package br.com.node.sincronizacao;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import br.com.node.util.Resources;

// Mantém o cursor seguindo a cabeça, numa thread que é dela.
//
// Não é camada de passagem: são três decisões que não cabem em mais ninguém. QUANDO começar —
// só depois do primeiro anúncio, porque antes dele não existe cabeça para perseguir e inventar
// uma seria consultar a rede fora do fluxo. DE ONDE começar — um recuo configurado atrás da
// cabeça, para a taxa das pools começar a se provar antes de o cursor chegar nela. E EM QUE
// ORDEM parar — o cursor antes da frente, senão a frente fechada deixa o cursor esperando
// bloco que ninguém mais vai trazer.
//
// A ORDEM DAS COISAS NA CABEÇA vem de uma cicatriz: no projeto anterior o anúncio entrava na
// mesma fila do catch-up frio, e um item com prazo de 450 ms esperava atrás de milhares com
// prazo de horas. Aqui a frente é uma só e isso não se repete, porque só se opera na cabeça:
// enquanto o cursor está atrasado não há decisão a atrasar, e quando ele chega na cabeça a fila
// só contém cabeça.
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

	// Uma thread, com teto no tipo: o cursor aplica em série de propósito, porque aplicar custa
	// 1,1 ms por bloco e a rede é o gargalo — duas threads aplicando só disputariam a ordem.
	private final ExecutorService seguidor = Executors
			.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "cursor"));

	public Sincronizacao(Cabeca cabeca, Frente frente, Cursor cursor) {
		this.cabeca = cabeca;
		this.frente = frente;
		this.cursor = cursor;
	}

	public void iniciar() {

		frente.abrir(cursor::montar, EM_VOO);

		// `execute`, não `submit`: o `Future` que ninguém lê engolia a exceção que matasse o cursor.
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
			// Cursor morto com o processo vivo é o pior estado: parece no ar, não aplica nada. Sai alto.
			br.com.node.util.Falhas.anotar("cursor", e);
			LOG.log(java.util.logging.Level.SEVERE, "cursor morreu — o node sai para não ficar no ar sem aplicar blocos", e);
			System.exit(2);
		}
	}
}
