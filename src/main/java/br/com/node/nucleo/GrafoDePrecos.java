package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Acha arbitragem por CICLO NEGATIVO, em vez de enumerar ciclos.
//
// Aresta token i→j com peso `-ln(taxa marginal com fee)`. Num ciclo, soma dos pesos < 0 ⟺ produto das
// taxas > 1 ⟺ arb (e o lucro de ciclo é rotation-invariant). Bellman-Ford/SPFA acha UM ciclo negativo
// em O(V·E) — TODAS as pools, ciclo de qualquer tamanho, sem enumerar e sem teto. Foi a virada quando
// o catálogo passou de centenas para milhares: enumerar 3-ciclos das top-N pools escondia 93% das
// pools (a cauda esquecida, onde a arb não-disputada mora); isto alcança todas ao mesmo custo.
//
// SÓ acha o candidato — o peso é MARGINAL (trade infinitesimal), então diz "existe fenda", não
// "quanto rende". Quem dimensiona a entrada ótima e confere lucro ≥ piso é o `Avaliador`/Möbius sobre
// o ciclo devolvido. Peso em `double` basta para detectar o sinal; a conta que decide é exata, depois.
public final class GrafoDePrecos {

	// `pool` é o endereço em hexadecimal, calculado UMA vez ao ligar: a busca e a cópia do grafo perguntam por
	// ele a cada aresta, e converter 20 mil endereços por busca era trabalho repetido.
	private record Aresta(int de, int para, double peso, Perna perna, String pool) {
	}

	private static final double EPS = 1e-12;
	private static final int CICLO_MAX = 8; // ciclo mais longo que isto não paga fee; recusa na recuperação
	private static final double Q96 = 79228162514264337593543950336.0; // 2^96

	private final Map<String, Integer> id = new HashMap<>();
	private final List<List<Integer>> saintes = new ArrayList<>();
	private final List<Aresta> arestas = new ArrayList<>();

	// As pools V2 do grafo, por endereço. É o que decide quem pode ser a PONTA: o empréstimo
	// relâmpago é o `swap` com dados não vazios, que só a família UniswapV2 oferece. O V3 continua
	// ligado — no MIOLO ele soma —, mas não empresta.
	private final Set<String> v2 = new HashSet<>();

	// Ciclos que tocavam numerário mas só por uma ponta V3 — recusados por não haver flash. Contado
	// para que "não achou" nunca esconda "achou, mas não dava para entrar".
	//
	// Conta o que ficou SEM alternativa, não cada tentativa: quando a segunda busca acha outro ciclo, a
	// recusa virou cobertura, e isso é o `repescados` que diz.
	private long semV2NaPonta;

	// Ciclos que a busca SEGUINTE achou depois de a anterior voltar com ponta V3. É a medida que decide
	// se a segunda busca paga o que custa: zero no relatório, sai; acima de zero, era cobertura perdida.
	private long repescados;

	// Quantas buscas o grafo se permite quando o ciclo que achou não pode ser ENTRADO (ponta V3). Só a
	// segunda é extra, e ela só roda nesse caso — que foi 41% dos ciclos medidos na corrida de 9 h.
	private static final int BUSCAS = 2;

	// Arestas riscadas SÓ desta chamada, por índice na lista de arestas. Quando o ciclo achado tem ponta
	// V3, a busca seguinte risca AQUELA aresta (a ponta, não o miolo) e procura outro ciclo: o contrato
	// não aceita entrar por V3, então um ciclo que entra por V3 não é oportunidade — é espera perdida.
	// O grafo é descartável (um por bloco), mas a chamada limpa no começo para não depender disso.
	private final Set<Integer> riscadas = new HashSet<>();

	// PONTA LIVRE: o Executor v3 empresta também da primeira perna V3 ou de gerente, e então qualquer pool
	// que entra no numerário serve de ponta (a V2 ainda é preferida quando o ciclo tem uma). Desligada, a
	// ponta tem de ser V2 como antes.
	private boolean pontaLivre;

	public void pontaLivre(boolean livre) {
		this.pontaLivre = livre;
	}

