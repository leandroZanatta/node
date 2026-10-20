package br.com.node.catalogo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Perna;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// O que a CADEIA nos ensinou sobre cada pool, para o erro não se repetir.
//
// Existe por causa do que o log mostrou: a mesma fenda, com o mesmo lucro ao wei, achada por 30+
// blocos seguidos e despachada de novo a cada minuto da janela de dedup. Arb real não sobrevive a um
// bloco na BSC; a que sobrevive é o modelo errado numa pool, e o `GrafoDePrecos` devolve UM ciclo
// negativo por bloco — enquanto o fantasma estiver no grafo, ele é o ciclo, e esconde todos os outros.
//
// Quem ensina é a `Autopsia`, com número. Três lições, cada uma com o seu remédio:
//   - PENA: a pool desmentida sai do grafo por um tempo, que DOBRA a cada reincidência (até PENA_MAXIMA)
//     — o motivo passageiro (estado atrasado) volta logo, o permanente (token quebrado) para de voltar;
//   - SEM EMPRÉSTIMO: a pool chama no flash swap um callback que o Executor não implementa; como perna
//     do meio ela serve, só a primeira posição lhe é negada. Fato do contrato dela: não expira;
//   - TAXA MEDIDA: a taxa que a pool cobra de NÓS, achada pela cadeia quando a nossa reverteu por `K`.
//     Vence a prova por troca no `Tarifario`, porque é medida com o nosso chamador — a 7a3eb6e7 cobra
//     5 bps dos seus dois chamadores e 10% de quem chama o par direto.
//
// PERSISTE em disco (quando há arquivo): o node é reiniciado muitas vezes por sessão, e sem isto cada
// restart reabria o mesmo fantasma e repetia o mesmo revert.
public final class Quarentena {

	private static final Logger LOG = Logger.getLogger(Quarentena.class.getName());

	private static final Duration PENA_MAXIMA = Duration.ofHours(24);

	private record Pena(long ate, String motivo, int reincidencias) {
	}

	private final Map<String, Pena> penas = new ConcurrentHashMap<>();
	private final Map<String, Pena> rotas = new ConcurrentHashMap<>();
	private final Map<String, String> semEmprestimo = new ConcurrentHashMap<>();
	private final Map<String, Integer> taxas = new ConcurrentHashMap<>();
	private final java.util.concurrent.atomic.AtomicLong exclusoesPorPena = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong exclusoesPorRota = new java.util.concurrent.atomic.AtomicLong();

	private final Path arquivo;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	public Quarentena() {
		this(null);
	}

	public Quarentena(Path arquivo) {
		this.arquivo = arquivo;
		carregar();
	}

	// Condena por `base`, dobrando a cada reincidência. A contagem sobrevive ao fim
	// da pena: é o que
	// separa "errou uma vez" de "erra sempre".
	public void condenar(byte[] pool, Duration base, String motivo) {

		String chave = HexFormat.of().formatHex(pool);
		Pena anterior = penas.get(chave);
		int reincidencias = anterior == null ? 0 : anterior.reincidencias() + 1;

		long milis = Math.min(PENA_MAXIMA.toMillis(), base.toMillis() << Math.min(reincidencias, 20));
		penas.put(chave, new Pena(System.currentTimeMillis() + milis, motivo, reincidencias));

		long minutos = milis / 60_000;
		String durStr = minutos < 2 ? milis + "ms" : minutos + "min";
		LOG.info(() -> "quarentena: condenando " + chave + " por " + durStr
				+ (reincidencias > 0 ? " (reincidência " + reincidencias + ")" : "") + " — " + motivo);

		salvar();
	}

	public boolean livre(byte[] pool) {
		Pena pena = penas.get(HexFormat.of().formatHex(pool));
		boolean bloqueada = pena != null && pena.ate() > System.currentTimeMillis();
		if (bloqueada) {
			exclusoesPorPena.incrementAndGet();
		}
		return !bloqueada;
	}

	// Uma falha TF não identifica qual perna/token falhou. A sequência ordenada é a única unidade
	// segura para suspender: as mesmas pools continuam disponíveis em outros caminhos.
	public void condenarRota(List<String> saltos, Duration base, String motivo) {

		String chave = chaveRota(saltos);
		Pena anterior = rotas.get(chave);
		int reincidencias = anterior == null ? 0 : anterior.reincidencias() + 1;
		long milis = Math.min(PENA_MAXIMA.toMillis(), base.toMillis() << Math.min(reincidencias, 20));
		rotas.put(chave, new Pena(System.currentTimeMillis() + milis, motivo, reincidencias));

		long minutos = milis / 60_000;
		String durStr = minutos < 2 ? milis + "ms" : minutos + "min";
		LOG.info(() -> "quarentena: suspendendo rota " + chave + " por " + durStr
				+ (reincidencias > 0 ? " (reincidência " + reincidencias + ")" : "") + " — " + motivo);
		salvar();
	}

