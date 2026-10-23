package br.com.node.transformacao.catalogo;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import br.com.node.transformacao.nucleo.Direcao;
import br.com.node.transformacao.nucleo.EstadoV3;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.vo.ProvaDeTaxa;

public final class Taxas {

	private final Map<String, Integer> provadas = new ConcurrentHashMap<>();
	private final Set<String> ambiguas = ConcurrentHashMap.newKeySet();

	private final Map<String, Long> motivos = new ConcurrentHashMap<>();

	private final Map<String, Map<Integer, Set<String>>> provadores = new ConcurrentHashMap<>();

	private final Map<String, Long> fechamentos = new ConcurrentHashMap<>();

	private static final long FECHAMENTOS_DE_POOL_MOVIMENTADA = 10;
	private static final int CHAMADORES_DE_POOL_FECHADA = 2;

	private static final Set<String> ROTEADORES_PUBLICOS = Set.of("10ed43c718714eb63d5aa57b78b54704e256024e",
			"05ff2b0db69458a0750badebc4f9e13add608c7f", "3a6d8ca21d1cf76f653a67577fa0d27453350dd8",
			"cf0febd3f17cef5b47b0cd257acf6025c5bff3b7", "cde540d7eafe93ac5fe6233bee57e1270d3e330f",
			"7dae51bd3e3376b8c7c4900e9107f12be3af1ba8", "1b02da8cb0d097eb8d57a175b88c7d8b47997506");

	private final Map<String, ReservasV2> antesDoSwap = new ConcurrentHashMap<>();

	private String transacaoDoPendente = "";

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
		return provadores.entrySet().stream().filter(entrada -> entrada.getValue().size() > 1).map(Map.Entry::getKey)
				.toList();
	}

	private void doSwapV2(String pool, Transicao transicao) {

		ReservasV2 antes = antesDoSwap.remove(pool);

		if (antes == null) {
			anotar("v2 sem estado anterior na transação");
			return;
		}

		BigInteger entrada0 = Eventos.palavra(transicao.log(), 0);
		BigInteger entrada1 = Eventos.palavra(transicao.log(), 1);

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

			anotar("v3 estado impossível");
			return;
		}

		Optional<Integer> taxa = depois.taxaQueFecha(Eventos.palavraComSinalGrande(transicao.log(), 0),
				Eventos.palavraComSinalGrande(transicao.log(), 1));

		anotar(taxa.isEmpty() ? "v3 nenhum degrau fecha sozinho"
				: "v3 " + provar(pool, taxa.get(), chamador(transicao)));
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