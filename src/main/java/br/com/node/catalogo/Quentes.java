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

// O CONJUNTO QUENTE: as pools com histórico de arbitragem, as únicas que o garimpo RÁPIDO olha a cada bloco.
//
// Medido em 27/09 com as corridas do dia contra a noite: o grafo inteiro tinha 13.799 pools e a busca levava
// 158 ms (mediana) — mas as pools com 3 ou mais arbitragens no histórico eram ~4,2 mil e cobriam 57 das 75
// fendas reais que despachamos na noite. O resto não fica perdido: o organizador (`Cauda`, thread fria) varre o
// grafo inteiro sem prazo e PROMOVE para cá toda pool de ciclo com lucro — e toda pool que aparece em
// arbitragem confirmada pela LIMIAR-ésima vez. Guardado em `quentes.json` (lido na subida, gravado de tempos em
// tempos), para o reinício não recomeçar frio.
public final class Quentes {

	private static final Logger LOG = Logger.getLogger(Quentes.class.getName());

	// Na terceira arbitragem confirmada a pool vira quente (medido em 27/09: ≥2 cobria 59 de 75 fendas reais com
	// 5,7 mil pools; ≥3, 57 com 4,2 mil — 27% menos pools por 2 fendas).
	static final int LIMIAR = 3;

	// Quente que passa ESQUECIMENTO sem aparecer em arbitragem nem ser promovida pelo organizador esfria: sem isso
	// o conjunto só cresceria até virar o grafo inteiro (o histórico de um dia já dava 7,7 mil pools com ≥2).
	static final long ESQUECIMENTO_MS = 24 * 3_600_000L;

	private final Path arquivo;
	private final Map<String, Integer> vistas = new ConcurrentHashMap<>();
	private final Set<String> quentes = ConcurrentHashMap.newKeySet();
	private final Queue<String> novas = new ConcurrentLinkedQueue<>();
	private final Map<String, Long> ultimaVez = new ConcurrentHashMap<>();
	private final AtomicLong esfriadas = new AtomicLong();

	private final AtomicLong porArbitragem = new AtomicLong();
	private final AtomicLong peloOrganizador = new AtomicLong();

	// `arquivo` nulo: só em memória (testes).
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

	// Pools de um ciclo com lucro que o organizador achou fora do quente: entram direto.
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

	// As pools de uma arbitragem confirmada (nossa ou de rival): entram na LIMIAR-ésima vez.
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

	// As promovidas desde a última chamada — o grafo quente as liga sem se remontar inteiro.
	public List<String> drenarNovas() {
		List<String> lista = new ArrayList<>();
		for (String pool; (pool = novas.poll()) != null;) {
			lista.add(pool);
		}
		return lista;
	}

	private void carregar() {

		if (!Files.exists(arquivo)) {
			LOG.info(() -> "quentes: " + arquivo + " não existe — começa vazio e aquece pelo organizador e pelas arbitragens");
			return;
		}

		try {
			JsonObject raiz = JsonParser.parseString(Files.readString(arquivo, StandardCharsets.UTF_8)).getAsJsonObject();
			raiz.getAsJsonArray("quentes").forEach(p -> quentes.add(p.getAsString()));
			raiz.getAsJsonObject("vistas").entrySet().forEach(e -> vistas.put(e.getKey(), e.getValue().getAsInt()));
			raiz.getAsJsonObject("ultima_vez").entrySet().forEach(e -> ultimaVez.put(e.getKey(), e.getValue().getAsLong()));
			LOG.info(() -> "quentes: " + quentes.size() + " pools quentes e " + vistas.size() + " contagens carregadas de "
					+ arquivo);
		} catch (IOException | RuntimeException e) {
			// Estado perdido não vira "começa do zero" calado: a subida tem de saber.
			throw new IllegalStateException("quentes: " + arquivo + " ilegível — apague-o de propósito para recomeçar", e);
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

	// Tira do quente (e esquece a contagem) quem não aparece há ESQUECIMENTO_MS. Chamado ao salvar.
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
