package br.com.node.util;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Falhas {

	private static final Logger LOG = Logger.getLogger(Falhas.class.getName());

	private static final long COM_PILHA = 3;
	private static final long REPETIR_A_CADA = 1_000;

	private static final Map<String, AtomicLong> CONTAGEM = new ConcurrentHashMap<>();

	private Falhas() {
	}

	public static void anotar(String origem, Throwable falha) {

		String chave = origem + " · " + falha.getClass().getSimpleName();
		long vez = CONTAGEM.computeIfAbsent(chave, k -> new AtomicLong()).incrementAndGet();

		if (vez <= COM_PILHA || vez % REPETIR_A_CADA == 0) {
			LOG.log(Level.WARNING, "FALHA em " + origem + " (" + vez + "ª): " + falha, falha);
		}
	}

	public static Map<String, Long> resumo() {

		Map<String, Long> resumo = new TreeMap<>();
		CONTAGEM.forEach((chave, contagem) -> resumo.put(chave, contagem.get()));
		return resumo;
	}
}