	// Os ciclos que a ÚLTIMA `ciclosPela` achou negativos e jogou fora por não ter V2 na ponta, já rodados
	// para entrar pelo numerário. CICATRIZ de 26/09: eram descartados sem log nem contador — 48 das 53
	// arbitragens alheias que o modelo achava acima do piso em 78 min (99% do valor) morriam aqui, e parecia
	// que o node nunca as tinha visto. Quem monta a busca avalia e conta.
	private final Map<String, Ciclo> semFlash = new HashMap<>();
	private static final int SEM_FLASH_MAX = 16;

	public List<Ciclo> ciclosSemFlash() {
		return List.copyOf(semFlash.values());
	}

	// Liga uma pool V2 como duas arestas (cada sentido). Taxa marginal 0→1 = (1 - taxa/escala)·(r1/r0);
	// peso = -ln(rate). Reserva zero ou taxa cheia não liga (não coteável).
	public void ligarV2(byte[] pool, Par par, BigInteger reserva0, BigInteger reserva1, int taxa) {
		ligarV2(pool, par, reserva0, reserva1, taxa, 1.0, 1.0);
	}

	// Com o IMPOSTO de cada sentido (`Cotador.fatorDeImposto`): multiplica o preço marginal, como a taxa da pool.
	// Fator zero é sentido que não se pode fazer (honeypot, venda taxada em pool que não é V2) — sem aresta.
	public void ligarV2(byte[] pool, Par par, BigInteger reserva0, BigInteger reserva1, int taxa, double imposto01,
			double imposto10) {

		double liquido = 1.0 - taxa / (double) Pool.ESCALA_DA_TAXA;
		double r0 = reserva0.doubleValue();
		double r1 = reserva1.doubleValue();

		if (liquido <= 0 || r0 <= 0 || r1 <= 0) {
			return;
		}

		v2.add(HexFormat.of().formatHex(pool));

		if (imposto01 > 0) {
			aresta(par.token0(), par.token1(), -Math.log(liquido * imposto01 * r1 / r0), new Perna(pool, par.token0(), par.token1()));
		}
		if (imposto10 > 0) {
			aresta(par.token1(), par.token0(), -Math.log(liquido * imposto10 * r0 / r1), new Perna(pool, par.token1(), par.token0()));
		}
	}

	// Liga uma pool Solidly stable (x³y+xy³=k) como duas arestas. A taxa marginal 0→1 =
	// [y*(3x²+y²)] / [x*(x²+3y²)] × (1-fee); mesma assinatura de flash loan que V2, então vai ao `v2`.
	public void ligarSolidlyStable(byte[] pool, Par par, BigInteger reserva0, BigInteger reserva1, int taxa) {
		ligarSolidlyStable(pool, par, reserva0, reserva1, taxa, 1.0, 1.0);
	}

	public void ligarSolidlyStable(byte[] pool, Par par, BigInteger reserva0, BigInteger reserva1, int taxa,
			double imposto01, double imposto10) {

		double liquido = 1.0 - taxa / (double) Pool.ESCALA_DA_TAXA;
		double x = reserva0.doubleValue();
		double y = reserva1.doubleValue();

		if (liquido <= 0 || x <= 0 || y <= 0) {
			return;
		}

		// Taxa marginal 0→1 = y*(3x²+y²) / (x*(x²+3y²))
		double rate01 = (y * (3 * x * x + y * y)) / (x * (x * x + 3 * y * y));
		double rate10 = 1.0 / rate01;

		v2.add(HexFormat.of().formatHex(pool));

		if (imposto01 > 0 && Double.isFinite(rate01) && rate01 > 0) {
			aresta(par.token0(), par.token1(), -Math.log(liquido * imposto01 * rate01),
					new Perna(pool, par.token0(), par.token1()));
		}
		if (imposto10 > 0 && Double.isFinite(rate10) && rate10 > 0) {
			aresta(par.token1(), par.token0(), -Math.log(liquido * imposto10 * rate10),
					new Perna(pool, par.token1(), par.token0()));
		}
	}

