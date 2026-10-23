package br.com.node.baixa.p2p;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import br.com.node.exception.PeerException;
import br.com.node.vo.Correlacionada;

public final class Pedidos {

	private final Map<Long, CompletableFuture<Correlacionada>> emVoo = new ConcurrentHashMap<>();

	private static final java.util.concurrent.atomic.AtomicLong PROXIMO = new java.util.concurrent.atomic.AtomicLong(1);

	public static long proximoId() {
		return PROXIMO.getAndIncrement();
	}

	public void registrar(long requisicaoId) {

		if (emVoo.putIfAbsent(requisicaoId, new CompletableFuture<>()) != null) {
			throw new br.com.node.exception.PeerException("Pedido " + requisicaoId + " já está em voo nesta conversa");
		}
	}

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

	public void abortar(PeerException causa) {

		emVoo.values().forEach(espera -> espera.completeExceptionally(causa));

		emVoo.clear();
	}
}