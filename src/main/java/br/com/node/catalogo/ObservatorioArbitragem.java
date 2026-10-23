package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ObjLongConsumer;

import br.com.node.nucleo.Arbitragem;

public final class ObservatorioArbitragem {

	@FunctionalInterface
	public interface AoConfirmar {
		void aceitar(Arbitragem arbitragem, long altura, int indice, int indiceDoGatilho);
	}

	private final Detector detector;
	private final AoConfirmar aoConfirmar;
	private final Catalogo catalogo;

	private static final int TETO_EMISSORES = 5_000;
	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private final Map<String, long[]> porCobertura = new LinkedHashMap<>();
	private final Map<String, BigInteger> wbnbPorCobertura = new LinkedHashMap<>();
	private final Map<String, Long> foraDoCatalogo = new HashMap<>();

	private long alturaCorrente = -1;
	private int indice;
	private final Map<String, Integer> ultimoToque = new java.util.HashMap<>();
	private long transacoesObservadas;
	private long arbitragensConfirmadas;
	private long primeiraAltura = -1;
	private long ultimaAltura = -1;
	private BigInteger lucroTotal = BigInteger.ZERO;
	private BigInteger maiorLucro = BigInteger.ZERO;
	private long maiorLucroAltura = -1;

	public ObservatorioArbitragem(Catalogo catalogo) {
		this(catalogo, (AoConfirmar) null);
	}

	public ObservatorioArbitragem(Catalogo catalogo, ObjLongConsumer<Arbitragem> aoConfirmar) {
		this(catalogo, aoConfirmar == null ? (AoConfirmar) null
				: (arbitragem, altura, indice, gatilho) -> aoConfirmar.accept(arbitragem, altura));
	}

	public ObservatorioArbitragem(Catalogo catalogo, AoConfirmar aoConfirmar) {

		this.detector = new Detector(poolId -> catalogo == null ? java.util.OptionalInt.empty()
				: catalogo.chaveDaPool(poolId)
						.map(chave -> Arrays.equals(chave.token0(), new byte[20]) ? java.util.OptionalInt.of(0)
								: Arrays.equals(chave.token1(), new byte[20]) ? java.util.OptionalInt.of(1)
										: java.util.OptionalInt.empty())
						.orElse(java.util.OptionalInt.empty()));
		this.aoConfirmar = aoConfirmar;
		this.catalogo = catalogo;
	}

	public synchronized void observar(List<br.com.node.vo.Log> logs, long altura) {
		transacoesObservadas++;

		if (altura != alturaCorrente) {
			alturaCorrente = altura;
			indice = 0;
			ultimoToque.clear();
		}
		int posicao = indice++;

		Map<String, Integer> antes = new HashMap<>();
		for (br.com.node.vo.Log log : logs) {
			String toque = toque(log);
			antes.putIfAbsent(toque, ultimoToque.getOrDefault(toque, -1));
		}
		for (br.com.node.vo.Log log : logs) {
			ultimoToque.put(toque(log), posicao);
		}

		Optional<Arbitragem> arbitragem = detector.em(logs).map(a -> comPoolsDosGerentes(a, logs));
		if (arbitragem.isEmpty()) {
			return;
		}

		if (aoConfirmar != null) {
			int gatilho = -1;
			for (byte[] pool : arbitragem.get().pools()) {
				gatilho = Math.max(gatilho, antes.getOrDefault(java.util.HexFormat.of().formatHex(pool), -1));
			}
			aoConfirmar.aceitar(arbitragem.get(), altura, posicao, gatilho);
		}

		arbitragensConfirmadas++;
		contarCobertura(arbitragem.get());
		if (primeiraAltura < 0) {
			primeiraAltura = altura;
		}
		ultimaAltura = altura;
		BigInteger lucro = arbitragem.get().sobra();
		lucroTotal = lucroTotal.add(lucro);
		if (lucro.compareTo(maiorLucro) > 0) {
			maiorLucro = lucro;
			maiorLucroAltura = altura;
		}
	}

