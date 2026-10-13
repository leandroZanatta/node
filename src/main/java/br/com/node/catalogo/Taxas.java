package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.EstadoV3;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.ReservasV2;
import br.com.node.vo.ProvaDeTaxa;

// Aprende a taxa de cada pool observando as transições dela.
//
// A taxa é o único número da pool que a cadeia não entrega de bandeja, e é o número que
// mais custou no projeto anterior: gravada por média de trades observados, uma pool de
// 1000 ficou registrada como 5, e essa pool sozinha respondeu por 13 dos 13 reverts por
// invariante de um dia — com 94.404 amostras.
//
// Então aqui não há média, não há aproximação e não há "estimada". Só entra taxa que
// FECHA EXATAMENTE a troca observada, e só se for redonda: as factories cobram múltiplos
// de um ponto-base, então taxa quebrada não é uma taxa incomum — é o sinal de que o
// estado que usamos estava errado. Medido no cursor: 972 recuperações exatas redondas
// contra 5 quebradas.
//
// O K ORDENA AS PROVAS — e isto substituiu "contradição apaga", que estava errado.
//
// Uma troca que fecha exatamente em f prova só que a pool cobra NO MÁXIMO f daquele chamador:
// quem pede menos que o máximo também passa pelo invariante. Medido em 768 blocos: das 6 pools
// "contraditórias", 5 tinham 2500 provado por 9 a 20 chamadores distintos contra 3000 de UM só,
// e os chamadores de 3000 (0x0005ea70, 0xf0b1fba8) fechavam 3000 em pools diferentes. Não era
// estado errado nem taxa por chamador: era robô calculando com a fórmula de 0,3% da Uniswap. E
// apagar a pool jogava fora justamente as mais negociadas — 330 trocas numa corrida.
//
// Então: uma taxa só, vale. Mais de uma, vale a MENOR provada por pelo menos dois chamadores
// distintos — a menor porque o K não deixa prova nenhuma abaixo da taxa real, e dois
// chamadores porque um só pode ser estado errado. Nenhuma assim, a pool é ambígua e fica fora
// do grafo: perder uma rota não custa gas, precificá-la errado custa.
public final class Taxas {

	private final Map<String, Integer> provadas = new ConcurrentHashMap<>();
	private final Set<String> ambiguas = ConcurrentHashMap.newKeySet();

	// Por que cada troca provou ou não provou taxa. Sem isto "45% de cobertura" é número sem
	// alavanca: pool que nunca negociou, troca sem estado anterior e troca em que o chamador
	// pediu menos pedem três decisões diferentes, e só uma delas é nossa.
	private final Map<String, Long> motivos = new ConcurrentHashMap<>();

	// Quem provou cada taxa, pelo chamador do `swap`. A prova por log vale para QUEM a
	// produziu, e a pool 7a3eb6e7 mostrou por quê: 33 trocas em 768 blocos, todas fechando em
	// 500 exatos, vindas de só dois chamadores — enquanto o anterior mediu 10% chamando o par
	// direto e levou 13 reverts por K em 13. Taxa que depende de quem chama só aparece por log
	// quando dois chamadores discordam, e é aqui que a discordância fica guardada mesmo depois
	// de a contradição apagar a pool.
	private final Map<String, Map<Integer, Set<String>>> provadores = new ConcurrentHashMap<>();

	// Quantas trocas fecharam exatamente numa taxa redonda, por pool. Com os chamadores
	// distintos, é o que separa pool pouco negociada de pool negociada por poucos.
	private final Map<String, Long> fechamentos = new ConcurrentHashMap<>();

	// A ASSINATURA DA 7a3eb6e7: muita troca, pouquíssimo chamador, e NENHUM roteador público entre
	// eles. Nesta USDT/WBNB, em três janelas de 606 a 768 blocos, foram 15 a 33 trocas de dois
	// chamadores (0x6f1b659d… e 0x5260f8ed…), todas fechando em 5 bps — enquanto o anterior mediu
	// 10% chamando o par direto e levou 13 reverts por K em 13.
	//
	// DUAS VERSÕES ERRADAS antes desta, as duas medidas:
	// - contar só chamadores tirou 68 pools, e em quase todas o único chamador era o roteador V2 da
	//   PancakeSwap. O chamador do `Swap` é quem chamou o PAR, e o roteador chama em nome de todo
	//   mundo: pool negociada só por ele é a mais aberta que existe (0x9a43b714…, 135 fechamentos);
	// - deduzir "aberto" de quem aparece em muitas pools tirou só 4, e soltou a própria 7a3eb6e7 —
	//   o chamador dela também aparece em muitas pools. Amplitude não prova abertura.
	//
	// Então roteador é o que está PROVADO como roteador, por endereço completo, e o resto conta
	// como chamador comum. Um endereço que não é roteador conhecido não vira roteador por suposição.
	private static final long FECHAMENTOS_DE_POOL_MOVIMENTADA = 10;
	private static final int CHAMADORES_DE_POOL_FECHADA = 2;

