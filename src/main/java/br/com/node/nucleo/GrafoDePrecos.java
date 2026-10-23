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
import java.util.function.Predicate;

public final class GrafoDePrecos {

	private record Aresta(int de, int para, double peso, Perna perna, String pool) {
	}

	private static final double EPS = 1e-12;
	private static final int CICLO_MAX = 8;
	private static final double Q96 = 79228162514264337593543950336.0;

	private final Map<String, Integer> id = new HashMap<>();
	private final List<List<Integer>> saintes = new ArrayList<>();
	private final List<Aresta> arestas = new ArrayList<>();

	private final Set<String> v2 = new HashSet<>();

	private long semV2NaPonta;

	private long repescados;

	private static final int BUSCAS = 2;

	private final Set<Integer> riscadas = new HashSet<>();

	private boolean pontaLivre;

	public void pontaLivre(boolean livre) {
		this.pontaLivre = livre;
	}

	private final Map<String, Ciclo> semFlash = new HashMap<>();
	private static final int SEM_FLASH_MAX = 16;

	public List<Ciclo> ciclosSemFlash() {
		return List.copyOf(semFlash.values());
	}

	public void ligarV2(byte[] pool, Par par, BigInteger reserva0, BigInteger reserva1, int taxa) {
		ligarV2(pool, par, reserva0, reserva1, taxa, 1.0, 1.0);
	}

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
			aresta(par.token0(), par.token1(), -Math.log(liquido * imposto01 * r1 / r0),
					new Perna(pool, par.token0(), par.token1()));
		}
		if (imposto10 > 0) {
			aresta(par.token1(), par.token0(), -Math.log(liquido * imposto10 * r0 / r1),
					new Perna(pool, par.token1(), par.token0()));
		}
	}

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

	public void ligarV3(byte[] pool, Par par, BigInteger sqrtPrecoX96, BigInteger liquidez, int taxa) {
		ligarV3(pool, par.token0(), par.token1(), sqrtPrecoX96, liquidez, taxa);
	}

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

			if (achada.pontasSemFlash().isEmpty()) {
				break;
			}

			recusouPorPonta = true;
			riscadas.addAll(achada.pontasSemFlash());
		}

		if (recusouPorPonta) {
			semV2NaPonta++;
		}

		return Optional.empty();
	}

	public Optional<Ciclo> cicloPela(Set<String> poolsTocadas, Set<String> numerarios, int maxPernas) {
		return cicloPela(poolsTocadas, numerarios, maxPernas, ciclo -> true);
	}

	public Optional<Ciclo> cicloPela(Set<String> poolsTocadas, Set<String> numerarios, int maxPernas,
			Predicate<Ciclo> permitido) {
		List<Ciclo> ciclos = ciclosPela(poolsTocadas, numerarios, maxPernas, 1, permitido);
		return ciclos.isEmpty() ? Optional.empty() : Optional.of(ciclos.get(0));
	}

	public List<Ciclo> ciclosPela(Set<String> poolsTocadas, Set<String> numerarios, int maxPernas, int quantos) {
		return ciclosPela(poolsTocadas, numerarios, maxPernas, quantos, ciclo -> true);
	}

	public List<Ciclo> ciclosPela(Set<String> poolsTocadas, Set<String> numerarios, int maxPernas, int quantos,
			Predicate<Ciclo> permitido) {

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

			for (double[] linha : dist) {
				Arrays.fill(linha, Double.POSITIVE_INFINITY);
			}
			for (int[] linha : pai) {
				Arrays.fill(linha, -1);
			}

			dist[0][ancora.para()] = 0;

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
					Ciclo ciclo = busca.ciclo().get();
					if (!permitido.test(ciclo)) {
						continue;
					}
					Set<String> conjunto = new java.util.TreeSet<>();
					ciclo.pernas().forEach(p -> conjunto.add(HexFormat.of().formatHex(p.pool())));
					String chave = String.join("-", conjunto);
					if (total < pesoPorConjunto.getOrDefault(chave, Double.POSITIVE_INFINITY)) {
						pesoPorConjunto.put(chave, total);
						cicloPorConjunto.put(chave, ciclo);
					}
				}
			}
		}

		return pesoPorConjunto.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(quantos)
				.map(e -> cicloPorConjunto.get(e.getKey())).toList();
	}

	private Optional<List<Integer>> caminho(int[][] pai, int k, int alvo, int origem) {

		List<Integer> reverso = new ArrayList<>();

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

	private int ancorasNoGrafo;
	private double menorTotal = Double.POSITIVE_INFINITY;

	public int ancorasNoGrafo() {
		return ancorasNoGrafo;
	}

	public long melhorRetornoBps() {
		return Double.isInfinite(menorTotal) ? -1 : Math.round(Math.exp(-menorTotal) * 10_000);
	}

	public long ciclosSemV2NaPonta() {
		return semV2NaPonta;
	}

	public long ciclosRepescados() {
		return repescados;
	}

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