	// Liga uma pool V3 (liquidez concentrada) como duas arestas. O preço marginal no tick corrente é
	// (sqrtPrecoX96/2^96)² = token1/token0; a taxa marginal 0→1 = preço·(1-fee), 1→0 = (1-fee)/preço.
	// Sem liquidez ativa não cota (o `aplicar` reverte), então não liga. É o que faltava: as arbs que
	// os bots vencem são em V3, e o grafo era V2-only — cego a elas.
	public void ligarV3(byte[] pool, Par par, BigInteger sqrtPrecoX96, BigInteger liquidez, int taxa) {
		ligarV3(pool, par.token0(), par.token1(), sqrtPrecoX96, liquidez, taxa);
	}

	// Com os LADOS da pool (a ordem em que ela calcula √P), que só diferem do `Par` na pool de gerente com
	// BNB nativo — ver `Catalogo.Lados`.
	public void ligarV3(byte[] pool, byte[] token0, byte[] token1, BigInteger sqrtPrecoX96, BigInteger liquidez,
			int taxa) {
		ligarV3(pool, token0, token1, sqrtPrecoX96, liquidez, taxa, 1.0, 1.0);
	}

	public void ligarV3(byte[] pool, byte[] token0, byte[] token1, BigInteger sqrtPrecoX96, BigInteger liquidez,
			int taxa, double imposto01, double imposto10) {

		double liquido = 1.0 - taxa / (double) Pool.ESCALA_DA_TAXA;
		double sp = sqrtPrecoX96.doubleValue() / Q96;
		double preco = sp * sp;

		if (liquido <= 0 || liquidez.signum() <= 0 || preco <= 0 || !Double.isFinite(preco)) {
			return;
		}

		if (imposto01 > 0) {
			aresta(token0, token1, -Math.log(preco * liquido * imposto01), new Perna(pool, token0, token1));
		}
		if (imposto10 > 0) {
			aresta(token1, token0, -Math.log(liquido * imposto10 / preco), new Perna(pool, token1, token0));
		}
	}

	public int tokens() {
		return saintes.size();
	}

	// Um ciclo negativo (arb) que passe por um dos numerários — pronto para começar nele (flash da
	// primeira perna). Vazio se não há arb marginal, ou a que há não toca numerário (sem como emprestar
	// ou precificar o piso). SPFA: `dist` zerado e todos na fila acha QUALQUER ciclo negativo; contar
	// V relaxamentos de um nó denuncia o ciclo, e a cadeia de pais o recupera.
	//
	// A busca é repetida (uma vez) quando o ciclo achado não pode ser entrado: a SPFA devolve UM ciclo
	// qualquer, e "o primeiro que ela achou" não é "o melhor" nem, pior, "o entrável". MEDIDO: 41% dos
	// ciclos de uma corrida de 9 h foram recusados por ponta V3 — e cada recusa era um bloco inteiro
	// jogado fora sem saber se havia outro ciclo ali. A segunda busca responde isso com o mesmo código:
	// risca a ponta que não empresta e procura de novo.
	public Optional<Ciclo> cicloNegativo(Set<String> numerarios) {

		int v = saintes.size();

		if (v == 0) {
			return Optional.empty();
		}

		riscadas.clear();

		boolean recusouPorPonta = false;

		for (int busca = 0; busca < BUSCAS; busca++) {

			Busca achada = buscar(v, numerarios);

			if (achada.ciclo().isPresent()) {

				if (busca > 0) {
					repescados++;
				}

				return achada.ciclo();
			}

			// Recusa que não é de ponta (ciclo sem numerário, ciclo longo) não melhora riscando
			// aresta: a busca seguinte devolveria o mesmo nada.
			if (achada.pontasSemFlash().isEmpty()) {
				break;
			}

			recusouPorPonta = true;
			riscadas.addAll(achada.pontasSemFlash());
		}

		// Só conta a recusa de ponta quando nenhuma busca achou alternativa — que é quando o bloco
		// ficou sem oportunidade por causa dela.
		if (recusouPorPonta) {
			semV2NaPonta++;
		}

		return Optional.empty();
	}