	// Procedência: `RoteadorDaFactory.ROUTERS` do projeto anterior, o mapa factory → roteador dos
	// forks V2 da BSC que ele usava para `getAmountsOut`, com teste próprio. Nenhum agregador foi
	// identificado lá; nenhum entra aqui.
	private static final Set<String> ROTEADORES_PUBLICOS = Set.of(
			"10ed43c718714eb63d5aa57b78b54704e256024e", // PancakeSwap V2
			"05ff2b0db69458a0750badebc4f9e13add608c7f", // PancakeSwap V1
			"3a6d8ca21d1cf76f653a67577fa0d27453350dd8", // Biswap
			"cf0febd3f17cef5b47b0cd257acf6025c5bff3b7", // ApeSwap
			"cde540d7eafe93ac5fe6233bee57e1270d3e330f", // BakerySwap
			"7dae51bd3e3376b8c7c4900e9107f12be3af1ba8", // MDEX
			"1b02da8cb0d097eb8d57a175b88c7d8b47997506"); // SushiSwap

	// O `Sync` de um swap sai ANTES do `Swap`, na mesma transação, e é ele que carrega o
	// estado anterior. Guarda-se esse estado até o `Swap` chegar — é a única forma de ter
	// as reservas de antes sem pedir nada a ninguém.
	private final Map<String, ReservasV2> antesDoSwap = new ConcurrentHashMap<>();

	// A transação a que o `antesDoSwap` pertence. Existe por causa de um defeito REAL, achado
	// pelo dossiê: `Sync` sem `Swap` na mesma transação — mint, burn, skim — deixava o estado
	// pendurado, e ele era casado com o `Swap` de uma transação MUITO depois. Estado errado
	// mais inversão exata dá taxa errada que FECHA, e taxa errada que fecha é indistinguível
	// de taxa certa.
	//
	// É a mesma falha do projeto anterior chegando por outro caminho: lá a pool 7a3eb6e7…
	// ficou gravada com 5 bps cobrando 1000, e aqui ela voltou a sair 500 — nos três maiores
	// achados de uma corrida. Por isso o pendente vale só DENTRO da transação.
	private String transacaoDoPendente = "";

	// Quem guarda a evidência fora da memória. Escrito uma vez por quem liga o node, lido a cada
	// prova pela thread do cursor. Recebe cada fechamento com `fechamentos = 1`; quem persiste
	// soma.
	private volatile Consumer<ProvaDeTaxa> aoProvar = prova -> {
	};

	public void observar(Transicao transicao) {

		String pool = chave(transicao.log().endereco());
		String transacaoAtual = transicao.altura() + ":" + transicao.transacao();

		if (!transacaoAtual.equals(transacaoDoPendente)) {
			antesDoSwap.clear();
			transacaoDoPendente = transacaoAtual;
		}

		if (transicao.log().assinadoPor(Eventos.SYNC_V2)) {
			transicao.antes().ifPresent(reservas -> antesDoSwap.put(pool, reservas));
			return;
		}

		if (transicao.log().assinadoPor(Eventos.SWAP_V2)) {
			doSwapV2(pool, transicao);
			return;
		}

		if (Eventos.ehSwapV3(transicao.log())) {
			doSwapV3(pool, transicao);
		}
	}

	public void aoProvar(Consumer<ProvaDeTaxa> ouvinte) {
		this.aoProvar = ouvinte;
	}

	// Reconstrói a decisão a partir da evidência guardada. Não recebe taxa decidida de propósito:
	// a regra que decide mora aqui, e o banco que a guardasse pronta decidiria com a regra do dia
	// em que gravou — que é como "contradição é permanente" ficou embutida numa tabela.
	public void semear(List<ProvaDeTaxa> provas) {
		provas.forEach(prova -> registrar(chave(prova.pool()), prova.taxa(), prova.chamador(), prova.fechamentos()));
	}

	public Optional<Integer> de(byte[] pool) {
		return Optional.ofNullable(provadas.get(chave(pool)));
	}

	public int quantasProvadas() {
		return provadas.size();
	}

	public int quantasAmbiguas() {
		return ambiguas.size();
	}

	public Map<String, Long> motivos() {
		return Map.copyOf(motivos);
	}

	public Map<Integer, Set<String>> provadoresPorTaxa(byte[] pool) {
		return Map.copyOf(provadores.getOrDefault(chave(pool), Map.of()));
	}

	// Pool movimentada que só poucos chamadores usam: a taxa provada é a DELES, e o nosso
	// executor não será nenhum deles.
	public boolean fechadaAChamadores(byte[] pool) {

		String chave = chave(pool);

		List<String> chamadores = provadores.getOrDefault(chave, Map.of()).values().stream().flatMap(Set::stream)
				.distinct().toList();

		return fechamentos.getOrDefault(chave, 0L) >= FECHAMENTOS_DE_POOL_MOVIMENTADA
				&& chamadores.size() <= CHAMADORES_DE_POOL_FECHADA
				&& chamadores.stream().noneMatch(Taxas::roteadorPublico);
	}

