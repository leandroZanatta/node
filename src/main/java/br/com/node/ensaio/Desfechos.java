package br.com.node.ensaio;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.google.gson.Gson;

import br.com.node.nucleo.Arbitragem;
import br.com.node.sincronizacao.Cabeca;
import br.com.node.util.Amostras;

// O DESFECHO de cada envio aceito pelo relay — e POR QUANTO TEMPO se ganhou ou perdeu.
//
// "Perdemos por tempo" não diz se estamos a 10 ms ou a 300 ms de competir, e sem esse número não se sabe se
// vale continuar. Aqui cada envio ganha, depois de três blocos:
//   - o DESFECHO: `incluida` (a nossa aparece como arbitragem do nosso Executor), `rival_n1`/`rival_n2` (outro
//     executor arbitrou as mesmas pools no bloco seguinte, ou no outro), ou `ninguem` (fenda fantasma, ou
//     seguiu aberta);
//   - a FOLGA: instante do bloco N+1 (relógio do validador, o mesmo da linha do tempo) MENOS a primeira
//     confirmação de aceitação do relay. Positiva: houve confirmação antes do bloco; negativa: depois.
//     A confirmação não é a hora exata em que o pacote chegou ao builder;
//   - a posição do rival, e quem ele é.
// Tudo com o que o node já vê — o fluxo de blocos e as arbitragens que o detector confirma. Nenhuma RPC.
public final class Desfechos implements Closeable {

	// O desfecho se decide depois de ESPERA blocos aplicados: o N+1 e o N+2 já passaram, e dá tempo de o veredito
	// chegar — ele espera o RPC ter o bloco N (até ~3 s) antes de simular.
	private static final int ESPERA = 8;

	// `fracaoMilha`: fração do lucro que o sistema visou pagar ao builder (por mille, 0–950).
	// `ofertado` em wei por gás: o que o builder vê (gás × 0,9 + pagamento direto / gasLimit).
	// `gasParaOLance`: gasLimit assinado × 6/5 — o que a 48club usa para ranquear o bundle.
	public record Envio(String hash, long altura, Set<String> pools, long chegadaMs, String caminho, String rota,
			int fracaoMilha, BigInteger ofertadoWeiPorGas, BigInteger lucroBnb, BigInteger gasParaOLance,
			Map<String, Object> detalhes) {

		public Envio(String hash, long altura, Set<String> pools, long chegadaMs, String caminho, String rota,
				int fracaoMilha, BigInteger ofertadoWeiPorGas, BigInteger lucroBnb, BigInteger gasParaOLance) {
			this(hash, altura, pools, chegadaMs, caminho, rota, fracaoMilha, ofertadoWeiPorGas, lucroBnb,
					gasParaOLance, Map.of());
		}
	}

	// `naFrente` e `topo` são preços de gás das transações observadas no bloco, não o lance total do bundle do builder.
	// O pagamento direto do rival não é observável aqui. Nulos quando os preços não chegaram pelo `NewBlock`.
	public record Desfecho(Envio envio, String classe, Long folgaMs, Integer posicao, String quem, BigInteger naFrente,
			BigInteger topo, String veredito) {
	}

	private record Visto(long altura, int posicao, String executor) {
	}

	private final byte[] nossoExecutor;
	private final LongFunction<Optional<Cabeca.Momentos>> momentos;
	private final Path arquivo;
	private final Gson gson = new Gson();
	private final BufferedWriter saida;

	private final Map<String, Envio> pendentes = new ConcurrentHashMap<>();
	private final Map<String, Visto> incluidas = new ConcurrentHashMap<>();
	private final Map<String, Visto> rivais = new ConcurrentHashMap<>();
	private final Map<String, Visto> mexidas = new ConcurrentHashMap<>();
	// O veredito do contrato para o envio da cauda (eth_call logo depois do envio): "ok" fecha; o resto reverteria.
	private final Map<String, String> vereditos = new ConcurrentHashMap<>();

