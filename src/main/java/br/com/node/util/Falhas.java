package br.com.node.util;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

// Onde uma exceção NÃO pode subir — a borda de uma tarefa num executor, ou a leitura de dado de terceiros que
// pode vir torto (tx pendente, calldata, resposta de multicall) — ela vem para cá, e não para um `LOG.fine`.
//
// CICATRIZ de 26/09: as bordas engoliam a exceção em `LOG.fine` (desligado no servidor) ou num `Future` que
// ninguém lia; uma conta errada ficava igual a "não achou nada", e o relatório de mercado somou USDT como BNB
// por dias sem nada acusar. Aqui toda falha é CONTADA por origem e tipo, e as primeiras de cada uma vão ao
// log em WARNING com a pilha inteira — o suficiente para achar a linha sem inundar o arquivo.
public final class Falhas {

	private static final Logger LOG = Logger.getLogger(Falhas.class.getName());

	// Quantas de cada (origem, tipo) saem com pilha; depois, uma a cada REPETIR_A_CADA, para o log mostrar
	// que continua acontecendo.
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
