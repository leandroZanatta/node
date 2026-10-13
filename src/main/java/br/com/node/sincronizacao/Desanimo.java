package br.com.node.sincronizacao;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.util.Resources;
import br.com.node.vo.NoRemoto;

// Diz se ainda vale a pena pedir a um par agora.
//
// Existe porque responder vazio não tirava o par da fila: ele voltava ao pool e o
// trabalhador seguinte o escolhia outra vez, sempre o mesmo primeiro da lista. Medido,
// 86 de 91 reatribuições eram "respondeu vazio" — quase todo o tempo da frente era gasto
// insistindo com quem já havia dito que não tinha.
//
// É RANKING, NÃO PUNIÇÃO, e a diferença não é de grau: quem arrenda cai para o par
// desanimado quando não há outro, então desanimar muda a ORDEM de escolha sem nunca
// subtrair par do pool. O projeto anterior gravava backoff exponencial de 30 s a 30 min
// na tabela de peers, e com o pool vazio era o próprio disjuntor que impedia a
// recuperação — um par ruim numa rodada ficava barrado por meia hora.
//
// Por isso: CURTO, em memória, expirando sozinho. Não há método de perdoar, porque não há
// nada a perdoar — a entrada morre na primeira leitura depois do prazo.
public final class Desanimo {

	private static final Duration PRAZO = Duration.ofSeconds(Resources.inteiro("sincronizacao.desanimo.segundos"));

	// Cresce até o número de pares que já responderam vazio, e encolhe conforme cada um é
	// consultado depois do prazo. Sem coleta periódica: quem lê é quem limpa.
	private final Map<String, Instant> ate = new ConcurrentHashMap<>();

	public void desanimar(NoRemoto no) {
		ate.put(no.idHex(), Instant.now().plus(PRAZO));
	}

	public boolean animado(NoRemoto no) {

		Instant limite = ate.get(no.idHex());

		if (limite == null) {
			return true;
		}

		if (limite.isAfter(Instant.now())) {
			return false;
		}

		ate.remove(no.idHex(), limite);

		return true;
	}
}
