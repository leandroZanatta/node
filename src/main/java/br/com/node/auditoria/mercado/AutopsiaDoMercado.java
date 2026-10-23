package br.com.node.auditoria.mercado;
import br.com.node.execucao.disparo.CustoDeGas;
import br.com.node.execucao.disparo.Gabarito;

import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.execucao.bundle.Salto;
import br.com.node.execucao.bundle.RotaDoModelo;


import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.gson.Gson;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Cotador;
import br.com.node.transformacao.catalogo.Eventos;
import br.com.node.transformacao.catalogo.Tarifario;
import br.com.node.transformacao.nucleo.Arbitragem;
import br.com.node.transformacao.nucleo.Avaliador;
import br.com.node.transformacao.nucleo.Ciclo;
import br.com.node.transformacao.nucleo.EstadoV3;
import br.com.node.transformacao.nucleo.Oportunidade;
import br.com.node.transformacao.nucleo.Perna;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.transformacao.nucleo.PoolV2;
import br.com.node.transformacao.nucleo.PoolV3;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.transformacao.nucleo.Travessia;
import br.com.node.vo.Log;

public final class AutopsiaDoMercado implements Closeable {

	private static final Logger LOG = Logger.getLogger(AutopsiaDoMercado.class.getName());

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final int PERNAS_DA_BUSCA = 4;

	public record Foto(ReservasV2 v2, EstadoV3 v3, Integer taxaDoGerente) {
	}

	public record PernaObservada(byte[] pool, byte[] entra, byte[] sai, int ordem) {
	}

	private final Catalogo catalogo;
	private final Cotador cotador;
	private final Tarifario tarifario;
	private final CustoDeGas custoDeGas;
	private final Gabarito gabarito;
	private final Path arquivo;
	private final Avaliador avaliador = new Avaliador();
	private final Gson gson = new Gson();

	private static final int BUFFER_RECENTES = 500;
	private final java.util.ArrayDeque<Map<String, Object>> recentes = new java.util.ArrayDeque<>(BUFFER_RECENTES);

	private static final long ATRASO_MS = 1_500;
	private static final int TETO_DA_FILA = 2_000;
	private final java.util.concurrent.ScheduledThreadPoolExecutor fila = new java.util.concurrent.ScheduledThreadPoolExecutor(
			1, tarefa -> {
				Thread thread = new Thread(tarefa, "autopsia-do-mercado");
				thread.setDaemon(true);
				return thread;
			});

	private List<Log> logsDaTransacao = List.of();
	private Map<String, Foto> fotos = Map.of();

	public interface Saldos {
		Optional<BigInteger> bnb(byte[] conta, long bloco);

		Optional<BigInteger> token(byte[] token, byte[] conta, long bloco);
	}

	private static final BigInteger VERIFICAR_A_PARTIR_DE = BigInteger.valueOf(2_000_000_000_000_000L);
	private volatile Saldos saldos;

	private volatile int versaoDoExecutor = 1;

	public void versaoDoExecutor(int versao) {
		this.versaoDoExecutor = versao;
	}

	private final Map<String, BigInteger> valorVerificadoPorClasse = new LinkedHashMap<>();
	private final AtomicLong verificadas = new AtomicLong();

	public void verificarCom(Saldos saldos) {
		this.saldos = saldos;
	}

	private final Map<String, AtomicLong> porClasse = new LinkedHashMap<>();
	private final Map<String, Map<String, BigInteger>> valorPorClasse = new LinkedHashMap<>();
	private final Map<String, AtomicLong> motivosDaPoolFora = new LinkedHashMap<>();
	private final Map<String, AtomicLong> naoLinearPorque = new LinkedHashMap<>();
	private final AtomicLong examinadas = new AtomicLong();
	private BufferedWriter saida;

	public AutopsiaDoMercado(Catalogo catalogo, Cotador cotador, Tarifario tarifario, CustoDeGas custoDeGas,
			Gabarito gabarito, Path arquivo) {
		this.catalogo = catalogo;
		this.cotador = cotador;
		this.tarifario = tarifario;
		this.custoDeGas = custoDeGas;
		this.gabarito = gabarito;
		this.arquivo = arquivo;
	}

