package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.vo.Log;

// Prova o imposto de transferência de cada token comparando o `Swap` com o `Transfer` do
// mesmo recibo.
//
// A evidência é a diferença entre o que a pool DIZ que enviou e o que o destino RECEBEU, no
// evento que o próprio token emite. Nenhum dos dois sozinho revela nada. Cicatriz do projeto
// anterior, YFSX: a pool enviou 37.583.037 e o destino recebeu 36.455.546 — exatos 3% retidos
// dentro do `transfer`, depois que o roteador já tinha cotado.
//
// FALHA É SILÊNCIO, NÃO ZERO. Token sem observação não é token limpo: é token não provado, e
// não provado não entra em rota. Tratar ausência de prova como ausência de imposto foi o que
// deixou o YFSX passar.
//
// E o limite desta prova, que o anterior pagou caro para aprender: ela observa TERCEIROS, e
// terceiros passam por roteador — que muitos tokens taxados isentam. O BlackSwan media zero
// assim e cobrava 1300 bps de quem transfere direto, como o nosso contrato faria. Então
// "limpo" aqui quer dizer "nenhum imposto visto", guarda-se o MAIOR retido, e basta uma
// observação taxada para condenar. Só simular a nossa própria transferência fecha o buraco.
public final class Impostos {

	private static final BigInteger PONTOS_BASE = BigInteger.valueOf(10_000);

	// O maior imposto visto por token, em pontos-base arredondados para CIMA: um wei retido já
	// não é zero, e arredondar para baixo transformaria imposto pequeno em limpo.
	private final Map<String, Long> maiorRetido = new ConcurrentHashMap<>();

	// Os repasses da transação corrente, pela rota `de|para`. Dentro do `swap()` o par
	// transfere ANTES de emitir o evento, então no instante do `Swap` o repasse que o comprova
	// já passou por aqui.
	//
	// Guarda-se o ÚLTIMO da rota, não o primeiro. O anterior guardava o primeiro, e numa
	// transação com duas trocas na mesma rota pool→destino isso casa o `Swap` com o repasse de
	// OUTRO token — diferença que parece imposto e não é.
	private final Map<String, Log> repasses = new HashMap<>();

	// A PROVA FORTE, da sondagem, por (POOL, token) — não por token. O imposto é do PAR de destino:
	// medido na cadeia, o BlackSwan cobra 1300 bps no par BS/WBNB e ZERO no par CAKE/BS. Chave por token
	// dava o mesmo veredicto aos dois e descartava a rota limpa junto com a taxada — foi a chave errada
	// mais cara do old (um número numa coluna de `tokens`). Vence o `maiorRetido`, evidência fraca por
	// evento e por token. -1 é honeypot: transferência que reverte, pior que taxa.
	private final Map<String, Integer> provado = new ConcurrentHashMap<>();

	private static final int HONEYPOT = -1;

	// A SUSPEITA que o veredicto da cadeia planta: um envio NOSSO reverteu dizendo que o token não se
	// move (`TRANSFER_FAILED`). Não é veredicto — é PISTA, e por isso NÃO se persiste: o banco aceita
	// `bps >= -1`, e -2 não passaria de propósito. Quem condena de verdade é a sonda, que REMEDE o par
	// (`jaProvado` ignora suspeito) e grava -1 se a transferência reverter de novo.
	//
	// Efeito imediato: a perna sai do grafo, porque `venda`/`compra` dão vazio para o suspeito. É o
	// "validar e já nem usar" — e o custo da validação é ZERO, porque a simulação do relay já rodou.
	// Sem isto, o log do `run-armado` mostrou o custo da alternativa: a MESMA rota de token travado
	// voltou 76 s depois, porque nada do que a cadeia respondeu virou conhecimento.
	private static final int SUSPEITO = -2;

	// O IMPOSTO DE COMPRA, por (pool, token): o que o token perde quando a POOL o envia (é o que nós recebemos
	// a menos na saída da perna). O `provado` acima é o da VENDA — o que se perde transferindo AO par, que é o
	// que a sonda mede direto. Até 27/09 os dois iam para o mesmo mapa (a autópsia gravava a venda do token que
	// entra e a compra do que sai na mesma chave, e a sonda gravava o maior dos dois): um número sem direção,
	// que só servia para BARRAR. Com a direção, o imposto entra na CONTA da perna — e a arbitragem por token
	// taxado, que a cadeia provou lucrativa (bPay/WBNB: 3% só na compra), volta a ser nossa.
	private final Map<String, Integer> compra = new ConcurrentHashMap<>();

	// Quem guarda a prova de imposto fora da memória. Escrito uma vez por quem liga o node, lido a cada
	// prova pela thread da sondagem. `semear` NÃO dispara isto — o que veio do banco já está no banco.
	private volatile java.util.function.Consumer<br.com.node.vo.ProvaDeImposto> aoProvar = prova -> {
	};