	// O que uma busca devolve: o ciclo (já rotacionado para o numerário) ou as arestas que impediram a
	// entrada. Separar as duas coisas é o que permite riscar só a ponta e tentar de novo.
	// O melhor ciclo que PASSA por uma das pools tocadas — a busca do N+1.
	//
	// CICATRIZ: o N+1 usava `cicloNegativo`, que devolve UM ciclo negativo QUALQUER do grafo. O grafo
	// quase sempre tem fenda-poeira persistente em algum canto (3.126 ciclos negativos em 10.490 blocos),
	// e a SPFA devolvia essa em vez da que o swap pendente acabou de abrir — a projeção olhava para o
	// lugar errado. Aqui a busca é ANCORADA: para cada sentido de cada pool tocada (a→b), o caminho mais
	// barato b→a no resto do grafo com no máximo `maxPernas − 1` arestas (Bellman-Ford limitado por
	// saltos: imune a ciclo negativo alheio, O(maxPernas·E) por aresta tocada). Ciclo = aresta + caminho.
	// O melhor dos que fecham negativo, rotacionado para entrar por numerário com ponta V2.
	public Optional<Ciclo> cicloPela(Set<String> poolsTocadas, Set<String> numerarios, int maxPernas) {
		List<Ciclo> ciclos = ciclosPela(poolsTocadas, numerarios, maxPernas, 1);
		return ciclos.isEmpty() ? Optional.empty() : Optional.of(ciclos.get(0));
	}

	// Os `quantos` melhores ciclos DISTINTOS (por conjunto de pools) que passam pelas pools tocadas, do
	// mais lucrativo (marginal) ao menos. É o que o garimpo do estado confirmado usa: um bloco abre várias
	// fendas, e devolver só a melhor deixava as outras para os bots que as pegam no bloco seguinte.
	public List<Ciclo> ciclosPela(Set<String> poolsTocadas, Set<String> numerarios, int maxPernas, int quantos) {

		int v = saintes.size();

		if (v == 0 || poolsTocadas.isEmpty() || maxPernas < 2) {
			return List.of();
		}

		riscadas.clear();
		semFlash.clear();

		Map<String, Double> pesoPorConjunto = new HashMap<>();
		Map<String, Ciclo> cicloPorConjunto = new HashMap<>();
		ancorasNoGrafo = 0;
		menorTotal = Double.POSITIVE_INFINITY;

		String[] poolDe = new String[arestas.size()];
		for (int ei = 0; ei < arestas.size(); ei++) {
			poolDe[ei] = arestas.get(ei).pool();
		}

		int saltos = maxPernas - 1;
		double[][] dist = new double[saltos + 1][v];
		int[][] pai = new int[saltos + 1][v];

		for (int ei = 0; ei < arestas.size(); ei++) {

			Aresta ancora = arestas.get(ei);
			String poolDaAncora = poolDe[ei];

			if (!poolsTocadas.contains(poolDaAncora)) {
				continue;
			}

			ancorasNoGrafo++;

			// dist[k][x]: menor peso de b até x com exatamente até k arestas; pai[k][x]: a aresta usada.
			// As matrizes são da CHAMADA, não da âncora: alocar duas por âncora, do tamanho de todos os tokens,
			// era lixo de centenas de KB por âncora e centenas de âncoras por bloco.
			for (double[] linha : dist) {
				Arrays.fill(linha, Double.POSITIVE_INFINITY);
			}
			for (int[] linha : pai) {
				Arrays.fill(linha, -1);
			}

			dist[0][ancora.para()] = 0;

			// POR FRONTEIRA: no salto k só relaxam as arestas que SAEM dos tokens alcançados no salto k−1. A
			// varredura antiga passava por todas as arestas do grafo em cada salto, inclusive as que partem de
			// tokens que o caminho nem tocou — o mesmo resultado com trabalho proporcional ao grafo inteiro.
			List<Integer> fronteira = new ArrayList<>(List.of(ancora.para()));

			for (int k = 1; k <= saltos && !fronteira.isEmpty(); k++) {

				List<Integer> proxima = new ArrayList<>();

				for (int x : fronteira) {

					double base = dist[k - 1][x];

					for (int ej : saintes.get(x)) {

						if (poolDe[ej].equals(poolDaAncora)) {
							continue;
						}

						Aresta a = arestas.get(ej);
						double d = base + a.peso();

						if (d < dist[k][a.para()]) {
							if (dist[k][a.para()] == Double.POSITIVE_INFINITY) {
								proxima.add(a.para());
							}
							dist[k][a.para()] = d;
							pai[k][a.para()] = ej;
						}
					}
				}

				fronteira = proxima;
			}

			for (int k = 1; k <= saltos; k++) {

				double total = ancora.peso() + dist[k][ancora.de()];

				if (total < menorTotal && caminho(pai, k, ancora.de(), ancora.para()).isPresent()) {
					menorTotal = total;
				}

				if (total >= -EPS) {
					continue;
				}

				Optional<List<Integer>> caminho = caminho(pai, k, ancora.de(), ancora.para());

				if (caminho.isEmpty()) {
					continue;
				}

				List<Perna> pernas = new ArrayList<>();
				List<Integer> indices = new ArrayList<>();
				pernas.add(ancora.perna());
				indices.add(ei);

				for (int ej : caminho.get()) {
					pernas.add(arestas.get(ej).perna());
					indices.add(ej);
				}

				Busca busca = rotarParaNumerario(pernas, indices, numerarios);

				if (busca.ciclo().isEmpty() && !busca.pontasSemFlash().isEmpty() && semFlash.size() < SEM_FLASH_MAX) {
					Ciclo descartado = rodado(pernas, numerarios);
					Set<String> conjunto = new java.util.TreeSet<>();
					descartado.pernas().forEach(p -> conjunto.add(HexFormat.of().formatHex(p.pool())));
					semFlash.putIfAbsent(String.join("-", conjunto), descartado);
				}

				if (busca.ciclo().isPresent()) {
					Set<String> conjunto = new java.util.TreeSet<>();
					busca.ciclo().get().pernas().forEach(p -> conjunto.add(HexFormat.of().formatHex(p.pool())));
					String chave = String.join("-", conjunto);
					if (total < pesoPorConjunto.getOrDefault(chave, Double.POSITIVE_INFINITY)) {
						pesoPorConjunto.put(chave, total);
						cicloPorConjunto.put(chave, busca.ciclo().get());
					}
				}
			}
		}

		return pesoPorConjunto.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(quantos)
				.map(e -> cicloPorConjunto.get(e.getKey())).toList();
	}

