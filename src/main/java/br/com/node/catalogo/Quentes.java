package br.com.node.catalogo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class Quentes {

	private static final Logger LOG = Logger.getLogger(Quentes.class.getName());

	static final int LIMIAR = 3;

	static final long ESQUECIMENTO_MS = 24 * 3_600_000L;

	private final Path arquivo;
	private final Map<String, Integer> vistas = new ConcurrentHashMap<>();
	private final Set<String> quentes = ConcurrentHashMap.newKeySet();
	private final Queue<String> novas = new ConcurrentLinkedQueue<>();
	private final Map<String, Long> ultimaVez = new ConcurrentHashMap<>();
	private final AtomicLong esfriadas = new AtomicLong();

	private final AtomicLong porArbitragem = new AtomicLong();
	private final AtomicLong peloOrganizador = new AtomicLong();

	public Quentes(Path arquivo) {
		this.arquivo = arquivo;
		if (arquivo != null) {
			carregar();
		}
	}

	public boolean contem(String pool) {
		return quentes.contains(pool);
	}

	public int tamanho() {
		return quentes.size();
	}

	public int promover(Collection<String> pools) {
		int entraram = 0;
		long agora = System.currentTimeMillis();
		for (String pool : pools) {
			ultimaVez.put(pool, agora);
			if (quentes.add(pool)) {
				novas.add(pool);
				entraram++;
			}
		}
		peloOrganizador.addAndGet(entraram);
		return entraram;
	}

	public void vistaEmArbitragem(Collection<String> pools) {
		long agora = System.currentTimeMillis();
		for (String pool : pools) {
			ultimaVez.put(pool, agora);
			if (vistas.merge(pool, 1, Integer::sum) >= LIMIAR && quentes.add(pool)) {
				novas.add(pool);
				porArbitragem.incrementAndGet();
			}
		}
	}

	public List<String> drenarNovas() {
		List<String> lista = new ArrayList<>();
		for (String pool; (pool = novas.poll()) != null;) {
			lista.add(pool);
		}
		return lista;
	}

	private void carregar() {

		if (!Files.exists(arquivo)) {
			LOG.info(() -> "quentes: " + arquivo
					+ " não existe — começa vazio e aquece pelo organizador e pelas arbitragens");
			return;
		}

		try {
			JsonObject raiz = JsonParser.parseString(Files.readString(arquivo, StandardCharsets.UTF_8))
					.getAsJsonObject();
			raiz.getAsJsonArray("quentes").forEach(p -> quentes.add(p.getAsString()));
			raiz.getAsJsonObject("vistas").entrySet().forEach(e -> vistas.put(e.getKey(), e.getValue().getAsInt()));
			raiz.getAsJsonObject("ultima_vez").entrySet()
					.forEach(e -> ultimaVez.put(e.getKey(), e.getValue().getAsLong()));
			LOG.info(() -> "quentes: " + quentes.size() + " pools quentes e " + vistas.size()
					+ " contagens carregadas de " + arquivo);
		} catch (IOException | RuntimeException e) {

			throw new IllegalStateException("quentes: " + arquivo + " ilegível — apague-o de propósito para recomeçar",
					e);
		}
	}

	public synchronized void salvar() {

		esfriar(System.currentTimeMillis());

		if (arquivo == null) {
			return;
		}

		JsonObject raiz = new JsonObject();
		JsonArray lista = new JsonArray();
		quentes.forEach(lista::add);
		raiz.add("quentes", lista);
		JsonObject contagens = new JsonObject();
		vistas.forEach(contagens::addProperty);
		raiz.add("vistas", contagens);
		JsonObject ultimas = new JsonObject();
		ultimaVez.forEach(ultimas::addProperty);
		raiz.add("ultima_vez", ultimas);

		Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
		try {
			Files.writeString(temporario, new GsonBuilder().create().toJson(raiz), StandardCharsets.UTF_8);
			Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			br.com.node.util.Falhas.anotar("quentes: gravar " + arquivo, e);
		}
	}

	void esfriar(long agora) {
		for (Map.Entry<String, Long> e : ultimaVez.entrySet()) {
			if (agora - e.getValue() > ESQUECIMENTO_MS) {
				ultimaVez.remove(e.getKey());
				vistas.remove(e.getKey());
				if (quentes.remove(e.getKey())) {
					esfriadas.incrementAndGet();
				}
			}
		}
	}

	public Map<String, Object> resumo() {
		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("pools_quentes", quentes.size());
		resumo.put("promovidas_por_arbitragem", porArbitragem.get());
		resumo.put("promovidas_pelo_organizador", peloOrganizador.get());
		resumo.put("contagens", vistas.size());
		resumo.put("esfriadas", esfriadas.get());
		return resumo;
	}
}