	public static boolean roteadorPublico(String chamador) {
		return ROTEADORES_PUBLICOS.contains(chamador);
	}

	public List<String> poolsComMaisDeUmaTaxa() {
		return provadores.entrySet().stream().filter(entrada -> entrada.getValue().size() > 1)
				.map(Map.Entry::getKey).toList();
	}

	private void doSwapV2(String pool, Transicao transicao) {

		ReservasV2 antes = antesDoSwap.remove(pool);

		if (antes == null) {
			anotar("v2 sem estado anterior na transação");
			return;
		}

		BigInteger entrada0 = Eventos.palavra(transicao.log(), 0);
		BigInteger entrada1 = Eventos.palavra(transicao.log(), 1);

		// Uma das duas entradas é zero num swap normal. Se as duas são positivas ou as
		// duas nulas, não é uma travessia simples e não diz nada sobre a taxa.
		if (entrada0.signum() > 0 == entrada1.signum() > 0) {
			anotar("v2 entrada nos dois tokens");
			return;
		}

		boolean entraToken0 = entrada0.signum() > 0;

		Direcao direcao = entraToken0 ? Direcao.ZERO_PARA_UM : Direcao.UM_PARA_ZERO;
		BigInteger entrada = entraToken0 ? entrada0 : entrada1;
		BigInteger saida = entraToken0 ? Eventos.palavra(transicao.log(), 3) : Eventos.palavra(transicao.log(), 2);

		Optional<Integer> taxa = antes.taxaQueFecha(direcao, entrada, saida);

		anotar(taxa.isEmpty() ? "v2 nenhuma taxa fecha" : "v2 " + provar(pool, taxa.get(), chamador(transicao)));
	}

	private void doSwapV3(String pool, Transicao transicao) {

		EstadoV3 depois;

		try {

			depois = new EstadoV3(Eventos.palavra(transicao.log(), 2), Eventos.palavra(transicao.log(), 3),
					(int) Eventos.palavraComSinal(transicao.log(), 4), transicao.altura());

		} catch (IllegalArgumentException impossivel) {

			// O mesmo estado que o catálogo recusa. Sem ele não há de onde deduzir a taxa, e
			// deixar a recusa subir derrubaria o cursor que chamou.
			anotar("v3 estado impossível");
			return;
		}

		Optional<Integer> taxa = depois.taxaQueFecha(Eventos.palavraComSinalGrande(transicao.log(), 0),
				Eventos.palavraComSinalGrande(transicao.log(), 1));

		anotar(taxa.isEmpty() ? "v3 nenhum degrau fecha sozinho" : "v3 " + provar(pool, taxa.get(), chamador(transicao)));
	}

	private String provar(String pool, int taxa, String chamador) {

		if (taxa % Pool.PONTO_BASE != 0 || taxa >= Pool.ESCALA_DA_TAXA) {
			return "taxa quebrada";
		}

		String motivo = registrar(pool, taxa, chamador, 1);

		aoProvar.accept(new ProvaDeTaxa(HexFormat.of().parseHex(pool), taxa, chamador, 1));

		return motivo;
	}

	private String registrar(String pool, int taxa, String chamador, long quantas) {

		Map<Integer, Set<String>> porTaxa = provadores.computeIfAbsent(pool, qual -> new ConcurrentHashMap<>());

		fechamentos.merge(pool, quantas, Long::sum);

		boolean nova = !porTaxa.containsKey(taxa);

		porTaxa.computeIfAbsent(taxa, qual -> ConcurrentHashMap.newKeySet()).add(chamador);

		Optional<Integer> decidida = decidir(porTaxa);

		if (decidida.isPresent()) {
			provadas.put(pool, decidida.get());
			ambiguas.remove(pool);
		} else {
			provadas.remove(pool);
			ambiguas.add(pool);
		}

		if (porTaxa.size() == 1) {
			return nova ? "taxa provada" : "taxa confirmada";
		}

		return decidida.isPresent() ? "mais de uma taxa, resolvida pelo K" : "mais de uma taxa, ambígua";
	}

	private static Optional<Integer> decidir(Map<Integer, Set<String>> porTaxa) {

		if (porTaxa.size() == 1) {
			return porTaxa.keySet().stream().findFirst();
		}

		return porTaxa.entrySet().stream().filter(entrada -> entrada.getValue().size() >= 2).map(Map.Entry::getKey)
				.min(Integer::compare);
	}

	// O chamador do `swap` é o primeiro tópico indexado, no V2 e no V3.
	private static String chamador(Transicao transicao) {
		return transicao.log().topicos().size() < 2 ? "?"
				: HexFormat.of().formatHex(Eventos.enderecoDoTopico(transicao.log(), 1));
	}

	private void anotar(String motivo) {
		motivos.merge(motivo, 1L, Long::sum);
	}

	private static String chave(byte[] endereco) {
		return HexFormat.of().formatHex(endereco);
	}
}