	// Reconstrói o caminho de `origem` até `alvo` com k arestas, em ordem de fluxo. Vazio quando repete
	// token ou pool — caminho não simples é outro ciclo embutido, não o que se procurou.
	private Optional<List<Integer>> caminho(int[][] pai, int k, int alvo, int origem) {

		List<Integer> reverso = new ArrayList<>();
		// A origem entra já visitada: passar por ela no meio é embutir outro ciclo — a fenda alheia
		// pegando carona na do swap, que foi o que o teste pegou.
		Set<Integer> nos = new HashSet<>(Set.of(origem));
		Set<String> pools = new HashSet<>();
		int cur = alvo;

		for (int passo = k; passo >= 1; passo--) {

			int ej = pai[passo][cur];

			if (ej < 0) {
				return Optional.empty();
			}

			Aresta a = arestas.get(ej);

			if (!nos.add(cur) || !pools.add(HexFormat.of().formatHex(a.perna().pool()))) {
				return Optional.empty();
			}

			reverso.add(ej);
			cur = a.de();
		}

		if (cur != origem) {
			return Optional.empty();
		}

		Collections.reverse(reverso);
		return Optional.of(reverso);
	}

	private record Busca(Optional<Ciclo> ciclo, List<Integer> pontasSemFlash) {

		private static Busca achou(Ciclo ciclo) {
			return new Busca(Optional.of(ciclo), List.of());
		}

		private static Busca nada() {
			return new Busca(Optional.empty(), List.of());
		}

		private static Busca semFlash(List<Integer> arestas) {
			return new Busca(Optional.empty(), List.copyOf(arestas));
		}
	}