	public void antes(List<Log> logs, long altura) {

		Map<String, Foto> tiradas = new HashMap<>();

		for (Log log : logs) {

			boolean deGerente = Eventos.ehSwapDeGerente(log) || Eventos.ehLiquidezDeGerente(log);
			byte[] pool = deGerente ? log.topicos().get(1) : log.endereco();
			String chave = HexFormat.of().formatHex(pool);

			if (tiradas.containsKey(chave)) {
				continue;
			}

			Optional<ReservasV2> v2 = deGerente ? Optional.empty() : catalogo.reservas(pool);
			Optional<EstadoV3> v3 = v2.isPresent() ? Optional.empty() : catalogo.estado(pool);

			if (v2.isPresent() || v3.isPresent()) {
				tiradas.put(chave, new Foto(v2.orElse(null), v3.orElse(null),
						deGerente ? catalogo.taxaNoGerente(pool).orElse(null) : null));
			}
		}

		logsDaTransacao = logs;
		fotos = tiradas;
	}

	public void confirmar(Arbitragem arbitragem, long altura, int indice, int gatilho) {

		List<Log> logs = logsDaTransacao;
		Map<String, Foto> antes = fotos;

		if (fila.getQueue().size() > TETO_DA_FILA) {
			return;
		}

		fila.schedule(() -> {
			try {
				examinar(arbitragem, logs, antes, altura, indice, gatilho);
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("autópsia do mercado", e);
			}
		}, ATRASO_MS, TimeUnit.MILLISECONDS);
	}

	public void examinar(Arbitragem arbitragem, List<Log> logs, Map<String, Foto> antes, long altura, int indice,
			int gatilho) {

		examinadas.incrementAndGet();

		Map<String, Object> registro = new LinkedHashMap<>();
		registro.put("altura", altura);
		registro.put("posicao", indice);
		registro.put("gatilho", gatilho);
		registro.put("token", HexFormat.of().formatHex(arbitragem.token()));
		registro.put("sobra_do_rival", arbitragem.sobra().toString());

		List<PernaObservada> observadas = pernas(arbitragem, logs);

		List<String> descricao = new ArrayList<>();
		observadas.forEach(
				p -> descricao.add(hex(p.pool()) + ":" + (p.entra() == null ? "?" : hex(p.entra()).substring(0, 8))
						+ ">" + (p.sai() == null ? "?" : hex(p.sai()).substring(0, 8))));
		registro.put("pernas", descricao);

		List<List<PernaObservada>> ciclos = ciclos(observadas, arbitragem.token());

		if (ciclos.isEmpty()) {
			String porque = observadas.size() < 2 ? "menos de 2 trocas"
					: observadas.stream().anyMatch(p -> p.entra() == null) ? "perna sem tokens"
							: "sem ciclo simples no lucro";
			naoLinearPorque.computeIfAbsent(porque, k -> new AtomicLong()).incrementAndGet();
			registro.put("porque", porque);
			fechar(registro, "nao_linear", arbitragem);
			return;
		}

		int usadas = ciclos.stream().mapToInt(List::size).max().orElse(0);
		registro.put("rota_dividida", ciclos.size() > 1 || usadas < observadas.size());
		registro.put("ciclos_simples", ciclos.size());

		Resultado melhor = null;
		for (List<PernaObservada> ciclo : ciclos) {
			Resultado resultado = avaliar(ciclo, antes);
			if (melhor == null || resultado.melhorQue(melhor)) {
				melhor = resultado;
			}
		}

		registro.putAll(melhor.detalhes());

		if (melhor.oportunidade() == null) {
			if (melhor.classe().equals("pool_fora")) {
				motivosDaPoolFora.computeIfAbsent((String) melhor.detalhes().get("motivo"), k -> new AtomicLong())
						.incrementAndGet();
			}
			fechar(registro, melhor.classe(), arbitragem);
			return;
		}

		List<PernaObservada> pernas = melhor.pernas();
		Oportunidade oportunidade = melhor.oportunidade();

		Set<String> conjunto = new TreeSet<>();
		pernas.forEach(p -> conjunto.add(hex(p.pool())));

		if (gabarito.viu(conjunto)) {
			fechar(registro, "achamos", arbitragem);
			return;
		}

		if (gatilho >= 0) {
			fechar(registro, "aberta_no_mesmo_bloco", arbitragem);
			return;
		}

		byte[] raiz = pernas.get(0).entra();

		if (!Arrays.equals(raiz, WBNB) && !Arrays.equals(raiz, USDT)) {
			fechar(registro, "raiz_nao_numeraria", arbitragem);
			return;
		}

		if (pernas.size() > PERNAS_DA_BUSCA) {
			fechar(registro, "mais_de_4_pernas", arbitragem);
			return;
		}

		List<Perna> doCiclo = new ArrayList<>();
		pernas.forEach(p -> doCiclo.add(new Perna(p.pool(), p.entra(), p.sai())));
		BigInteger gas = custoDeGas.estimarGasDasTravessias(melhor.travessias(), doCiclo);
		Optional<BigInteger> piso = custoDeGas.piso(raiz, oportunidade.entrada(), gas);

		if (piso.isPresent()) {
			registro.put("piso", piso.get().toString());
		}

		if (piso.isEmpty() || oportunidade.lucro().compareTo(piso.get()) < 0) {
			fechar(registro, "abaixo_do_piso", arbitragem);
			return;
		}

		if (versaoDoExecutor < 3
				&& pernas.stream().noneMatch(p -> (Arrays.equals(p.entra(), WBNB) || Arrays.equals(p.entra(), USDT))
						&& catalogo.reservas(p.pool()).isPresent())) {
			fechar(registro, "sem_flash", arbitragem);
			return;
		}

		BigInteger lucroDoModelo = oportunidade.lucro();
		LOG.info(() -> "mercado: DEVERÍAMOS TER ACHADO — altura " + altura + " · " + pernas.size()
				+ " pernas · lucro do modelo " + lucroDoModelo + " (raiz " + hex(raiz).substring(0, 8) + ") · pools "
				+ conjunto);
		fechar(registro, "deveriamos_ter_achado", arbitragem);
	}