	private String transacaoCorrente = "";

	public void observar(Transicao transicao) {

		String transacao = transicao.altura() + ":" + transicao.transacao();

		if (!transacao.equals(transacaoCorrente)) {
			repasses.clear();
			transacaoCorrente = transacao;
		}

		Log log = transicao.log();

		if (log.assinadoPor(Eventos.TRANSFER) && log.topicos().size() >= 3) {
			repasses.put(rota(Eventos.enderecoDoTopico(log, 1), Eventos.enderecoDoTopico(log, 2)), log);
			return;
		}

		if (log.assinadoPor(Eventos.SWAP_V2) && log.topicos().size() >= 3) {
			medirCompra(log);
		}
	}

	// O imposto de VENDA de `token` ao entrar em `pool`, em bps, ou vazio quando a perna não pode ser feita:
	// honeypot, suspeito, ou token que já vimos taxado e cuja venda POR ESTA POOL ninguém mediu — não se chuta
	// um número para imposto desconhecido; a sonda mede e a perna volta.
	public java.util.OptionalInt venda(byte[] pool, byte[] token) {

		Integer forte = provado.get(chave(pool, token));

		if (forte != null) {
			return forte >= 0 ? java.util.OptionalInt.of(forte) : java.util.OptionalInt.empty();
		}

		Long fraco = maiorRetido.get(HexFormat.of().formatHex(token));

		if (fraco != null && fraco > 0) {
			return java.util.OptionalInt.empty();
		}

		// ASSUMIDO, não medido: zero é o que se aposta até a sonda ou a autópsia medirem, e a simulação do relay é
		// quem confere. Contado, para o relatório dizer quanto da conta é aposta.
		vendaAssumida.incrementAndGet();
		return java.util.OptionalInt.of(0);
	}

	private final java.util.concurrent.atomic.AtomicLong vendaAssumida = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong compraAssumida = new java.util.concurrent.atomic.AtomicLong();

	// Quantas consultas de imposto responderam com zero ASSUMIDO (sem medida nem evidência), por direção.
	public Map<String, Long> assumidos() {
		return Map.of("venda_assumida_zero", vendaAssumida.get(), "compra_assumida_zero", compraAssumida.get());
	}

	// O imposto de COMPRA de `token` ao sair de `pool`, em bps, ou vazio se o token é honeypot/suspeito por esta
	// pool, ou se só há imposto VISTO em terceiros sem medida nossa. Sem nada, zero assumido — e a simulação do
	// relay mais o `lucroMinimo` do contrato são o filtro final, como no portão de antes.
	public java.util.OptionalInt compra(byte[] pool, byte[] token) {

		Integer estado = provado.get(chave(pool, token));

		if (estado != null && estado < 0) {
			return java.util.OptionalInt.empty();
		}

		Integer forte = compra.get(chave(pool, token));

		if (forte != null) {
			return java.util.OptionalInt.of(forte);
		}

		Long fraco = maiorRetido.get(HexFormat.of().formatHex(token));

		// Prova antiga SEM direção (linhas de `impostos_provados` de antes de 27/09 são o maior dos dois lados, ou
		// o último gravado — podem ser justamente a compra): vale o maior entre ela e a evidência de terceiros.
		// Errar aqui é para o lado de prometer MENOS.
		if (estado != null) {
			return java.util.OptionalInt.of(Math.max(fraco == null ? 0 : Math.toIntExact(fraco), estado));
		}

		// Imposto VISTO em terceiros e nunca medido por nós: fora até a sonda ou a autópsia medirem. CICATRIZ de
		// 27/09: usar o valor visto deixou entrar no grafo as pools de todo token com imposto, que o portão antigo
		// barrava — o grafo foi de ~7,6 mil para ~13,8 mil pools e a busca de 39 para 158 ms (mediana) numa noite,
		// para quase nada de valor pela cauda. A sonda mede com o grafo DELA, então isto não a impede de medir.
		if (fraco != null && fraco > 0) {
			return java.util.OptionalInt.empty();
		}

		compraAssumida.incrementAndGet();
		return java.util.OptionalInt.of(0);
	}

	// Já existe prova forte para (pool, token) — limpa, taxada OU honeypot? A sondagem pergunta isto
	// ANTES de medir: sem a checagem, ela remede tudo que o banco já provou a cada execução, torrando
	// o orçamento de RPC (que é escasso) no que já sabe — a razão de a cobertura mal crescer.
	//
	// SUSPEITO é exceção DE PROPÓSITO: não é prova, é o que a cadeia acabou de nos dizer para IR
	// OLHAR. Tratá-lo como prova deixaria o par fora do grafo para sempre; tratá-lo como nada
	// repetiria o revert. Ele manda remedir.
	public boolean jaProvado(byte[] pool, byte[] token) {

		Integer estado = provado.get(chave(pool, token));

		return estado != null && estado != SUSPEITO;
	}