	public boolean livreRota(Ciclo ciclo) {
		return livreRota(chaveRota(ciclo));
	}

	private boolean livreRota(String chave) {
		Pena pena = rotas.get(chave);
		boolean bloqueada = pena != null && pena.ate() > System.currentTimeMillis();
		if (bloqueada) {
			exclusoesPorRota.incrementAndGet();
		}
		return !bloqueada;
	}

	public void absolverRota(Ciclo ciclo) {
		absolverRota(chaveRota(ciclo));
	}

	public void absolverRota(List<String> saltos) {
		absolverRota(chaveRota(saltos));
	}

	private void absolverRota(String chave) {
		if (rotas.remove(chave) != null) {
			salvar();
		}
	}

	// Chaves independem da rotação inicial do ciclo, mas preservam o sentido de cada troca.
	public static String chaveRota(Ciclo ciclo) {
		List<String> saltos = ciclo.pernas().stream().map(Quarentena::salto).toList();
		return chaveRota(saltos);
	}

	public static String chaveRota(List<String> saltos) {
		if (saltos.isEmpty()) {
			return "";
		}
		List<String> normalizados = saltos.stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList();
		String menor = null;
		for (int inicio = 0; inicio < normalizados.size(); inicio++) {
			StringBuilder rodada = new StringBuilder();
			for (int i = 0; i < normalizados.size(); i++) {
				if (i > 0) {
					rodada.append('>');
				}
				rodada.append(normalizados.get((inicio + i) % normalizados.size()));
			}
			String candidata = rodada.toString();
			if (menor == null || candidata.compareTo(menor) < 0) {
				menor = candidata;
			}
		}
		return menor;
	}

	private static String salto(Perna perna) {
		HexFormat hex = HexFormat.of();
		return hex.formatHex(perna.pool()) + ":" + hex.formatHex(perna.entra()) + ":"
				+ hex.formatHex(perna.sai());
	}

	public void proibirEmprestimo(byte[] pool, String callback) {
		if (semEmprestimo.put(HexFormat.of().formatHex(pool), callback) == null) {
			salvar();
		}
	}

	public boolean podeEmprestar(byte[] pool) {
		return !semEmprestimo.containsKey(HexFormat.of().formatHex(pool));
	}

	// Taxa em milionésimos, a mesma escala do `Tarifario`.
	public void medirTaxa(byte[] pool, int taxa) {
		taxas.put(HexFormat.of().formatHex(pool), taxa);
		salvar();
	}

	public Optional<Integer> taxaMedida(byte[] pool) {
		return Optional.ofNullable(taxas.get(HexFormat.of().formatHex(pool)));
	}

	// As que cumprem pena agora, com o motivo — para o relatório dizer QUEM saiu do
	// grafo e por quê.
	public Map<String, String> presas() {

		long agora = System.currentTimeMillis();
		Map<String, String> presas = new LinkedHashMap<>();

		penas.forEach((pool, pena) -> {
			if (pena.ate() > agora) {
				presas.put(pool, pena.motivo()
						+ (pena.reincidencias() > 0 ? " (reincidência " + pena.reincidencias() + ")" : ""));
			}
		});

		return presas;
	}

	// Limpa penas de pool e de rota, preservando taxas medidas e restrições de empréstimo.
	// Usado via POST /quarentena/limpar para liberar itens suspensos por erros passados.
	public int limparPenas() {
		int total = penas.size() + rotas.size();
		penas.clear();
		rotas.clear();
		salvar();
		return total;
	}

	// Resumo para o endpoint HTTP /quarentena.
	public Map<String, Object> statusHttp() {
		long agora = System.currentTimeMillis();
		Map<String, Object> r = new LinkedHashMap<>();
		long ativas = penas.values().stream().filter(p -> p.ate() > agora).count();
		r.put("penas_ativas", ativas);
		r.put("penas_total", penas.size());
		long rotasAtivas = rotas.values().stream().filter(p -> p.ate() > agora).count();
		r.put("rotas_penas_ativas", rotasAtivas);
		r.put("rotas_penas_total", rotas.size());
		r.put("taxas_medidas", taxas.size());
		r.put("sem_emprestimo_proibido", semEmprestimo.size());
		r.put("exclusoes_por_pena_acumuladas", exclusoesPorPena.get());
		r.put("exclusoes_por_rota_acumuladas", exclusoesPorRota.get());
		Map<String, Long> porMotivo = new java.util.TreeMap<>();
		penas.forEach((pool, pena) -> {
			if (pena.ate() > agora) {
				String chave = pena.motivo().length() > 50 ? pena.motivo().substring(0, 50) : pena.motivo();
				porMotivo.merge(chave, 1L, Long::sum);
			}
		});
		r.put("ativas_por_motivo", porMotivo);

		List<Map<String, Object>> detalhe = penas.entrySet().stream().filter(e -> e.getValue().ate() > agora)
				.sorted((a, b) -> Long.compare(b.getValue().ate(), a.getValue().ate())).limit(20).map(e -> {
					Map<String, Object> p = new LinkedHashMap<>();
					p.put("pool", e.getKey());
					p.put("restam_min", (e.getValue().ate() - agora) / 60_000);
					p.put("reincidencias", e.getValue().reincidencias());
					p.put("motivo", e.getValue().motivo());
					return p;
				}).toList();
		r.put("penas_ativas_detalhe", detalhe);
		List<Map<String, Object>> rotasDetalhe = rotas.entrySet().stream().filter(e -> e.getValue().ate() > agora)
				.sorted((a, b) -> Long.compare(b.getValue().ate(), a.getValue().ate())).limit(20).map(e -> {
					Map<String, Object> p = new LinkedHashMap<>();
					p.put("rota", e.getKey());
					p.put("restam_min", (e.getValue().ate() - agora) / 60_000);
					p.put("reincidencias", e.getValue().reincidencias());
					p.put("motivo", e.getValue().motivo());
					return p;
				}).toList();
		r.put("rotas_penas_ativas_detalhe", rotasDetalhe);

		return r;
	}

