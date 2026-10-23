package br.com.node.execucao.disparo;

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

import br.com.node.transformacao.nucleo.Arbitragem;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.util.Amostras;

public final class Desfechos implements Closeable {

	private static final int ESPERA = 8;

	public record Envio(String hash, long altura, Set<String> pools, long chegadaMs, String caminho, String rota,
			int fracaoMilha, BigInteger ofertadoWeiPorGas, BigInteger lucroBnb, BigInteger gasParaOLance,
			Map<String, Object> detalhes) {

		public Envio(String hash, long altura, Set<String> pools, long chegadaMs, String caminho, String rota,
				int fracaoMilha, BigInteger ofertadoWeiPorGas, BigInteger lucroBnb, BigInteger gasParaOLance) {
			this(hash, altura, pools, chegadaMs, caminho, rota, fracaoMilha, ofertadoWeiPorGas, lucroBnb, gasParaOLance,
					Map.of());
		}
	}

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

	private final Map<String, String> vereditos = new ConcurrentHashMap<>();

	private static final int BLOCOS_COM_PRECO = 32;
	private final Map<Long, List<BigInteger>> precosPorAltura = java.util.Collections
			.synchronizedMap(new java.util.LinkedHashMap<>(BLOCOS_COM_PRECO, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, List<BigInteger>> maisVelho) {
					return size() > BLOCOS_COM_PRECO;
				}
			});
	private final Amostras topoMwei = new Amostras(5_000);
	private final Amostras naFrenteMwei = new Amostras(5_000);

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
		precosPorAltura.put(altura,
				List.copyOf(precos.stream().map(p -> p == null ? BigInteger.valueOf(-1) : p).toList()));
	}

	private BigInteger precoEm(long altura, int posicao) {
		List<BigInteger> precos = precosPorAltura.get(altura);
		if (precos == null || posicao < 0 || posicao >= precos.size() || precos.get(posicao).signum() < 0) {
			return null;
		}
		return precos.get(posicao);
	}

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
			(nossa ? incluidas : rivais)
					.merge(envio.hash(), visto,
							(antes, agora) -> agora.altura() < antes.altura()
									|| (agora.altura() == antes.altura() && agora.posicao() < antes.posicao()) ? agora
											: antes);
		}
	}

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
			boolean deGerente = br.com.node.transformacao.catalogo.Eventos.ehSwapDeGerente(log)
					|| br.com.node.transformacao.catalogo.Eventos.ehLiquidezDeGerente(log);
			tocadas.add(HexFormat.of().formatHex(deGerente ? log.topicos().get(1) : log.endereco()));
		}

		for (Envio envio : pendentes.values()) {
			long distancia = altura - envio.altura();
			if (distancia >= 1 && distancia <= 2 && tocadas.stream().anyMatch(envio.pools()::contains)) {
				mexidas.putIfAbsent(envio.hash(), new Visto(altura, posicao, "?"));
			}
		}
	}

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

			classe = "invalida";
			quem = rival != null ? rival : mexida;
		} else if (rival != null) {
			classe = rival.altura() == envio.altura() + 1 ? "rival_n1" : "rival_n2";
			quem = rival;
		} else if (mexida != null) {

			classe = mexida.altura() == envio.altura() + 1 ? "pool_mexida_n1" : "pool_mexida_n2";
			quem = mexida;
		} else {
			classe = "ninguem";
			quem = null;
		}

		Long folga = momentos.apply(envio.altura() + 1).map(m -> m.blocoMs() - envio.chegadaMs()).orElse(null);

		BigInteger naFrente = quem != null && !classe.equals("incluida") ? precoEm(quem.altura(), quem.posicao())
				: null;
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