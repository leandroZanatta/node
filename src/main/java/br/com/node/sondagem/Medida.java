package br.com.node.sondagem;

// O resultado de uma medição de imposto: por que saiu, e quanto (em pontos-base) quando saiu.
//
// O MOTIVO vem junto do número porque "não mediu" tem causas que pedem consertos opostos, e agregá-las
// num vazio foi a cicatriz do old: rede fora, endpoint limitando, token sem mapping simples e contrato
// que reverte viravam todos "não mensurável", e "não mensurável" somado não decide nada — um quer
// outro endpoint, outro quer outra pool, o terceiro é resposta legítima sobre o token.
public record Medida(Motivo motivo, int bps) {

	public enum Motivo {

		// Mediu.
		MEDIDO,

		// O detentor-âncora oferecido não tem saldo: não serve para achar o slot, e não é culpa do token.
		SEM_DETENTOR,

		// A varredura foi inteira e nenhum slot casou: o saldo não está em mapping simples.
		SLOT_NAO_ACHADO,

		// A viagem falhou — rede, timeout, endpoint. Não afirma nada sobre o token.
		SEM_RESPOSTA,

		// A simulação reverteu, ou entregou mais do que se pediu. Costuma ser honeypot.
		REVERTEU;

		// Se é propriedade do CONTRATO, e não da viagem. Errar para o lado permanente tira o token do
		// livro para sempre; por isso só o que não muda tentando de novo é definitivo.
		//
		// SLOT_NAO_ACHADO NÃO é: é a NOSSA sonda que não achou onde o token guarda o saldo (proxy,
		// layout próprio), e isso não diz nada sobre o token reverter. CICATRIZ medida em 23/09: ele
		// virava honeypot (-1, imutável) — 213 marcas em 173 pools, 22 delas no próprio WBNB/USDT (na
		// compra, o slot procurado é o do OUTRO token do par, e a culpa caía no inocente). Entre elas o
		// par 431a3bee/WBNB, a arbitragem que os outros bots mais repetiam, provado limpo pela autópsia
		// nos dois sentidos. Sem prova, o par fica SEM PROVA — o portão deixa passar, e quem pega
		// imposto de verdade é o pré-voo e a autópsia.
		public boolean definitivo() {
			return this == REVERTEU;
		}
	}

	public static Medida de(Motivo motivo) {
		return new Medida(motivo, 0);
	}

	public boolean mediu() {
		return motivo == Motivo.MEDIDO;
	}
}
