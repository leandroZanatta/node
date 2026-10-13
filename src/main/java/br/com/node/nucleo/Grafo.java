package br.com.node.nucleo;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Guarda quais pools ligam quais tokens, e enumera os ciclos que voltam à partida.
//
// Só entra aqui pool cuja curva nós sabemos calcular. Enumerar rota sobre pool que não
// sabemos precificar é produzir candidato que vai reverter — e gastar gas para
// descobrir o que o catálogo já sabia.
//
// O QUE ESTE GRAFO EXISTE PARA CONSERTAR: no projeto anterior foram enumeradas 200.000
// rotas sobre 344.823 pools, e o triângulo que carregava 46% do dinheiro real NÃO
// ESTAVA entre elas — `alheias_no_livro = 0` em 32 arbitragens de ~US$ 13 cada. O
// gargalo nunca foi preço nem volume de enumeração: foi a enumeração não alcançar onde
// o dinheiro estava.
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

	// Todos os ciclos de até `maximoPernas` que saem de `partida` e voltam nela.
	//
	// A partida é sempre um numerário — algo em que o lucro se mede e com que o gas se
	// paga. Ciclo que volta num token que não paga gas é lucro que não se realiza.
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

				// Uma perna só não é ciclo: é ida e volta na mesma pool, e o que ela rende é
				// sempre negativo pela taxa.
				if (caminho.size() >= 2) {
					achados.add(new Ciclo(caminho));
				}

			} else {
				procurar(partida, perna.sai(), caminho, maximoPernas, achados);
			}

			caminho.remove(caminho.size() - 1);
		}
	}

	// Pool repetida no mesmo ciclo não é arbitragem: é desfazer o próprio movimento.
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
