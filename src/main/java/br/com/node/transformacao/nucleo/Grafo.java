package br.com.node.transformacao.nucleo;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Grafo {

	private final Map<String, List<Perna>> arestas = new ConcurrentHashMap<>();

	public void ligar(byte[] pool, Par par) {

		arestas.computeIfAbsent(chave(par.token0()), t -> new ArrayList<>())
				.add(new Perna(pool, par.token0(), par.token1()));

		arestas.computeIfAbsent(chave(par.token1()), t -> new ArrayList<>())
				.add(new Perna(pool, par.token1(), par.token0()));
	}

	public int tokens() {
		return arestas.size();
	}

	public List<Ciclo> ciclos(byte[] partida, int maximoPernas) {

		List<Ciclo> achados = new ArrayList<>();

		procurar(partida, partida, new ArrayList<>(), maximoPernas, achados);

		return achados;
	}

	private void procurar(byte[] partida, byte[] atual, List<Perna> caminho, int maximoPernas, List<Ciclo> achados) {

		if (caminho.size() >= maximoPernas) {
			return;
		}

		for (Perna perna : arestas.getOrDefault(chave(atual), List.of())) {

			if (jaUsou(caminho, perna.pool())) {
				continue;
			}

			caminho.add(perna);

			if (chave(perna.sai()).equals(chave(partida))) {

				if (caminho.size() >= 2) {
					achados.add(new Ciclo(caminho));
				}

			} else {
				procurar(partida, perna.sai(), caminho, maximoPernas, achados);
			}

			caminho.remove(caminho.size() - 1);
		}
	}

	private static boolean jaUsou(List<Perna> caminho, byte[] pool) {

		for (Perna perna : caminho) {

			if (chave(perna.pool()).equals(chave(pool))) {
				return true;
			}
		}

		return false;
	}

	private static String chave(byte[] endereco) {
		return HexFormat.of().formatHex(endereco);
	}
}