	public record Resultado(String classe, List<PernaObservada> pernas, List<Travessia> travessias, Oportunidade oportunidade,
			Map<String, Object> detalhes) {

		private static final List<String> ORDEM = List.of("pool_fora", "sem_estado_antes", "nao_cota",
				"modelo_sem_lucro", "lucro");

		boolean melhorQue(Resultado outro) {
			int a = ORDEM.indexOf(classe);
			int b = ORDEM.indexOf(outro.classe);
			if (a != b) {
				return a > b;
			}
			return oportunidade != null && outro.oportunidade != null
					&& oportunidade.lucro().compareTo(outro.oportunidade.lucro()) > 0;
		}
	}

	private Resultado avaliar(List<PernaObservada> pernas, Map<String, Foto> antes) {

		Map<String, Object> detalhes = new LinkedHashMap<>();
		detalhes.put("ciclo", pernas.stream().map(p -> hex(p.pool())).toList());

		for (PernaObservada perna : pernas) {
			String motivo = cotador.motivoDeFora(perna.pool());
			if (!motivo.equals("no grafo") && !motivo.startsWith("sem atividade")) {
				detalhes.put("pool_fora", hex(perna.pool()));
				detalhes.put("motivo", motivo);
				return new Resultado("pool_fora", pernas, null, null, detalhes);
			}
		}

		Map<String, Pool> overlay = new HashMap<>();

		for (PernaObservada perna : pernas) {
			Optional<Pool> pool = poolAntes(perna.pool(), antes.get(hex(perna.pool())));
			if (pool.isEmpty()) {
				detalhes.put("pool_sem_estado", hex(perna.pool()));
				return new Resultado("sem_estado_antes", pernas, null, null, detalhes);
			}
			overlay.put(hex(perna.pool()), pool.get());
		}

		List<Perna> doCiclo = new ArrayList<>();
		pernas.forEach(p -> doCiclo.add(new Perna(p.pool(), p.entra(), p.sai())));

		Optional<List<Travessia>> travessias = cotador.travessias(new Ciclo(doCiclo), overlay);

		if (travessias.isEmpty()) {
			detalhes.put("motivo_por_perna",
					pernas.stream().map(p -> hex(p.pool()) + "=" + cotador.motivoDeFora(p.pool())).toList());
			return new Resultado("nao_cota", pernas, null, null, detalhes);
		}

		Optional<Oportunidade> oportunidade;
		try {
			oportunidade = avaliador.melhor(travessias.get());
		} catch (RuntimeException e) {
			oportunidade = Optional.empty();
		}

		if (oportunidade.isEmpty() || oportunidade.get().lucro().signum() <= 0) {
			return new Resultado("modelo_sem_lucro", pernas, travessias.get(), null, detalhes);
		}

		detalhes.put("lucro_do_modelo", oportunidade.get().lucro().toString());
		detalhes.put("entrada_do_modelo", oportunidade.get().entrada().toString());

		return new Resultado("lucro", pernas, travessias.get(), oportunidade.get(), detalhes);
	}