	// Os preços por gás por transação, na ordem do bloco — vindos do `NewBlock` (mempool).
	private static final int BLOCOS_COM_PRECO = 32;
	private final Map<Long, List<BigInteger>> precosPorAltura = java.util.Collections.synchronizedMap(
			new java.util.LinkedHashMap<>(BLOCOS_COM_PRECO, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, List<BigInteger>> maisVelho) {
					return size() > BLOCOS_COM_PRECO;
				}
			});
	private final Amostras topoMwei = new Amostras(5_000);
	private final Amostras naFrenteMwei = new Amostras(5_000);

	// A posição da tx no bloco, contada como o `ObservatorioArbitragem` conta: uma chamada por tx, na ordem.
	private long alturaCorrente = -1;
	private int indice;

	private volatile Consumer<Desfecho> aoDecidir = desfecho -> {
	};

	private final Map<String, Amostras> folgaPorClasse = new ConcurrentHashMap<>();
	private final Map<String, Long> porClasse = new ConcurrentHashMap<>();
	private final Map<Integer, Long> posicaoDoRivalN1 = new ConcurrentHashMap<>();

	public Desfechos(byte[] nossoExecutor, LongFunction<Optional<Cabeca.Momentos>> momentos, Path arquivo) {
		this.nossoExecutor = nossoExecutor.clone();
		this.momentos = momentos;
		this.arquivo = arquivo;
		if (arquivo == null) {
			this.saida = null;
			return;
		}
		try {
			Files.createDirectories(arquivo.toAbsolutePath().getParent());
			this.saida = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException("desfechos: não abri " + arquivo, e);
		}
	}

	public void aoDecidir(Consumer<Desfecho> ouvinte) {
		this.aoDecidir = ouvinte;
	}

	public void precos(long altura, List<BigInteger> precos) {
		precosPorAltura.put(altura, List.copyOf(precos.stream().map(p -> p == null ? BigInteger.valueOf(-1) : p).toList()));
	}

	private BigInteger precoEm(long altura, int posicao) {
		List<BigInteger> precos = precosPorAltura.get(altura);
		if (precos == null || posicao < 0 || posicao >= precos.size() || precos.get(posicao).signum() < 0) {
			return null;
		}
		return precos.get(posicao);
	}

	// A mediana dos preços de gás das transações nas posições 1 a 5. Não representa o lance do bundle.
	private BigInteger topoDe(long altura) {
		List<BigInteger> precos = precosPorAltura.get(altura);
		if (precos == null) {
			return null;
		}
		List<BigInteger> topo = precos.stream().skip(1).limit(5).filter(p -> p.signum() > 0).sorted().toList();
		return topo.isEmpty() ? null : topo.get(topo.size() / 2);
	}

	public void veredito(String hash, String veredito) {
		vereditos.put(hash, veredito);
	}

	public void registrar(Envio envio) {
		pendentes.put(envio.hash(), envio);
	}

	// Toda arbitragem que o detector confirma. Casa com um envio pendente pelas POOLS e pela altura: a nossa
	// (executor = o nosso contrato) é inclusão; a de outro, no N+1 ou N+2, é o rival que levou.
	public void arbitragem(Arbitragem arbitragem, long altura, int posicao) {

		if (pendentes.isEmpty() || arbitragem.executor() == null) {
			return;
		}

		boolean nossa = java.util.Arrays.equals(arbitragem.executor(), nossoExecutor);
		List<String> pools = arbitragem.pools().stream().map(p -> HexFormat.of().formatHex(p)).toList();

		for (Envio envio : pendentes.values()) {

			long distancia = altura - envio.altura();

			if (distancia < 1 || distancia > 2 || pools.stream().noneMatch(envio.pools()::contains)) {
				continue;
			}

			Visto visto = new Visto(altura, posicao, HexFormat.of().formatHex(arbitragem.executor()));
			(nossa ? incluidas : rivais).merge(envio.hash(), visto,
					(antes, agora) -> agora.altura() < antes.altura()
							|| (agora.altura() == antes.altura() && agora.posicao() < antes.posicao()) ? agora : antes);
		}
	}

	// TODA tx aplicada, na ordem do bloco: a primeira que TOCA uma pool de um envio no N+1 ou N+2 fica anotada,
	// seja arbitragem ou não. CICATRIZ de 27/09: 28 de 74 fendas reais saíam "ninguém levou" — e em todas as
	// pools tinham sido mexidas no N+1, nas posições 1 e 2, por tx que o detector não lê como arbitragem.
	// Chamado pela thread do cursor, a mesma que chama `arbitragem` e `blocoFechado`.
	public void transacao(List<br.com.node.vo.Log> logs, long altura) {

		if (altura != alturaCorrente) {
			alturaCorrente = altura;
			indice = 0;
		}
		int posicao = indice++;

		if (pendentes.isEmpty() || logs.isEmpty()) {
			return;
		}

		java.util.Set<String> tocadas = new java.util.HashSet<>();
		for (br.com.node.vo.Log log : logs) {
			boolean deGerente = br.com.node.catalogo.Eventos.ehSwapDeGerente(log)
					|| br.com.node.catalogo.Eventos.ehLiquidezDeGerente(log);
			tocadas.add(HexFormat.of().formatHex(deGerente ? log.topicos().get(1) : log.endereco()));
		}

		for (Envio envio : pendentes.values()) {
			long distancia = altura - envio.altura();
			if (distancia >= 1 && distancia <= 2 && tocadas.stream().anyMatch(envio.pools()::contains)) {
				mexidas.putIfAbsent(envio.hash(), new Visto(altura, posicao, "?"));
			}
		}
	}

	// Um bloco aplicado: decide os envios que já têm ESPERA blocos atrás de si.
	public void blocoFechado(long altura) {

		for (Envio envio : List.copyOf(pendentes.values())) {
			if (altura - envio.altura() >= ESPERA && pendentes.remove(envio.hash()) != null) {
				decidir(envio);
			}
		}
	}

	private void decidir(Envio envio) {

		Visto nossa = incluidas.remove(envio.hash());
		Visto rival = rivais.remove(envio.hash());
		Visto mexida = mexidas.remove(envio.hash());
		String veredito = vereditos.remove(envio.hash());

		String classe;
		Visto quem;

		if (nossa != null) {
			classe = "incluida";
			quem = nossa;
		} else if (veredito != null && !veredito.equals("ok") && !veredito.equals("sem_veredito")) {
			// A nossa reverteria: o builder a descarta, e nem tempo nem lance explicam. Não ensina o lance.
			classe = "invalida";
			quem = rival != null ? rival : mexida;
		} else if (rival != null) {
			classe = rival.altura() == envio.altura() + 1 ? "rival_n1" : "rival_n2";
			quem = rival;
		} else if (mexida != null) {
			// Alguém tocou as pools antes de nós, e o detector não reconhece como arbitragem.
			classe = mexida.altura() == envio.altura() + 1 ? "pool_mexida_n1" : "pool_mexida_n2";
			quem = mexida;
		} else {
			classe = "ninguem";
			quem = null;
		}

		// Sem o instante do N+1 não há folga — e isso fica dito no registro, não vira zero.
		Long folga = momentos.apply(envio.altura() + 1).map(m -> m.blocoMs() - envio.chegadaMs()).orElse(null);

		BigInteger naFrente = quem != null && !classe.equals("incluida") ? precoEm(quem.altura(), quem.posicao()) : null;
		BigInteger topo = topoDe(envio.altura() + 1);

		Desfecho desfecho = new Desfecho(envio, classe, folga, quem != null ? quem.posicao() : null,
				quem != null ? quem.executor() : null, naFrente, topo, veredito);

		if (topo != null) {
			topoMwei.anotar(topo.divide(MWEI).longValue());
		}
		if (naFrente != null) {
			naFrenteMwei.anotar(naFrente.divide(MWEI).longValue());
		}
		porClasse.merge(classe, 1L, Long::sum);
		if (folga != null) {
			folgaPorClasse.computeIfAbsent(classe, k -> new Amostras(5_000)).anotar(folga);
		}
		if ((classe.equals("rival_n1") || classe.equals("pool_mexida_n1")) && quem != null) {
			posicaoDoRivalN1.merge(quem.posicao(), 1L, Long::sum);
		}

		gravar(desfecho);

		aoDecidir.accept(desfecho);
	}

	private void gravar(Desfecho desfecho) {

		if (saida == null) {
			return;
		}
		Map<String, Object> registro = new LinkedHashMap<>();
		Envio envio = desfecho.envio();
		registro.put("hash", envio.hash());
		registro.put("altura", envio.altura());
		registro.put("caminho", envio.caminho());
		registro.put("classe", desfecho.classe());
		registro.put("folga_ms", desfecho.folgaMs());
		registro.put("posicao", desfecho.posicao());
		registro.put("quem", desfecho.quem());
		registro.put("chegada_ms", envio.chegadaMs());
		registro.put("fracao_milha", envio.fracaoMilha());
		registro.put("preco_gas_tx_na_frente_wei_por_gas",
				desfecho.naFrente() == null ? null : desfecho.naFrente().toString());
		registro.put("mediana_gas_tx_top5_n1_wei_por_gas", desfecho.topo() == null ? null : desfecho.topo().toString());
		registro.put("lucro_bnb", envio.lucroBnb().toString());
		registro.put("rota", envio.rota());
		registro.put("veredito", desfecho.veredito());

		synchronized (saida) {
			try {
				saida.write(gson.toJson(registro));
				saida.newLine();
				saida.flush();
			} catch (IOException e) {
				throw new IllegalStateException("desfechos: não gravei em " + arquivo, e);
			}
		}
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("pendentes", pendentes.size());
		resumo.put("por_classe", new java.util.TreeMap<>(porClasse));
		Map<String, Object> folgas = new java.util.TreeMap<>();
		folgaPorClasse.forEach((classe, amostras) -> folgas.put(classe, amostras.resumo()));
		resumo.put("folga_ms_por_classe", folgas);
		resumo.put("posicao_do_rival_n1", new java.util.TreeMap<>(posicaoDoRivalN1));
		resumo.put("preco_gas_tx_na_frente_mwei_por_gas", naFrenteMwei.resumo());
		resumo.put("mediana_gas_tx_top5_n1_mwei_por_gas", topoMwei.resumo());
		resumo.put("arquivo", arquivo == null ? "integrado ao diario de ensaio" : arquivo.toString());
		return resumo;
	}

	private static final BigInteger MWEI = BigInteger.valueOf(1_000_000);

	@Override
	public void close() {
		if (saida == null) {
			return;
		}
		synchronized (saida) {
			try {
				saida.close();
			} catch (IOException e) {
				br.com.node.util.Falhas.anotar("desfechos: fechar " + arquivo, e);
			}
		}
	}
}
