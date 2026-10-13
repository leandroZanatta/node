package br.com.node.sincronizacao;

import java.util.concurrent.ConcurrentHashMap;

import br.com.node.vo.NoRemoto;

// Quantos blocos vale a pena pedir a cada par — aprendido do que o par entrega.
//
// O corte de resposta é escolha de implementação de CLIENTE, não regra do protocolo: o
// geth corta em `softResponseLimit = 2 MB`, e quem reimplementou não é obrigado a usar
// o mesmo número. Pedir um lote fixo para todos custa uma volta inteira de rede quando
// o par corta em 12 — e a volta é o recurso escasso, não o byte.
//
// Por isso se pergunta ao par pela via que ele entende: pede-se, observa-se o corte, e
// da próxima vez pede-se o corte. Quando ele entrega tudo, sobe-se devagar até o teto
// configurado — um par pode corrigir para cima, e ficar preso no primeiro corte ruim
// seria o mesmo defeito do backoff que não recuperava.
public final class Orcamentos {

	private final ConcurrentHashMap<String, Integer> porPar = new ConcurrentHashMap<>();

	private final int teto;

	public Orcamentos(int teto) {
		this.teto = teto;
	}

	public int quanto(NoRemoto no) {
		return porPar.getOrDefault(no.idHex(), teto);
	}

	public void aprender(NoRemoto no, int pedidos, int vieram) {

		if (vieram <= 0) {
			return;
		}

		// Entregar uma faixa CURTA inteira não prova nada sobre o corte do par. Aprender
		// disso baixaria o orçamento de 16 para 6 só porque restavam 4 blocos — seria
		// aprender do próprio pedido em vez de aprender do par.
		if (vieram == pedidos && pedidos < quanto(no)) {
			return;
		}

		int proximo = vieram < pedidos ? vieram : Math.min(teto, pedidos + pedidos / 4 + 1);

		porPar.put(no.idHex(), proximo);
	}
}
