package br.com.node.nucleo;

import java.math.BigInteger;

// Uma perna do caminho: a pool, o sentido e o IMPOSTO de transferência dos dois tokens dela, em bps.
//
// `impostoEntra` é o que o token de entrada perde ao ser transferido AO par (venda): a pool recebe menos do
// que saiu de nós. `impostoSai` é o que o token de saída perde quando o par o envia (compra): chega menos do
// que a pool mandou. Até 27/09 pool com token taxado saía do grafo inteira, pela hipótese de que "divergência
// em token taxado é sombra do imposto, ninguém arbitra" — medido na cadeia, falso: 24 das 25 maiores
// arbitragens alheias por pool taxada lucraram (bPay/WBNB, 3% só na compra). O imposto entra na CONTA.
//
// O contrato já anda assim: transfere ao par e calcula sobre o que CHEGOU (V2), e passa adiante o saldo que
// recebeu de cada perna. A conta daqui reproduz o mesmo caminho.
public record Travessia(Pool pool, Direcao direcao, int impostoEntra, int impostoSai) {

	private static final BigInteger ESCALA = BigInteger.valueOf(10_000);

	public Travessia {

		if (impostoEntra < 0 || impostoEntra >= 10_000 || impostoSai < 0 || impostoSai >= 10_000) {
			throw new IllegalArgumentException("imposto fora de [0, 10000) bps: entra " + impostoEntra + ", sai " + impostoSai);
		}
	}

	public Travessia(Pool pool, Direcao direcao) {
		this(pool, direcao, 0, 0);
	}

	public boolean taxada() {
		return impostoEntra > 0 || impostoSai > 0;
	}

	// A curva da pool com os dois impostos dentro, na MESMA forma `a·x/(b+c·x)` — a entrada escala por
	// (E−v)/E e a saída por (E−p)/E, então a' = a·(E−v)·(E−p), b' = b·E², c' = c·(E−v)·E. Continua fechada na
	// composição, e a entrada ótima do ciclo sai do mesmo jeito.
	public Curva curva() {

		Curva base = pool.curva(direcao);

		if (!taxada()) {
			return base;
		}

		BigInteger entra = ESCALA.subtract(BigInteger.valueOf(impostoEntra));
		BigInteger sai = ESCALA.subtract(BigInteger.valueOf(impostoSai));

		return new Curva(base.a().multiply(entra).multiply(sai), base.b().multiply(ESCALA).multiply(ESCALA),
				base.c().multiply(entra).multiply(ESCALA));
	}

	// O caminho exato: a pool recebe a entrada MENOS a venda, e o que sai chega MENOS a compra. O imposto é
	// arredondado para CIMA nos dois lados — contra nós —, porque o token pode arredondar de qualquer jeito e
	// um wei a mais prometido é revert. O estado seguinte da pool é o do que ela RECEBEU e ENVIOU de fato.
	public Passo aplicar(BigInteger entrada) {

		BigInteger chegaAPool = entrada.subtract(imposto(entrada, impostoEntra));
		Passo passo = pool.aplicar(direcao, chegaAPool);
		BigInteger chegaANos = passo.saida().subtract(imposto(passo.saida(), impostoSai));

		return new Passo(chegaANos, passo.depois());
	}

	private static BigInteger imposto(BigInteger quantia, int bps) {
		if (bps == 0) {
			return BigInteger.ZERO;
		}
		BigInteger[] divisao = quantia.multiply(BigInteger.valueOf(bps)).divideAndRemainder(ESCALA);
		return divisao[1].signum() == 0 ? divisao[0] : divisao[0].add(BigInteger.ONE);
	}
}