	public static List<List<PernaObservada>> ciclos(List<PernaObservada> pernas, byte[] tokenDoLucro) {

		List<List<PernaObservada>> achados = new ArrayList<>();

		if (pernas.stream().anyMatch(p -> p.entra() == null)) {
			return achados;
		}

		descer(pernas, tokenDoLucro, tokenDoLucro, new ArrayList<>(), achados);

		return achados;
	}

	private static void descer(List<PernaObservada> pernas, byte[] inicio, byte[] atual, List<PernaObservada> caminho,
			List<List<PernaObservada>> achados) {

		if (caminho.size() >= 5 || achados.size() >= 16) {
			return;
		}

		for (PernaObservada perna : pernas) {

			if (!Arrays.equals(perna.entra(), atual)
					|| caminho.stream().anyMatch(p -> Arrays.equals(p.pool(), perna.pool()))) {
				continue;
			}

			caminho.add(perna);

			if (Arrays.equals(perna.sai(), inicio) && caminho.size() >= 2) {
				achados.add(List.copyOf(caminho));
			} else {
				descer(pernas, inicio, perna.sai(), caminho, achados);
			}

			caminho.remove(caminho.size() - 1);
		}
	}

	public List<PernaObservada> pernas(Arbitragem arbitragem, List<Log> logs) {

		List<PernaObservada> pernas = new ArrayList<>();
		Set<String> vistas = new java.util.HashSet<>();

		for (byte[] pool : arbitragem.pools()) {

			if (!vistas.add(hex(pool))) {
				continue;
			}

			if (Catalogo.deGerente(pool)) {
				doGerente(pool, logs).ifPresent(pernas::add);
				continue;
			}

			byte[] entra = null;
			byte[] sai = null;
			boolean ambiguo = false;
			int ordem = Integer.MAX_VALUE;

			for (int i = 0; i < logs.size(); i++) {

				Log log = logs.get(i);

				if (!log.assinadoPor(Eventos.TRANSFER) || log.topicos().size() != 3) {
					continue;
				}

				byte[] de = Eventos.enderecoDoTopico(log, 1);
				byte[] para = Eventos.enderecoDoTopico(log, 2);

				if (Arrays.equals(para, pool)) {
					ambiguo |= entra != null && !Arrays.equals(entra, log.endereco());
					entra = entra == null ? log.endereco() : entra;
					ordem = Math.min(ordem, i);
				} else if (Arrays.equals(de, pool)) {
					ambiguo |= sai != null && !Arrays.equals(sai, log.endereco());
					sai = sai == null ? log.endereco() : sai;
					ordem = Math.min(ordem, i);
				}
			}

			if (entra != null && sai != null && !ambiguo && !Arrays.equals(entra, sai)) {
				pernas.add(new PernaObservada(pool, entra, sai, ordem));
			}
		}

		pernas.sort((a, b) -> Integer.compare(a.ordem(), b.ordem()));

		return pernas;
	}

