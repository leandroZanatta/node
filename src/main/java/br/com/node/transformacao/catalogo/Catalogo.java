package br.com.node.transformacao.catalogo;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.transformacao.nucleo.EstadoV3;
import br.com.node.transformacao.nucleo.Par;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.vo.Log;

public final class Catalogo {

	private final Map<String, ReservasV2> v2 = new ConcurrentHashMap<>();
	private final Map<String, EstadoV3> v3 = new ConcurrentHashMap<>();

	private final Map<String, EstadoNoGerente> noGerente = new ConcurrentHashMap<>();

	private final Map<String, ChaveDaPool> chaves = new ConcurrentHashMap<>();
	private final java.util.Set<String> pedidas = ConcurrentHashMap.newKeySet();
	private volatile java.util.function.BiConsumer<byte[], byte[]> aoFaltarChave = (gerente, poolId) -> {
	};
	private volatile java.util.function.Consumer<ChaveDaPool> aoNascer = chave -> {
	};

	private final Map<String, Par> pares = new ConcurrentHashMap<>();

	private final Map<String, Long> descartados = new ConcurrentHashMap<>();

	private final Map<String, Long> atividade = new ConcurrentHashMap<>();

	public Optional<ReservasV2> reservas(byte[] pool) {
		return Optional.ofNullable(v2.get(chave(pool)));
	}

	public Optional<EstadoV3> estado(byte[] pool) {

		if (deGerente(pool)) {
			return Optional.ofNullable(noGerente.get(chave(pool))).map(EstadoNoGerente::estado);
		}

		return Optional.ofNullable(v3.get(chave(pool)));
	}

	public Optional<Par> par(byte[] pool) {

		if (deGerente(pool)) {
			return Optional.ofNullable(chaves.get(chave(pool))).flatMap(Catalogo::parDoGerente);
		}

		return Optional.ofNullable(pares.get(chave(pool)));
	}

	public record Lados(byte[] token0, byte[] token1) {
	}

	public Optional<Lados> lados(byte[] pool) {

		if (deGerente(pool)) {
			return Optional.ofNullable(chaves.get(chave(pool))).flatMap(chave -> {
				byte[] token0 = Arrays.equals(chave.token0(), NATIVO) ? WBNB : chave.token0();
				byte[] token1 = Arrays.equals(chave.token1(), NATIVO) ? WBNB : chave.token1();
				return Arrays.equals(token0, token1) ? Optional.empty() : Optional.of(new Lados(token0, token1));
			});
		}

		return par(pool).map(par -> new Lados(par.token0(), par.token1()));
	}

