package br.com.node.catalogo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Perna;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.PoolV3;
import br.com.node.nucleo.ReservasV2;
import br.com.node.nucleo.Travessia;

// Transforma um ciclo de endereços nas pools que o compõem, ou em nada.
//
// É O PORTÃO, e ele é intencionalmente estreito: falta a taxa, falta o par, falta o
// estado, falta o token na ponta — não cota. Cotar com um pedaço faltando é produzir
// candidato que reverte, e reverter custa gas para descobrir o que o catálogo já sabia.
//
// "Ou nada" em vez de "o que deu": um ciclo meio cotado é pior que nenhum, porque ele
// entra na comparação com os outros e ganha por ter menos custo contabilizado.
public final class Cotador {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] BUSD = HexFormat.of().parseHex("e9e7cea3dedca5984780bafc599bd69add087d56");
	private static final byte[] USDC = HexFormat.of().parseHex("8ac76a51cc950d9822d68b83fe1ad97b32cd580d");
	private static final byte[] U    = HexFormat.of().parseHex("ce24439f2d9c6a2289f741120fe202248b666666");

	// Piso de liquidez, no lado do numerário. Poda pool rasa (dust, honeypot morto) que só inflaria o
	// grafo e nunca fecha ≥ piso, mas fica BAIXO de propósito — pool mid-depth é justo onde um swap
	// grande abre a fenda N+1 suculenta, e floreá-la fora seria perder o que mais rende. ~US$ 30 de
	// profundidade no numerário. WBNB e USDT são 18 casas na BSC.
	private static final java.math.BigInteger PISO_WBNB = java.math.BigInteger.valueOf(50_000_000_000_000_000L);
	private static final java.math.BigInteger PISO_USDT = new java.math.BigInteger("30000000000000000000");

	private final Catalogo catalogo;
	private final Impostos impostos;
	private final Tarifario tarifario;
	private final Quarentena quarentena;

	// Qual taxa e qual espaçamento valem não é decidido aqui: é do `Tarifario`, que junta identidade
	// da pool e prova por troca. O cotador só se recusa a cotar quando falta algum pedaço.
	public Cotador(Catalogo catalogo, Impostos impostos, Tarifario tarifario, Quarentena quarentena) {
		this.catalogo = catalogo;
		this.impostos = impostos;
		this.tarifario = tarifario;
		this.quarentena = quarentena;
	}

	// A pool não está cumprindo pena por ter sido desmentida pela cadeia. Separado do `precifica`
	// porque a lista de precificáveis da `Cauda` só se refaz a cada 90 s, e a pena vale do bloco
	// seguinte à autópsia.
	// Os mapas de ticks (nulo = desligado: a pool cota até o primeiro tick, como sempre).
	private volatile Ticks ticks;

	public void ticks(Ticks ticks) {
		this.ticks = ticks;
	}

	// A pool com o mapa de ticks dela, se houver — e o pedido da foto, se faltar ou estiver perto da borda.
	public PoolV3 comTicks(byte[] pool, PoolV3 v3) {

		Ticks mapas = ticks;

		if (mapas == null) {
			return v3;
		}

		byte[] gerente = Catalogo.deGerente(pool) ? catalogo.chaveDaPool(pool).map(ChaveDaPool::gerente).orElse(null) : null;

		if (Catalogo.deGerente(pool) && gerente == null) {
			return v3;
		}

		mapas.garantir(pool, v3.tick(), v3.tickSpacing(), gerente);

		return v3.comTicks(mapas.mapa(pool).orElse(null));
	}

	public boolean livre(byte[] pool) {
		return quarentena.livre(pool);
	}

	// Sabemos precificar esta pool? É a MESMA regra do `travessias`, num só lugar, porque
	// quem monta o grafo precisa dela antes de ligar a pool: enumerar sobre pool que não se
	// precifica produziu 4.566 ciclos dos quais 274 eram cotáveis — 94% de enumeração jogada
	// fora, e o custo dela é o atraso no instante em que a pool acabou de se mover.
	// POR QUE a pool não entra no grafo — o primeiro portão que ela não passa, na ordem do `precifica`.
	// É a pergunta que o gabarito faz sobre as pools das arbitragens alheias que não vimos: cada motivo
	// pede uma correção diferente (sem par = catálogo; sem taxa = factory/tarifário; sem estado = ainda
	// não houve evento desde o início; imposto/liquidez/quarentena = filtro nosso).
	public String motivoDeFora(byte[] pool) {

		if (!quarentena.livre(pool)) {
			return "quarentena";
		}

		if (Catalogo.deGerente(pool)) {
			Optional<ChaveDaPool> chave = catalogo.chaveDaPool(pool);
			if (chave.isEmpty()) {
				return "gerente sem chave (varredura não chegou)";
			}
			if (!chave.get().modelavel()) {
				return chave.get().taxaLp() == ChaveDaPool.TAXA_DINAMICA ? "gerente: taxa dinâmica" : "gerente: hook";
			}
		}

		Optional<Par> par = catalogo.par(pool);

		if (par.isEmpty()) {
			return "sem par no catálogo";
		}

		if (!algumSentido(pool, par.get())) {
			return "imposto";
		}

		if (!liquidezSuficiente(pool, par.get())) {
			return "liquidez abaixo do piso";
		}

		if (tarifario.taxa(pool).isEmpty()) {
			return catalogo.reservas(pool).isEmpty() && catalogo.estado(pool).isPresent() ? tarifario.porqueSemTaxaV3(pool)
					: "V2 sem taxa provada";
		}

		if (estado(pool).isEmpty()) {
			return "sem estado (nenhum evento desde o início)";
		}

		if (catalogo.atividade(pool) <= 0) {
			return "sem atividade (fora do grafo da cauda)";
		}

		return "no grafo";
	}

	public boolean precifica(byte[] pool) {
		return quarentena.livre(pool)
				&& catalogo.par(pool).filter(par -> algumSentido(pool, par) && liquidezSuficiente(pool, par)).isPresent()
				&& tarifario.taxa(pool).isPresent() && estado(pool).isPresent();
	}

	public Optional<List<Travessia>> travessias(Ciclo ciclo) {
		return travessias(ciclo, Map.of());
	}

	// A MESMA cotação, mas lendo do `overlay` a pool que o swap pendente já moveu: é como se
	// projeta a fenda de N+1 sem esperar o bloco. O portão não muda — par limpo, taxa, estado —,
	// só a fonte das reservas das pools tocadas. Pool fora do overlay vem do catálogo, como sempre.
	public Optional<List<Travessia>> travessias(Ciclo ciclo, Map<String, Pool> overlay) {

		List<Travessia> caminho = new ArrayList<>();

		for (Perna perna : ciclo.pernas()) {

			Optional<Travessia> travessia = travessia(perna, overlay);

			if (travessia.isEmpty()) {
				return Optional.empty();
			}

			caminho.add(travessia.get());
		}

		return Optional.of(caminho);
	}

	private Optional<Travessia> travessia(Perna perna, Map<String, Pool> overlay) {

		if (!quarentena.livre(perna.pool())) {
			return Optional.empty();
		}

		Optional<Par> par = catalogo.par(perna.pool());
		Optional<Integer> taxa = tarifario.taxa(perna.pool());
		Optional<Catalogo.Lados> lados = catalogo.lados(perna.pool());
		Optional<ImpostosDaPerna> imposto = impostosDaPerna(perna.pool(), perna.entra(), perna.sai());

		if (par.isEmpty() || taxa.isEmpty() || lados.isEmpty() || imposto.isEmpty()) {
			return Optional.empty();
		}

		// O sentido não é escolha: é qual dos dois LADOS da pool está entrando — os lados, e não o `Par`,
		// porque na pool de gerente com BNB nativo os dois discordam. Trocar isto inverte o preço inteiro,
		// e inverter preço é ler oportunidade onde há prejuízo.
		Direcao direcao = Arrays.equals(perna.entra(), lados.get().token0()) ? Direcao.ZERO_PARA_UM
				: Direcao.UM_PARA_ZERO;

		return pool(perna, taxa.get(), overlay)
				.map(pool -> new Travessia(pool, direcao, imposto.get().entra(), imposto.get().sai()));
	}

	// V2 e V3 se distinguem pelo que o catálogo tem da pool, não por cadastro: quem emitiu
	// `Sync` tem reserva, quem emitiu `Swap` de V3 tem preço e liquidez. A pool diz o que é
	// pelo evento que ela emite. O overlay, quando tem a pool, vence o catálogo — é o estado
	// projetado do swap pendente.
	private Optional<Pool> pool(Perna perna, int taxa, Map<String, Pool> overlay) {

		Pool projetada = overlay.get(HexFormat.of().formatHex(perna.pool()));

		if (projetada != null) {
			return Optional.of(projetada);
		}

		Optional<ReservasV2> reservas = catalogo.reservas(perna.pool());

		if (reservas.isPresent()) {
			return Optional.of(tarifario.solidlyStable(perna.pool())
					? new br.com.node.nucleo.PoolSolidlyStable(reservas.get().reserva0(), reservas.get().reserva1(), taxa)
					: new PoolV2(reservas.get().reserva0(), reservas.get().reserva1(), taxa));
		}

		// O espaçamento vem da tabela da factory que o endereço provou. Sem ela a pool não cota: espaçamento
		// errado faz `aplicar` aceitar entrada que cruza tick.
		OptionalInt espacamento = tarifario.espacamento(perna.pool());

		if (espacamento.isEmpty()) {
			return Optional.empty();
		}

		return catalogo.estado(perna.pool()).map(estado -> comTicks(perna.pool(), new PoolV3(estado.sqrtPrecoX96(),
				estado.liquidez(), estado.tick(), taxa, espacamento.getAsInt())));
	}

	// O imposto de uma perna, por DIREÇÃO: a venda do token que entra e a compra do que sai, em bps.
	public record ImpostosDaPerna(int entra, int sai) {
	}

	// O IMPOSTO ENTRA NA CONTA, e o portão só barra o que não se pode fazer. Até 27/09 este portão tirava do
	// grafo a pool com QUALQUER token taxado, pela hipótese de que "divergência em token taxado é sombra do
	// imposto, e ninguém a arbitra". Medido na cadeia, falso: 24 das 25 maiores arbitragens alheias por pool
	// taxada lucraram (ex.: bPay/WBNB, 3% só na compra). Barra-se agora só:
	//   - honeypot / suspeito, ou venda de token sabidamente taxado que ninguém mediu por esta pool
	//     (`Impostos.venda` vazio) — não se chuta número para imposto desconhecido;
	//   - token TAXADO ENTRANDO em pool que não é V2: V3 e gerentes cobram a entrada exata no callback, e o
	//     token que chega menos reverte. No V2 o contrato transfere e calcula sobre o que chegou.
	// Imposto é do PAR (BlackSwan: 0 no CAKE/BS, 1300 no BS/WBNB), então tudo é por (pool, token).
	public Optional<ImpostosDaPerna> impostosDaPerna(byte[] pool, byte[] entra, byte[] sai) {

		java.util.OptionalInt venda = impostos.venda(pool, entra);
		java.util.OptionalInt compra = impostos.compra(pool, sai);

		if (venda.isEmpty() || compra.isEmpty() || venda.getAsInt() >= 10_000 || compra.getAsInt() >= 10_000) {
			return Optional.empty();
		}

		if (venda.getAsInt() > 0 && catalogo.reservas(pool).isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new ImpostosDaPerna(venda.getAsInt(), compra.getAsInt()));
	}

	// O fator multiplicativo do imposto no preço marginal de `de`→`para` (1 = sem imposto), para o PESO da
	// aresta no grafo; zero quando a direção não pode ser feita.
	public double fatorDeImposto(byte[] pool, byte[] de, byte[] para) {
		return impostosDaPerna(pool, de, para).map(i -> (1 - i.entra() / 10_000.0) * (1 - i.sai() / 10_000.0))
				.orElse(0.0);
	}

	// A pool entra no grafo se ao menos UM sentido dela é possível — a bPay/WBNB só se compra com imposto, e a
	// volta pode estar barrada sem que a ida deixe de valer.
	private boolean algumSentido(byte[] pool, Par par) {
		return impostosDaPerna(pool, par.token0(), par.token1()).isPresent()
				|| impostosDaPerna(pool, par.token1(), par.token0()).isPresent();
	}

	// OVERFLOW: reserva >= 95% do uint112_max → qualquer swap que deposita tokens vai transbordar.
	// 2^112 * 0.95 = 4932681615607086747103971512559090 (5 casas abaixo do max de 5192296858...095).
	private static final java.math.BigInteger TETO_RESERVA_V2 =
			java.math.BigInteger.ONE.shiftLeft(112).subtract(java.math.BigInteger.ONE)
					.multiply(java.math.BigInteger.valueOf(95)).divide(java.math.BigInteger.valueOf(100));

	// Piso de liquidez: se a pool segura um numerário, ela precisa segurar o bastante dele. Pool sem
	// numerário passa (é perna do meio; a profundidade que importa está nas pontas). V3 (sem reservas
	// V2) passa aqui — a própria curva a filtra. Barra só a rasa de verdade.
	private boolean liquidezSuficiente(byte[] pool, Par par) {

		Optional<ReservasV2> reservas = catalogo.reservas(pool);

		if (reservas.isEmpty()) {
			return true;
		}

		java.math.BigInteger r0 = reservas.get().reserva0();
		java.math.BigInteger r1 = reservas.get().reserva1();

		// Reserva perto do teto do uint112: qualquer swap que deposita tokens transborda → OVERFLOW no-chain.
		if (r0.compareTo(TETO_RESERVA_V2) >= 0 || r1.compareTo(TETO_RESERVA_V2) >= 0) {
			return false;
		}

		if (Arrays.equals(par.token0(), WBNB)) {
			return r0.compareTo(PISO_WBNB) >= 0;
		}
		if (Arrays.equals(par.token1(), WBNB)) {
			return r1.compareTo(PISO_WBNB) >= 0;
		}
		if (Arrays.equals(par.token0(), USDT) || Arrays.equals(par.token0(), BUSD) || Arrays.equals(par.token0(), USDC) || Arrays.equals(par.token0(), U)) {
			return r0.compareTo(PISO_USDT) >= 0;
		}
		if (Arrays.equals(par.token1(), USDT) || Arrays.equals(par.token1(), BUSD) || Arrays.equals(par.token1(), USDC) || Arrays.equals(par.token1(), U)) {
			return r1.compareTo(PISO_USDT) >= 0;
		}

		return true;
	}

	// Só a existência do estado, sem montar a pool: `precifica` é chamada por pool do
	// catálogo inteiro, e montar `PoolV3` ali para jogar fora seria pagar TickMath por nada.
	private Optional<?> estado(byte[] pool) {

		Optional<ReservasV2> reservas = catalogo.reservas(pool);

		return reservas.isPresent() ? reservas : catalogo.estado(pool);
	}
}