	// A SPFA propriamente dita, uma passada. Não muda entre as buscas: o que muda é `riscadas`.
	private Busca buscar(int v, Set<String> numerarios) {

		double[] dist = new double[v];
		int[] pai = new int[v];
		int[] contagem = new int[v];
		boolean[] naFila = new boolean[v];
		Arrays.fill(pai, -1);

		Deque<Integer> fila = new ArrayDeque<>();
		for (int i = 0; i < v; i++) {
			fila.add(i);
			naFila[i] = true;
		}

		while (!fila.isEmpty()) {

			int u = fila.poll();
			naFila[u] = false;

			for (int ei : saintes.get(u)) {

				if (riscadas.contains(ei)) {
					continue;
				}

				Aresta a = arestas.get(ei);

				if (dist[u] + a.peso() < dist[a.para()] - EPS) {
					dist[a.para()] = dist[u] + a.peso();
					pai[a.para()] = ei;

					if (++contagem[a.para()] >= v) {
						return recuperar(a.para(), pai, numerarios);
					}

					if (!naFila[a.para()]) {
						fila.add(a.para());
						naFila[a.para()] = true;
					}
				}
			}
		}

		return Busca.nada();
	}

	// Do nó denunciado, anda V passos pela cadeia de pais para cair DENTRO do ciclo, depois coleta as
	// arestas até fechar. Vêm em ordem reversa (pai aponta para trás); inverte para o sentido do fluxo.
	// Guarda os ÍNDICES junto: é por eles que a busca seguinte risca a ponta que não empresta.
	private Busca recuperar(int gatilho, int[] pai, Set<String> numerarios) {

		int x = gatilho;
		for (int i = 0; i < saintes.size(); i++) {
			x = arestas.get(pai[x]).de();
		}

		List<Perna> pernas = new ArrayList<>();
		List<Integer> indices = new ArrayList<>();
		int cur = x;

		do {
			int ei = pai[cur];
			Aresta a = arestas.get(ei);
			pernas.add(a.perna());
			indices.add(ei);
			cur = a.de();

			if (pernas.size() > CICLO_MAX) {
				return Busca.nada();
			}
		} while (cur != x);

		Collections.reverse(pernas);
		Collections.reverse(indices);

		return rotarParaNumerario(pernas, indices, numerarios);
	}

	// Rotaciona o ciclo para começar na perna que ENTRA num numerário (WBNB/USDT) — a que o flash
	// empresta e em que o lucro se mede. Sem numerário no ciclo, não é executável pela nossa via.
	//
	// E a ponta tem de ser V2, senão não há flash nenhum: o empréstimo relâmpago é o `swap` com dados
	// não vazios, que só a família UniswapV2 oferece, então começar numa V3 reverte com
	// `EmprestimoNaoV2` na certa. MEDIDO no `run-armado`: 1 dos 7 envios morreu exatamente assim, com
	// a rota começando em V3 — o revert não custou gás, mas custou a volta para não aprender nada.
	//
	// O V3 continua no grafo, e deve: as arbs que o mercado vence moram nele. O que ele não pode é ser
	// a PONTA. No miolo ele soma; na ponta ele inviabiliza.
	//
	// Quando SÓ há ponta V3, devolve as arestas dessas pontas em vez de vazio: é o que a busca seguinte
	// risca para tentar outro ciclo. Nesse caso o ciclo completo se perde (não dá para entrar), mas o
	// MIOLO dele não é riscado — só a entrada que o contrato recusaria.
	private Busca rotarParaNumerario(List<Perna> pernas, List<Integer> indices, Set<String> numerarios) {

		boolean tocouNumerario = false;
		List<Integer> pontasSemFlash = new ArrayList<>();

		for (int i = 0; i < pernas.size(); i++) {

			Perna perna = pernas.get(i);

			if (!numerarios.contains(HexFormat.of().formatHex(perna.entra()))) {
				continue;
			}

			tocouNumerario = true;

			if (!v2.contains(HexFormat.of().formatHex(perna.pool()))) {
				pontasSemFlash.add(indices.get(i));
				continue;
			}

			List<Perna> rotado = new ArrayList<>();
			for (int k = 0; k < pernas.size(); k++) {
				rotado.add(pernas.get((i + k) % pernas.size()));
			}

			return Busca.achou(new Ciclo(List.copyOf(rotado)));
		}

		if (tocouNumerario && pontaLivre) {
			return Busca.achou(rodado(pernas, numerarios));
		}

		return tocouNumerario ? Busca.semFlash(pontasSemFlash) : Busca.nada();
	}