	public Map<String, String> semEmprestimo() {
		return Map.copyOf(semEmprestimo);
	}

	public Map<String, Integer> taxasMedidas() {
		return Map.copyOf(taxas);
	}

	// Em árvore JSON, não por reflexão: o binário nativo não serializa `record` sem
	// metadata declarada.
	private synchronized void salvar() {

		if (arquivo == null) {
			return;
		}

		JsonObject raiz = new JsonObject();

		JsonObject objetoPenas = new JsonObject();
		penas.forEach((pool, pena) -> {
			JsonObject p = new JsonObject();
			p.addProperty("ate", pena.ate());
			p.addProperty("motivo", pena.motivo());
			p.addProperty("reincidencias", pena.reincidencias());
			objetoPenas.add(pool, p);
		});
		raiz.add("penas", objetoPenas);

		JsonObject objetoRotas = new JsonObject();
		rotas.forEach((rota, pena) -> {
			JsonObject p = new JsonObject();
			p.addProperty("ate", pena.ate());
			p.addProperty("motivo", pena.motivo());
			p.addProperty("reincidencias", pena.reincidencias());
			objetoRotas.add(rota, p);
		});
		raiz.add("rotas", objetoRotas);

		JsonObject objetoSemEmprestimo = new JsonObject();
		semEmprestimo.forEach(objetoSemEmprestimo::addProperty);
		raiz.add("sem_emprestimo", objetoSemEmprestimo);

		JsonObject objetoTaxas = new JsonObject();
		taxas.forEach(objetoTaxas::addProperty);
		raiz.add("taxas_medidas", objetoTaxas);

		try {
			Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
			Files.writeString(temporario, gson.toJson(raiz), StandardCharsets.UTF_8);
			Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOG.warning(() -> "quarentena: não gravou " + arquivo + ": " + e.getMessage());
		}
	}

	private void carregar() {

		if (arquivo == null || !Files.exists(arquivo)) {
			return;
		}

		try {
			JsonObject raiz = JsonParser.parseString(Files.readString(arquivo, StandardCharsets.UTF_8))
					.getAsJsonObject();

			if (raiz.has("penas")) {
				raiz.getAsJsonObject("penas").entrySet().forEach(e -> {
					JsonObject p = e.getValue().getAsJsonObject();
					penas.put(e.getKey(), new Pena(p.get("ate").getAsLong(), p.get("motivo").getAsString(),
							p.get("reincidencias").getAsInt()));
				});
			}
			if (raiz.has("rotas")) {
				raiz.getAsJsonObject("rotas").entrySet().forEach(e -> {
					JsonObject p = e.getValue().getAsJsonObject();
					rotas.put(e.getKey(), new Pena(p.get("ate").getAsLong(), p.get("motivo").getAsString(),
							p.get("reincidencias").getAsInt()));
				});
			}
			if (raiz.has("sem_emprestimo")) {
				// Descartadas ao carregar: eram todas proibições por callback desconhecido, e o
				// Executor
				// implantado em 24/09 atende qualquer callback pelo `fallback`.
				int descartadas = raiz.getAsJsonObject("sem_emprestimo").size();
				if (descartadas > 0) {
					LOG.info(() -> "quarentena: " + descartadas
							+ " proibições de emprestar descartadas (Executor com fallback)");
				}
			}
			if (raiz.has("taxas_medidas")) {
				raiz.getAsJsonObject("taxas_medidas").entrySet()
						.forEach(e -> taxas.put(e.getKey(), e.getValue().getAsInt()));
			}

			LOG.info(() -> "quarentena: " + penas.size() + " penas, " + rotas.size() + " rotas, " + semEmprestimo.size()
					+ " proibidas de emprestar e " + taxas.size() + " taxas medidas carregadas de " + arquivo);

		} catch (IOException | RuntimeException e) {
			LOG.warning(() -> "quarentena: " + arquivo + " ilegível, começo vazio: " + e.getMessage());
		}
	}
}