	static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] NATIVO = new byte[20];

	private static Optional<Par> parDoGerente(ChaveDaPool chave) {

		byte[] token0 = Arrays.equals(chave.token0(), NATIVO) ? WBNB : chave.token0();
		byte[] token1 = Arrays.equals(chave.token1(), NATIVO) ? WBNB : chave.token1();

		return Arrays.equals(token0, token1) ? Optional.empty() : Optional.of(new Par(token0, token1));
	}

	public void aprenderPar(byte[] pool, List<byte[]> tokensVistos) {

		if (tokensVistos.size() != 2) {
			return;
		}

		Par par = new Par(tokensVistos.get(0), tokensVistos.get(1));

		if (pares.putIfAbsent(chave(pool), par) == null) {
			poolsPorPar.computeIfAbsent(par, p -> ConcurrentHashMap.newKeySet()).add(chave(pool));
		}
	}

	private final Map<Par, java.util.Set<String>> poolsPorPar = new ConcurrentHashMap<>();

	public List<byte[]> poolsDoPar(byte[] tokenA, byte[] tokenB) {

		java.util.Set<String> pools = poolsPorPar.get(new Par(tokenA, tokenB));

		if (pools == null) {
			return List.of();
		}

		List<byte[]> lista = new ArrayList<>(pools.size());
		pools.forEach(pool -> lista.add(HexFormat.of().parseHex(pool)));
		return lista;
	}

	public void inferirPares(List<List<Log>> porTransacao) {

		Map<String, Map<String, byte[]>> candidatos = new HashMap<>();

		for (List<Log> logs : porTransacao) {
			for (Log log : logs) {

				if (!log.assinadoPor(Eventos.TRANSFER) || log.topicos().size() < 3) {
					continue;
				}

				for (int ponta = 1; ponta <= 2; ponta++) {

					byte[] endereco = Eventos.enderecoDoTopico(log, ponta);

					if (endereco.length == 20) {
						candidatos.computeIfAbsent(HexFormat.of().formatHex(endereco), chave -> new HashMap<>())
								.put(HexFormat.of().formatHex(log.endereco()), log.endereco());
					}
				}
			}
		}

		candidatos.forEach((endereco, tokens) -> {

			byte[] pool = HexFormat.of().parseHex(endereco);

			if (conhece(pool)) {
				aprenderPar(pool, new ArrayList<>(tokens.values()));
			}
		});
	}

	public List<byte[]> pools() {

		List<byte[]> todas = new ArrayList<>();

		v2.keySet().forEach(pool -> todas.add(HexFormat.of().parseHex(pool)));
		v3.keySet().forEach(pool -> todas.add(HexFormat.of().parseHex(pool)));
		noGerente.keySet().forEach(pool -> {
			ChaveDaPool chave = chaves.get(pool);
			if (chave != null && chave.modelavel()) {
				todas.add(HexFormat.of().parseHex(pool));
			}
		});

		return todas;
	}

	public int paresConhecidos() {
		return pares.size();
	}

	public boolean conhece(byte[] pool) {
		return v2.containsKey(chave(pool)) || v3.containsKey(chave(pool)) || noGerente.containsKey(chave(pool));
	}

	public static boolean deGerente(byte[] pool) {
		return pool.length == 32;
	}

	public Optional<ChaveDaPool> chaveDaPool(byte[] poolId) {
		return Optional.ofNullable(chaves.get(chave(poolId)));
	}

	public Optional<Integer> taxaNoGerente(byte[] poolId) {
		return Optional.ofNullable(noGerente.get(chave(poolId))).map(EstadoNoGerente::taxa);
	}

	public int poolsV2() {
		return v2.size();
	}

	public long atividade(byte[] pool) {
		return atividade.getOrDefault(chave(pool), 0L);
	}

	public int poolsV3() {
		return v3.size();
	}

	public Optional<ReservasV2> aplicarSync(Log log, long altura) {

		String pool = chave(log.endereco());

		if (log.dados() == null || log.dados().length < 64) {
			descartados.merge(pool, 1L, Long::sum);
			return Optional.empty();
		}

		BigInteger reserva0 = Eventos.palavra(log, 0);
		BigInteger reserva1 = Eventos.palavra(log, 1);

		if (!plausivel(reserva0) || !plausivel(reserva1)) {
			descartados.merge(pool, 1L, Long::sum);
			return Optional.empty();
		}

		ReservasV2 antes = v2.get(pool);

		v2.put(pool, new ReservasV2(reserva0, reserva1, altura));
		atividade.merge(pool, 1L, Long::sum);

		return Optional.ofNullable(antes);
	}

	private static final BigInteger TETO_UINT112 = BigInteger.TWO.pow(112).subtract(BigInteger.ONE);

	private static boolean plausivel(BigInteger reserva) {
		return reserva.signum() >= 0 && reserva.compareTo(TETO_UINT112) <= 0;
	}

	public void aplicarSwapV3(Log log, long altura) {

		try {

			EstadoV3 depois = new EstadoV3(Eventos.palavra(log, 2), Eventos.palavra(log, 3),
					(int) Eventos.palavraComSinal(log, 4), altura);
			EstadoV3 antes = v3.put(chave(log.endereco()), depois);
			atividade.merge(chave(log.endereco()), 1L, Long::sum);

			EstadoV3.taxaEntre(antes, depois, Eventos.palavraComSinalGrande(log, 0),
					Eventos.palavraComSinalGrande(log, 1))
					.ifPresent(taxa -> taxasObservadasV3.merge(chave(log.endereco()), new int[] { taxa, -1 },
							(velhas, nova) -> new int[] { nova[0], velhas[0] }));

		} catch (IllegalArgumentException impossivel) {
			descartados.merge(chave(log.endereco()), 1L, Long::sum);
		}
	}

	public int poolsDescartadas() {
		return descartados.size();
	}

	private final Map<String, int[]> taxasObservadasV3 = new ConcurrentHashMap<>();

	public Optional<Integer> taxaObservadaV3(byte[] pool) {

		int[] ultimas = taxasObservadasV3.get(chave(pool));

		return ultimas != null && ultimas[1] >= 0 && Math.abs(ultimas[0] - ultimas[1]) <= 1 ? Optional.of(ultimas[0])
				: Optional.empty();
	}

	public boolean taxaV3JaVista(byte[] pool) {
		return taxasObservadasV3.containsKey(chave(pool));
	}

	public Optional<EstadoNoGerente> estadoNoGerente(byte[] gerente, byte[] poolId) {
		return Optional.ofNullable(noGerente.get(chave(poolId))).filter(e -> Arrays.equals(e.gerente(), gerente));
	}

	public int poolsNoGerente(byte[] gerente) {

		return (int) noGerente.values().stream().filter(e -> Arrays.equals(e.gerente(), gerente)).count();
	}

	public void aplicarSwapDeGerente(Log log, long altura) {

		String pool = chave(log.topicos().get(1));

		try {

			noGerente.put(pool,
					new EstadoNoGerente(log.endereco(),
							new EstadoV3(Eventos.palavra(log, 2), Eventos.palavra(log, 3),
									(int) Eventos.palavraComSinal(log, 4), altura),
							Eventos.palavra(log, 5).intValueExact()));
			atividade.merge(pool, 1L, Long::sum);

			if (!chaves.containsKey(pool) && pedidas.add(pool)) {
				aoFaltarChave.accept(log.endereco(), log.topicos().get(1));
			}

		} catch (IllegalArgumentException | ArithmeticException impossivel) {
			descartados.merge(pool, 1L, Long::sum);
		}
	}

	public void aplicarInicializacao(Log log, long altura) {
		ChaveDaPool.doInitialize(log, altura).ifPresent(chave -> {
			aprenderChave(chave);
			aoNascer.accept(chave);
		});
	}

	public void aprenderChave(ChaveDaPool chave) {

		String pool = chave(chave.poolId());

		chaves.put(pool, chave);
		pedidas.remove(pool);
	}

	public void liberarPedidas() {
		pedidas.removeIf(pool -> !chaves.containsKey(pool));
	}

	public Optional<ChaveDaPool> chave(byte[] gerente, byte[] poolId) {
		return Optional.ofNullable(chaves.get(chave(poolId))).filter(c -> Arrays.equals(c.gerente(), gerente));
	}

	public void aoFaltarChave(java.util.function.BiConsumer<byte[], byte[]> aoFaltarChave) {
		this.aoFaltarChave = aoFaltarChave;
	}

	@FunctionalInterface
	public interface MudancaDeLiquidez {
		void aoMudar(byte[] pool, int inferior, int superior, BigInteger delta, long altura);
	}

	private volatile MudancaDeLiquidez aoMudarLiquidez = (pool, inferior, superior, delta, altura) -> {
	};

	public void aoMudarLiquidez(MudancaDeLiquidez aoMudarLiquidez) {
		this.aoMudarLiquidez = aoMudarLiquidez;
	}

	public void aoNascer(java.util.function.Consumer<ChaveDaPool> aoNascer) {
		this.aoNascer = aoNascer;
	}

	public Map<String, Long> resumoDoGerente(byte[] gerente) {

		long comEstado = 0;
		long comChave = 0;
		long modelaveis = 0;
		long hookForaDoSwap = 0;

		for (Map.Entry<String, EstadoNoGerente> pool : noGerente.entrySet()) {

			if (!Arrays.equals(pool.getValue().gerente(), gerente)) {
				continue;
			}

			comEstado++;
			ChaveDaPool chave = chaves.get(pool.getKey());

			if (chave != null) {
				comChave++;
				modelaveis += chave.modelavel() ? 1 : 0;
				hookForaDoSwap += chave.hookForaDoSwap() ? 1 : 0;
			}
		}

		Map<String, Long> resumo = new java.util.LinkedHashMap<>();
		resumo.put("com_estado", comEstado);
		resumo.put("com_chave", comChave);
		resumo.put("modelaveis", modelaveis);
		resumo.put("hook_fora_do_swap", hookForaDoSwap);

		return resumo;
	}

	public void aplicarLiquidezDeGerente(Log log, long altura) {

		String pool = chave(log.topicos().get(1));
		EstadoNoGerente atual = noGerente.get(pool);

		long tickInferior = Eventos.palavraComSinal(log, 0);
		long tickSuperior = Eventos.palavraComSinal(log, 1);

		aoMudarLiquidez.aoMudar(log.topicos().get(1), (int) tickInferior, (int) tickSuperior,
				Eventos.palavraComSinalGrande(log, 2), altura);

		if (atual == null || atual.estado().tick() < tickInferior || atual.estado().tick() >= tickSuperior) {
			return;
		}

		EstadoV3 antes = atual.estado();

		try {

			noGerente.put(pool,
					new EstadoNoGerente(atual.gerente(),
							new EstadoV3(antes.sqrtPrecoX96(),
									antes.liquidez().add(Eventos.palavraComSinalGrande(log, 2)), antes.tick(), altura),
							atual.taxa()));

		} catch (IllegalArgumentException impossivel) {

			noGerente.remove(pool);

			descartados.merge(pool, 1L, Long::sum);
		}
	}

	public void aplicarLiquidezV3(Log log, long altura) {

		EstadoV3 atual = v3.get(chave(log.endereco()));

		long tickInferior = Eventos.tickDoTopico(log, 2);
		long tickSuperior = Eventos.tickDoTopico(log, 3);

		aoMudarLiquidez.aoMudar(log.endereco(), (int) tickInferior, (int) tickSuperior, Eventos.liquidezMovida(log),
				altura);

		if (atual == null || atual.tick() < tickInferior || atual.tick() >= tickSuperior) {
			return;
		}

		try {

			v3.put(chave(log.endereco()), new EstadoV3(atual.sqrtPrecoX96(),
					atual.liquidez().add(Eventos.liquidezMovida(log)), atual.tick(), altura));

		} catch (IllegalArgumentException impossivel) {

			v3.remove(chave(log.endereco()));

			descartados.merge(chave(log.endereco()), 1L, Long::sum);
		}
	}

	private static String chave(byte[] endereco) {
		return HexFormat.of().formatHex(endereco);
	}
}