	// O par sob suspeita: a cadeia disse que o token não se move por aqui, e o grafo já o soltou.
	// A sondagem consulta isto para remedir ANTES de tudo — é o que fecha o ciclo pista → veredicto.
	public boolean suspeito(byte[] pool, byte[] token) {

		Integer estado = provado.get(chave(pool, token));

		return estado != null && estado == SUSPEITO;
	}

	// Planta a suspeita do veredicto da cadeia. Chamado pelo `Disparo` quando o revert nomeia a
	// transferência que não passou; lido pelo portão (barrar) e pela sondagem (priorizar).
	public void suspeitar(byte[] pool, byte[] token) {
		provado.put(chave(pool, token), SUSPEITO);
	}

	// A sondagem grava aqui o imposto que mediu para (pool, token), em pontos-base. Chamado pela thread
	// da sondagem; lido pela do cotador. Vence a evidência por evento porque mede a NOSSA transferência
	// pela pool, não a do roteador — o BlackSwan media zero por evento e cobra 1300 direto num par.
	public void medidaVenda(byte[] pool, byte[] token, int bps) {
		provado.put(chave(pool, token), bps);
		aoProvar.accept(new br.com.node.vo.ProvaDeImposto(pool, token, bps, false));
	}

	// A compra medida (autópsia ou sonda). Não mexe no `provado`: a venda do mesmo (pool, token) é outra medida.
	public void medidaCompra(byte[] pool, byte[] token, int bps) {

		if (bps < 0) {
			throw new IllegalArgumentException("imposto de compra negativo para " + chave(pool, token) + ": " + bps);
		}

		compra.put(chave(pool, token), bps);
		aoProvar.accept(new br.com.node.vo.ProvaDeImposto(pool, token, bps, true));
	}

	// Token cuja transferência reverte na simulação por esta pool: honeypot. Nunca limpo.
	public void honeypot(byte[] pool, byte[] token) {
		provado.put(chave(pool, token), HONEYPOT);
		aoProvar.accept(new br.com.node.vo.ProvaDeImposto(pool, token, HONEYPOT, false));
	}

	public void aoProvar(java.util.function.Consumer<br.com.node.vo.ProvaDeImposto> ouvinte) {
		this.aoProvar = ouvinte;
	}

	// Semeia o que o banco guardou, no start, sem redisparar o gancho. Chave por (pool, token) em hex, no
	// mesmo formato que `chave` usa.
	public void semear(java.util.List<br.com.node.vo.ProvaDeImposto> provas) {
		provas.forEach(prova -> (prova.compra() ? compra : provado).put(chave(prova.pool(), prova.token()), prova.bps()));
	}

	private static String chave(byte[] pool, byte[] token) {
		return HexFormat.of().formatHex(pool) + ":" + HexFormat.of().formatHex(token);
	}

	public long quantosProvados() {
		return provado.size();
	}

	// Quantos pares estão sob suspeita, esperando o veredicto da sonda.
	public long quantosSuspeitos() {
		return provado.values().stream().filter(bps -> bps == SUSPEITO).count();
	}

	public long quantosLimpos() {
		return maiorRetido.values().stream().filter(retido -> retido == 0).count();
	}

	public long quantosTributados() {
		return maiorRetido.values().stream().filter(retido -> retido > 0).count();
	}

	// A COMPRA: a pool enviando ao destino do swap. É a direção que o anterior demorou a
	// perguntar — a rota FLS errou 1151 bps por catorze ensaios, e era o imposto da compra, que
	// a tabela por par tinha zerado.
	private void medirCompra(Log swap) {

		BigInteger saida0 = Eventos.palavra(swap, 2);
		BigInteger saida1 = Eventos.palavra(swap, 3);

		// sem saída, ou com saída nos dois tokens, o swap não diz qual token saiu
		if (saida0.signum() > 0 == saida1.signum() > 0) {
			return;
		}

		Log recebimento = repasses.get(rota(swap.endereco(), Eventos.enderecoDoTopico(swap, 2)));

		if (recebimento == null) {
			return;
		}

		BigInteger enviado = saida0.max(saida1);
		BigInteger recebido = Eventos.palavra(recebimento, 0);

		// recebeu MAIS do que a pool enviou: o repasse é de outra operação e não prova nada
		if (recebido.compareTo(enviado) > 0) {
			return;
		}

		long retido = enviado.subtract(recebido).multiply(PONTOS_BASE).add(enviado).subtract(BigInteger.ONE)
				.divide(enviado).longValueExact();

		maiorRetido.merge(HexFormat.of().formatHex(recebimento.endereco()), retido, Math::max);
	}

	private static String rota(byte[] de, byte[] para) {
		return HexFormat.of().formatHex(de) + "|" + HexFormat.of().formatHex(para);
	}
}