	private static final java.util.Set<String> COFRES = java.util.Set.of(HexFormat.of().formatHex(Eventos.GERENTE_V4),
			HexFormat.of().formatHex(Eventos.GERENTE_INFINITY_CL), "238a358808379702088667322f80ac48bad5e6c4");

	static Arbitragem comPoolsDosGerentes(Arbitragem arbitragem, List<br.com.node.vo.Log> logs) {

		List<byte[]> dosGerentes = new java.util.ArrayList<>();
		for (br.com.node.vo.Log log : logs) {
			if (Eventos.ehSwapDeGerente(log)) {
				dosGerentes.add(log.topicos().get(1));
			}
		}

		if (dosGerentes.isEmpty()) {
			return arbitragem;
		}

		List<byte[]> pools = new java.util.ArrayList<>();
		for (byte[] pool : arbitragem.pools()) {
			if (!COFRES.contains(HexFormat.of().formatHex(pool))) {
				pools.add(pool);
			}
		}
		pools.addAll(dosGerentes);

		return new Arbitragem(pools, arbitragem.token(), arbitragem.sobra(), arbitragem.v2(), arbitragem.v3(),
				arbitragem.executor());
	}

	private static String toque(br.com.node.vo.Log log) {
		boolean deGerente = Eventos.ehSwapDeGerente(log) || Eventos.ehLiquidezDeGerente(log);
		return HexFormat.of().formatHex(deGerente ? log.topicos().get(1) : log.endereco());
	}

	private void contarCobertura(Arbitragem arbitragem) {

		int fora = 0;
		for (byte[] pool : arbitragem.pools()) {
			if (catalogo.par(pool).isEmpty()) {
				fora++;
				String chave = HexFormat.of().formatHex(pool);
				if (foraDoCatalogo.size() < TETO_EMISSORES || foraDoCatalogo.containsKey(chave)) {
					foraDoCatalogo.merge(chave, 1L, Long::sum);
				}
			}
		}

		String cobertura = fora == 0 ? "todas no catalogo" : "com pool fora do catalogo";
		porCobertura.computeIfAbsent(cobertura, k -> new long[1])[0]++;
		if (Arrays.equals(arbitragem.token(), WBNB)) {
			wbnbPorCobertura.merge(cobertura, arbitragem.sobra(), BigInteger::add);
		}
	}

	public synchronized Map<String, Object> resumo() {
		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("transacoes_observadas", transacoesObservadas);
		resumo.put("arbitragens_confirmadas", arbitragensConfirmadas);
		resumo.put("primeira_altura", primeiraAltura);
		resumo.put("ultima_altura", ultimaAltura);
		resumo.put("duracao_janela_blocos", primeiraAltura < 0 ? 0 : ultimaAltura - primeiraAltura + 1);
		resumo.put("lucro_total", lucroTotal.toString());
		resumo.put("maior_lucro", maiorLucro.toString());
		resumo.put("maior_lucro_altura", maiorLucroAltura);
		Map<String, Long> cobertura = new LinkedHashMap<>();
		porCobertura.forEach((k, v) -> cobertura.put(k, v[0]));
		resumo.put("arbitragens_por_cobertura", cobertura);
		Map<String, String> wbnb = new LinkedHashMap<>();
		wbnbPorCobertura.forEach((k, v) -> wbnb.put(k, v.toString()));
		resumo.put("lucro_wbnb_por_cobertura", wbnb);
		Map<String, Long> emissores = new LinkedHashMap<>();
		foraDoCatalogo.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(15)
				.forEach(e -> emissores.put(e.getKey(), e.getValue()));
		resumo.put("contratos_fora_do_catalogo", emissores);
		resumo.put("mempool_projecoes", 0);
		resumo.put("chegada_antes_medido", false);
		return resumo;
	}
}