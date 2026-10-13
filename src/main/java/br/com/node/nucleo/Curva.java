package br.com.node.nucleo;

import java.math.BigInteger;

// Uma travessia escrita como `a·x / (b + c·x)`.
//
// TODA perna de AMM que sabemos calcular tem essa forma. V2 é ela direta:
// `saída = x·k·Rsai / (Rentra·E + x·k)`, com `k = E − taxa`. V3 DENTRO DO TICK também é,
// e isso não é aproximação — sai da álgebra exata do contrato:
//
//   0→1:  a = L·√P²·k        b = L·Q96²·E      c = Q96·k·√P
//   1→0:  a = Q96²·k·L       b = √P²·L·E       c = √P·k·Q96
//
// O que faz a forma valer a pena: ELA É FECHADA NA COMPOSIÇÃO. Duas pernas em série
// continuam sendo uma só `a·x/(b+c·x)`. Então um ciclo inteiro de três pernas é UMA
// função com três coeficientes, e as perguntas que interessam deixam de ser busca:
//
//   rende?              ⟺  A > B          (o preço marginal do laço em x=0)
//   quanto entrar?          x* = (√(A·B) − B)/C
//
// Sem isso, "qual o tamanho ótimo" é varredura numérica por ciclo — e com milhares de
// ciclos por bloco a varredura é o custo dominante. Com isso é uma raiz quadrada.
//
// O tamanho ótimo sai daqui; o lucro NÃO. Quem diz o lucro é a pool, passo a passo, com
// as truncagens dela — esta forma ignora arredondamento, e lucro calculado por ela seria
// lucro que o contrato não paga.
public record Curva(BigInteger a, BigInteger b, BigInteger c) {

	public Curva {

		if (a.signum() <= 0 || b.signum() <= 0 || c.signum() <= 0) {
			throw new IllegalArgumentException("Curva com coeficiente não positivo: " + a + " " + b + " " + c);
		}
	}

	// `this` primeiro, `seguinte` depois. Multiplicar os três por uma constante não muda a
	// função, então divide-se pelo mdc: sem isso, três pernas com √P² dentro passam de
	// 600 bits e a raiz quadrada fica o custo do bloco.
	public Curva compor(Curva seguinte) {

		BigInteger novoA = a.multiply(seguinte.a);
		BigInteger novoB = b.multiply(seguinte.b);
		BigInteger novoC = seguinte.b.multiply(c).add(a.multiply(seguinte.c));

		BigInteger mdc = novoA.gcd(novoB).gcd(novoC);

		return new Curva(novoA.divide(mdc), novoB.divide(mdc), novoC.divide(mdc));
	}

	// A saída que esta forma prevê. Serve para PROVAR que os coeficientes estão certos:
	// para o V2 ela tem de bater wei a wei com o `aplicar` da pool, porque as duas
	// expressões são a mesma reagrupada. Coeficiente errado dá preço plausível e errado, em
	// silêncio — como a TickMath, só se pega comparando com quem manda.
	public BigInteger aplicar(BigInteger entrada) {
		return a.multiply(entrada).divide(b.add(c.multiply(entrada)));
	}

	// O laço devolve mais do que custa na margem. É a única pergunta que se responde sem
	// nenhuma divisão, e é ela que descarta a esmagadora maioria dos ciclos.
	public boolean rende() {
		return a.compareTo(b) > 0;
	}

	// Onde a derivada do lucro zera: p(x) = a·x/(b+c·x) − x, p'(x) = a·b/(b+c·x)² − 1.
	// Truncar a raiz é entrar um pouco antes do ótimo, e antes do ótimo o lucro ainda
	// cresce — errar para o lado de entrar menos é o lado que não fabrica lucro.
	public BigInteger entradaOtima() {
		return a.multiply(b).sqrt().subtract(b).divide(c);
	}
}