	// O ciclo rodado para começar na PRIMEIRA perna que entra num numerário, seja qual for o tipo da pool.
	private static Ciclo rodado(List<Perna> pernas, Set<String> numerarios) {

		int i = 0;
		while (i < pernas.size() && !numerarios.contains(HexFormat.of().formatHex(pernas.get(i).entra()))) {
			i++;
		}

		List<Perna> rotado = new ArrayList<>();
		for (int k = 0; k < pernas.size(); k++) {
			rotado.add(pernas.get((i + k) % pernas.size()));
		}

		return new Ciclo(List.copyOf(rotado));
	}

	// Ciclos recusados porque a ponta no numerário era V3 (sem flash possível) e NENHUMA busca achou
	// alternativa. Exposto para que o funil conte a recusa em vez de engoli-la.
	// O diagnóstico da última `cicloPela`: quantas arestas das pools tocadas estavam no grafo, e o
	// MELHOR ciclo que passava por elas, lucrativo ou não. Sem isto "o N+1 não achou nada" não separa
	// "o swap não abriu fenda" (retorno abaixo de 1) de "a pool tocada nem estava no grafo" (zero âncoras).
	private int ancorasNoGrafo;
	private double menorTotal = Double.POSITIVE_INFINITY;

	public int ancorasNoGrafo() {
		return ancorasNoGrafo;
	}

	// Retorno marginal do melhor ciclo pelas pools tocadas, em bps (10.000 = empata). -1 se nenhum.
	public long melhorRetornoBps() {
		return Double.isInfinite(menorTotal) ? -1 : Math.round(Math.exp(-menorTotal) * 10_000);
	}

	public long ciclosSemV2NaPonta() {
		return semV2NaPonta;
	}

	// Ciclos que a SEGUNDA busca achou onde a primeira voltou com ponta V3. É o que a segunda busca
	// recuperou de um bloco que antes era jogado fora inteiro; zero persistente diz que ela pode sair.
	public long ciclosRepescados() {
		return repescados;
	}

	// O MESMO grafo sem as arestas das pools dadas — para religá-las com o estado projetado de um swap pendente.
	// Existe porque remontar o grafo do zero a cada swap pendente era ~75% do tempo do N+1 (8 de 11 ms com 2 mil
	// pools, medido em 26/09) e crescia com o catálogo: no servidor, depois de horas, a projeção chegou a 108 ms.
	// A cópia só move listas — sem catálogo, sem tarifário, sem logaritmo. Os nós (tokens) são os mesmos.
	public GrafoDePrecos semAsPools(Set<String> pools) {

		GrafoDePrecos copia = new GrafoDePrecos();
		copia.id.putAll(id);
		copia.pontaLivre = pontaLivre;

		for (int i = 0; i < saintes.size(); i++) {
			copia.saintes.add(new ArrayList<>());
		}

		for (Aresta a : arestas) {
			if (!pools.contains(a.pool())) {
				copia.saintes.get(a.de()).add(copia.arestas.size());
				copia.arestas.add(a);
			}
		}

		for (String pool : v2) {
			if (!pools.contains(pool)) {
				copia.v2.add(pool);
			}
		}

		return copia;
	}

	private void aresta(byte[] de, byte[] para, double peso, Perna perna) {
		int a = no(de);
		int b = no(para);
		saintes.get(a).add(arestas.size());
		arestas.add(new Aresta(a, b, peso, perna, HexFormat.of().formatHex(perna.pool())));
	}

	private int no(byte[] token) {
		return id.computeIfAbsent(HexFormat.of().formatHex(token), t -> {
			saintes.add(new ArrayList<>());
			return saintes.size() - 1;
		});
	}
}