	private Optional<PernaObservada> doGerente(byte[] poolId, List<Log> logs) {

		for (int i = 0; i < logs.size(); i++) {

			Log log = logs.get(i);

			if (!Eventos.ehSwapDeGerente(log) || !Arrays.equals(log.topicos().get(1), poolId)) {
				continue;
			}

			Optional<Catalogo.Lados> lados = catalogo.lados(poolId);

			if (lados.isEmpty()) {
				return Optional.of(new PernaObservada(poolId, null, null, i));
			}

			boolean entraZero = Eventos.palavraComSinalGrande(log, 0).signum() < 0;
			byte[] entra = entraZero ? lados.get().token0() : lados.get().token1();
			byte[] sai = entraZero ? lados.get().token1() : lados.get().token0();

			return Optional.of(new PernaObservada(poolId, entra, sai, i));
		}

		return Optional.empty();
	}

	public static Optional<List<PernaObservada>> emCiclo(List<PernaObservada> pernas, byte[] tokenDoLucro) {

		if (pernas.size() < 2 || pernas.stream().anyMatch(p -> p.entra() == null)) {
			return Optional.empty();
		}

		int n = pernas.size();

		for (int i = 0; i < n; i++) {
			if (!Arrays.equals(pernas.get(i).sai(), pernas.get((i + 1) % n).entra())) {
				return Optional.empty();
			}
		}

		int inicio = 0;
		for (int i = 0; i < n; i++) {
			if (Arrays.equals(pernas.get(i).entra(), tokenDoLucro)) {
				inicio = i;
				break;
			}
		}

		List<PernaObservada> girado = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			girado.add(pernas.get((inicio + i) % n));
		}

