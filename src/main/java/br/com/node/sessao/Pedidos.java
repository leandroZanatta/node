package br.com.node.sessao;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import br.com.node.exception.PeerException;
import br.com.node.vo.Correlacionada;

// Guarda os pedidos em voo de uma conversa e entrega cada resposta a quem a espera.
//
// Existe porque "a próxima mensagem da classe que eu quero" não é correlação: o par
// intercala milhares de anúncios de mempool entre o pedido e a resposta — medido, 4.549
// contra 256 —, e com duas threads pedindo na mesma conversa a heurística entrega a
// resposta de um ao outro. Trocar resposta é o erro que não dá exceção: os recibos
// chegam, são do bloco errado, e a continuidade só reprova depois.
//
// Quem lê o socket é UM leitor só, e é ele que chama `entregar`. Quem pede não lê nada:
// registra, envia, espera o próprio id.
public final class Pedidos {

	private final Map<Long, CompletableFuture<Correlacionada>> emVoo = new ConcurrentHashMap<>();

	// UM contador para o processo inteiro. CICATRIZ de 24/09: a frente (blocos) e a mempool (corpos) tinham
	// cada uma o seu, os dois começando em 1, e pediam na MESMA conversa: o pedido 68 de cabeçalho e o 68
	// de corpos se trocavam ("Pedido 68 respondido com PooledTransactions, não BlockHeaders"), e o dono
	// verdadeiro esperava o prazo inteiro. Colidia sobretudo na partida, com os contadores ainda juntos.
	private static final java.util.concurrent.atomic.AtomicLong PROXIMO = new java.util.concurrent.atomic.AtomicLong(1);

	public static long proximoId() {
		return PROXIMO.getAndIncrement();
	}

	public void registrar(long requisicaoId) {

		// Id repetido na mesma conversa trocaria respostas em silêncio: falha alto.
		if (emVoo.putIfAbsent(requisicaoId, new CompletableFuture<>()) != null) {
			throw new br.com.node.exception.PeerException("Pedido " + requisicaoId + " já está em voo nesta conversa");
		}
	}

	// Devolve se havia alguém esperando. Resposta sem dono não é erro — é pedido que já
	// venceu, ou tráfego que o par manda por conta própria.
	public boolean entregar(Correlacionada resposta) {

		CompletableFuture<Correlacionada> espera = emVoo.remove(resposta.requisicaoId());

		if (espera == null) {
			return false;
		}

		return espera.complete(resposta);
	}

	public <T extends Correlacionada> T esperar(long requisicaoId, Class<T> esperado, long timeoutMs) {

		CompletableFuture<Correlacionada> espera = emVoo.get(requisicaoId);

		if (espera == null) {
			throw new PeerException("Pedido " + requisicaoId + " não foi registrado");
		}

		try {

			Correlacionada resposta = espera.get(timeoutMs, TimeUnit.MILLISECONDS);

			if (!esperado.isInstance(resposta)) {
				throw new PeerException("Pedido " + requisicaoId + " respondido com "
						+ resposta.getClass().getSimpleName() + ", não " + esperado.getSimpleName());
			}

			return esperado.cast(resposta);

		} catch (TimeoutException e) {
			throw new PeerException(esperado.getSimpleName() + " não chegou em " + timeoutMs + "ms");
		} catch (ExecutionException e) {
			throw new PeerException("Conversa terminou esperando " + esperado.getSimpleName() + ": " + e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PeerException("Espera de " + esperado.getSimpleName() + " interrompida");
		} finally {
			emVoo.remove(requisicaoId);
		}
	}

	// A conversa morreu: ninguém pode ficar esperando até o timeout por um socket que
	// já não existe. Sem isto, cada morte prenderia um trabalhador da frente pelo prazo
	// inteiro, e o pior par voltaria a ditar o ritmo por uma porta dos fundos.
	public void abortar(PeerException causa) {

		emVoo.values().forEach(espera -> espera.completeExceptionally(causa));

		emVoo.clear();
	}
}
