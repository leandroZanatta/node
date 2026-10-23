package br.com.node.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Amostras {

	private final int janela;
	private final Deque<Long> valores = new ArrayDeque<>();
	private long total;

	public Amostras(int janela) {
		this.janela = janela;
	}

	public synchronized void anotar(long valor) {

		total++;
		valores.addLast(valor);

		if (valores.size() > janela) {
			valores.removeFirst();
		}
	}

	public synchronized Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("amostras", total);

		if (valores.isEmpty()) {
			return resumo;
		}

		List<Long> ordenados = new ArrayList<>(valores);
		ordenados.sort(null);

		resumo.put("mediana", ordenados.get(ordenados.size() / 2));
		resumo.put("p90", ordenados.get(Math.min(ordenados.size() - 1, ordenados.size() * 9 / 10)));
		resumo.put("max", ordenados.get(ordenados.size() - 1));

		return resumo;
	}
}