		return Optional.of(girado);
	}

	private Optional<Pool> poolAntes(byte[] pool, Foto foto) {

		if (foto == null) {
			return Optional.empty();
		}

		if (foto.v2() != null) {
			return tarifario.taxa(pool).map(taxa -> new PoolV2(foto.v2().reserva0(), foto.v2().reserva1(), taxa));
		}

		Optional<Integer> taxa = foto.taxaDoGerente() != null ? Optional.of(foto.taxaDoGerente())
				: tarifario.taxa(pool);
		OptionalInt espacamento = tarifario.espacamento(pool);

		if (taxa.isEmpty() || espacamento.isEmpty() || foto.v3().liquidez().signum() <= 0) {
			return Optional.empty();
		}

		return Optional.of(cotador.comTicks(pool, new PoolV3(foto.v3().sqrtPrecoX96(), foto.v3().liquidez(),
				foto.v3().tick(), taxa.get(), espacamento.getAsInt())));
	}

	private synchronized void fechar(Map<String, Object> registro, String classe, Arbitragem arbitragem) {

		registro.put("classe", classe);
		verificar(registro, classe, arbitragem);
		porClasse.computeIfAbsent(classe, k -> new AtomicLong()).incrementAndGet();
		if (recentes.size() >= BUFFER_RECENTES)
			recentes.pollFirst();
		recentes.addLast(Map.copyOf(registro));
		valorPorClasse.computeIfAbsent(classe, k -> new LinkedHashMap<>()).merge(hex(arbitragem.token()),
				arbitragem.sobra(), BigInteger::add);

		try {

			if (saida == null) {
				Files.createDirectories(arquivo.getParent());
				saida = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			}

			saida.write(gson.toJson(registro));
			saida.newLine();
			saida.flush();

		} catch (IOException e) {
			br.com.node.util.Falhas.anotar("autópsia do mercado não gravou", e);
		}
	}

	public synchronized List<Map<String, Object>> recentes(int n, String classeFilter) {
		return recentes.stream().filter(e -> classeFilter == null || classeFilter.equals(e.get("classe")))
				.skip(Math.max(0, recentes.size() - n)).toList();
	}

	private void verificar(Map<String, Object> registro, String classe, Arbitragem arbitragem) {

		Saldos leitor = saldos;
		Object altura = registro.get("altura");

		if (leitor == null || arbitragem.executor() == null || !(altura instanceof Long bloco)) {
			return;
		}

		boolean wbnb = Arrays.equals(arbitragem.token(), WBNB);
		boolean usdt = Arrays.equals(arbitragem.token(), USDT);
		if (!wbnb && !usdt) {
			return;
		}

		BigInteger emBnb = emBnb(arbitragem.token(), arbitragem.sobra());

		if (emBnb.compareTo(VERIFICAR_A_PARTIR_DE) < 0) {
			return;
		}

		byte[] conta = arbitragem.executor();
		Optional<BigInteger> depois = leitor.token(arbitragem.token(), conta, bloco);
		Optional<BigInteger> antes = leitor.token(arbitragem.token(), conta, bloco - 1);

		if (depois.isEmpty() || antes.isEmpty()) {
			return;
		}

		BigInteger verificada = depois.get().subtract(antes.get());

		if (wbnb) {
			Optional<BigInteger> nativoDepois = leitor.bnb(conta, bloco);
			Optional<BigInteger> nativoAntes = leitor.bnb(conta, bloco - 1);
			if (nativoDepois.isEmpty() || nativoAntes.isEmpty()) {
				return;
			}
			verificada = verificada.add(nativoDepois.get().subtract(nativoAntes.get()));
		}

		registro.put("executor", hex(conta));

		registro.put("sobra_verificada", verificada.toString());
		registro.put("sobra_verificada_token", hex(arbitragem.token()));
		verificadas.incrementAndGet();
		BigInteger verificadaEmBnb = wbnb ? verificada : emBnb(arbitragem.token(), verificada);
		valorVerificadoPorClasse.merge(classe, verificadaEmBnb.max(BigInteger.ZERO), BigInteger::add);
	}

	private BigInteger emBnb(byte[] token, BigInteger quantia) {
		return custoDeGas.paraBnb(token, quantia).orElseThrow(() -> new IllegalStateException(
				"sem câmbio para BNB do token " + hex(token) + " (quantia " + quantia + ")"));
	}

	public synchronized Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("examinadas", examinadas.get());
		Map<String, Long> classes = new LinkedHashMap<>();
		porClasse.forEach((k, v) -> classes.put(k, v.get()));
		resumo.put("por_classe", classes);
		Map<String, Object> valores = new LinkedHashMap<>();
		valorPorClasse.forEach((classe, porToken) -> {
			Map<String, String> principais = new LinkedHashMap<>();
			BigInteger wbnb = porToken.get(hex(WBNB));
			BigInteger usdt = porToken.get(hex(USDT));
			if (wbnb != null) {
				principais.put("wbnb", wbnb.toString());
			}
			if (usdt != null) {
				principais.put("usdt", usdt.toString());
			}
			valores.put(classe, principais);
		});
		resumo.put("valor_por_classe", valores);
		Map<String, Long> motivos = new LinkedHashMap<>();
		motivosDaPoolFora.forEach((k, v) -> motivos.put(k, v.get()));
		resumo.put("motivos_da_pool_fora", motivos);
		Map<String, Long> naoLinear = new LinkedHashMap<>();
		naoLinearPorque.forEach((k, v) -> naoLinear.put(k, v.get()));
		resumo.put("nao_linear_porque", naoLinear);
		resumo.put("verificadas_na_cadeia", verificadas.get());
		Map<String, String> verificado = new LinkedHashMap<>();
		valorVerificadoPorClasse.forEach((k, v) -> verificado.put(k, v.toString()));
		resumo.put("valor_verificado_por_classe_bnb", verificado);
		resumo.put("arquivo", arquivo.toString());
		return resumo;
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	@Override
	public synchronized void close() {

		fila.shutdownNow();

		try {
			if (saida != null) {
				saida.close();
			}
		} catch (IOException e) {
			br.com.node.util.Falhas.anotar("autópsia do mercado não fechou o arquivo", e);
		}
	}
}