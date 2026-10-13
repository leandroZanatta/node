package br.com.node.conferencia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Conta, por categoria, o que bateu com o contrato, o que divergiu e o que ficou sem resposta.
//
// "Sem resposta" é contado à parte e nunca vira divergência: nó público que já podou a altura não
// diz nada sobre o node. Um instrumento que confunde pergunta sem resposta com resposta negativa
// é pior que nenhum, porque conclui — cicatriz do projeto anterior, em que 528 de 550 conferências
// de cotação perguntavam ao roteador errado e eram lidas como "a rota não serve".
public final class Placar {

	private static final int EXEMPLOS = 8;

	private final Map<String, long[]> contagens = new LinkedHashMap<>();
	private final Map<String, List<String>> exemplos = new LinkedHashMap<>();

	public void conferir(String categoria, boolean bateu, String detalhe) {

		long[] contagem = contagens.computeIfAbsent(categoria, qual -> new long[3]);

		contagem[bateu ? 0 : 1]++;

		if (!bateu) {

			List<String> lista = exemplos.computeIfAbsent(categoria, qual -> new ArrayList<>());

			if (lista.size() < EXEMPLOS) {
				lista.add(detalhe);
			}
		}
	}

	public void semResposta(String categoria) {
		contagens.computeIfAbsent(categoria, qual -> new long[3])[2]++;
	}

	public long divergentes(String categoria) {
		return contagens.getOrDefault(categoria, new long[3])[1];
	}

	public long conferidos(String categoria) {

		long[] contagem = contagens.getOrDefault(categoria, new long[3]);

		return contagem[0] + contagem[1];
	}

	public long semRespostaEm(String categoria) {
		return contagens.getOrDefault(categoria, new long[3])[2];
	}

	public void imprimir() {

		System.out.println();
		System.out.println("conferência contra o contrato: categoria · batem · divergem · sem resposta");

		contagens.forEach((categoria, contagem) -> System.out.printf("   %-40s %6d · %6d · %6d%n", categoria,
				contagem[0], contagem[1], contagem[2]));

		exemplos.forEach((categoria, lista) -> {
			System.out.println();
			System.out.println("   divergências em " + categoria + ":");
			lista.forEach(detalhe -> System.out.println("      " + detalhe));
		});
	}
}
