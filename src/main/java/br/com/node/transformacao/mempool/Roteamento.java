package br.com.node.transformacao.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.nucleo.Direcao;
import br.com.node.transformacao.nucleo.Par;
import br.com.node.transformacao.nucleo.Passo;
import br.com.node.transformacao.nucleo.PoolV2;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.vo.SwapPendente;

public final class Roteamento {

	private static final Set<String> ENTRADA_EXATA = Set.of("38ed1739", "5c11d795", "18cbafe5", "791ac947");

	private static final Set<String> ENTRADA_EXATA_COM_PREFIXO = Set.of("d8396489");

	private static final Set<String> ENTRADA_ETH = Set.of("7ff36ab5", "b6f9de95");

	private static final int PALAVRA = 32;
	private static final int SELETOR = 4;

	private Roteamento() {
	}

	public static Optional<SwapPendente> decodificar(byte[] calldata) {
		return decodificar(calldata, null);
	}

	public static Optional<SwapPendente> decodificar(byte[] calldata, BigInteger valor) {

		if (calldata == null || calldata.length < SELETOR + PALAVRA * 2) {
			return Optional.empty();
		}

		String seletor = hex(calldata, 0, SELETOR);

		if (RoteadorDeSaltos.SELETOR.equals(seletor)) {
			return RoteadorDeSaltos.decodificar(calldata);
		}

		if (RoteadorDag.SELETOR.equals(seletor)) {
			return RoteadorDag.decodificar(calldata);
		}

		if (RoteadorSolidly.SELECOES_TOKEN.contains(seletor)) {
			return RoteadorSolidly.decodificar(calldata, null);
		}

		if (RoteadorSolidly.SELECOES_ETH.contains(seletor)) {
			return RoteadorSolidly.decodificar(calldata, valor);
		}

		if (ENTRADA_EXATA.contains(seletor)) {

			if (calldata.length < SELETOR + PALAVRA * 3) {
				return Optional.empty();
			}

			int offsetDoCaminho = SELETOR + inteiro(palavra(calldata, 2));

			return caminho(calldata, offsetDoCaminho).filter(c -> c.size() >= 2)
					.map(c -> new SwapPendente(palavra(calldata, 0), c));
		}

		if (ENTRADA_EXATA_COM_PREFIXO.contains(seletor)) {

			if (calldata.length < SELETOR + PALAVRA * 4) {
				return Optional.empty();
			}

			int offsetDoCaminho = SELETOR + inteiro(palavra(calldata, 3));

			return caminho(calldata, offsetDoCaminho).filter(c -> c.size() >= 2)
					.map(c -> new SwapPendente(palavra(calldata, 1), c));
		}

		if (ENTRADA_ETH.contains(seletor) && valor != null && valor.signum() > 0) {

			int offsetDoCaminho = SELETOR + inteiro(palavra(calldata, 1));

			return caminho(calldata, offsetDoCaminho).filter(c -> c.size() >= 2).map(c -> new SwapPendente(valor, c));
		}

		return Optional.empty();
	}

	private static final java.util.HexFormat HEX = java.util.HexFormat.of();

	private static final java.util.Map<String, byte[]> FACTORY_DO_ROTEADOR = java.util.Map.of(
			"10ed43c718714eb63d5aa57b78b54704e256024e", hexa("ca143ce32fe78f1f7019d7d551a6402fc5350c73"),
			"05ff2b0db69458a0750badebc4f9e13add608c7f", hexa("bcfccbde45ce874adcb698cc183debcf17952812"),
			"3a6d8ca21d1cf76f653a67577fa0d27453350dd8", hexa("858e3312ed3a876947ea49d572a7c42de08af7ee"),
			"cf0febd3f17cef5b47b0cd257acf6025c5bff3b7", hexa("0841bd0b734e4f5853f0dd8d7ea041c241fb0da6"),
			"7dae51bd3e3376b8c7c4900e9107f12be3af1ba8", hexa("3cd1c46068daea5ebb0d3f55f6915b10648062b8"),
			"1b02da8cb0d097eb8d57a175b88c7d8b47997506", hexa("c35dadb65012ec5796536bd9864ed8773abc74c4"));

	private static byte[] hexa(String hex) {
		return HEX.parseHex(hex);
	}

	public static List<byte[]> mapearParaPools(SwapPendente swap, Catalogo catalogo) {
		return mapearParaPools(swap, catalogo, null);
	}

	public static List<byte[]> mapearParaPools(SwapPendente swap, Catalogo catalogo, byte[] roteador) {

		if (swap == null || swap.caminho() == null || swap.caminho().size() < 2) {
			return List.of();
		}

		int pernas = swap.caminho().size() - 1;

		if (swap.pools().size() == pernas) {
			for (int i = 0; i < pernas; i++) {
				byte[] pool = swap.pools().get(i);
				byte[] entra = swap.caminho().get(i);
				byte[] sai = swap.caminho().get(i + 1);
				if (!catalogo.par(pool).map(par -> pareia(par, entra, sai)).orElse(false)) {
					return List.of();
				}
			}
			return swap.pools();
		}

		byte[] factory = roteador == null ? null : FACTORY_DO_ROTEADOR.get(HEX.formatHex(roteador));

		List<byte[]> pools = new ArrayList<>();

		for (int i = 0; i < pernas; i++) {
			byte[] token0 = swap.caminho().get(i);
			byte[] token1 = swap.caminho().get(i + 1);

			Optional<byte[]> pool;

			if (factory != null) {
				pool = br.com.node.transformacao.catalogo.Fabricas.parV2(factory, token0, token1).filter(
						endereco -> catalogo.par(endereco).map(par -> pareia(par, token0, token1)).orElse(false));
			} else {
				List<byte[]> candidatas = catalogo.poolsDoPar(token0, token1).stream()
						.filter(endereco -> catalogo.conhece(endereco)).limit(2).toList();
				if (candidatas.size() == 1) {
					pool = Optional.of(candidatas.get(0));
				} else if (!candidatas.isEmpty()) {
					List<Optional<byte[]>> porFactory = FACTORY_DO_ROTEADOR.values().stream()
							.map(f -> br.com.node.transformacao.catalogo.Fabricas.parV2(f, token0, token1).filter(
									addr -> catalogo.par(addr).map(par -> pareia(par, token0, token1)).orElse(false)))
							.filter(Optional::isPresent).limit(2).toList();
					pool = porFactory.size() == 1 ? porFactory.get(0) : Optional.empty();
				} else {
					pool = Optional.empty();
				}
			}

			if (pool.isEmpty()) {
				return List.of();
			}

			pools.add(pool.get());
		}

		return List.copyOf(pools);
	}

	public static Optional<List<Passo>> projetar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool) {
		return projetar(swap, catalogo, taxaDaPool, null, pool -> false);
	}

	public static Optional<List<Passo>> projetar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool, byte[] roteador) {
		return projetar(swap, catalogo, taxaDaPool, roteador, pool -> false);
	}

	public static Optional<List<Passo>> projetar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool, byte[] roteador, Function<byte[], Boolean> solidlyStable) {

		if (swap == null || swap.caminho() == null || swap.caminho().size() < 2 || swap.entra().signum() <= 0) {
			return Optional.empty();
		}

		List<byte[]> pools = mapearParaPools(swap, catalogo, roteador);
		if (pools.size() != swap.caminho().size() - 1) {
			return Optional.empty();
		}

		List<Passo> passos = new ArrayList<>();
		BigInteger carregando = swap.entra();

		for (int i = 0; i < pools.size(); i++) {
			byte[] pool = pools.get(i);
			Optional<ReservasV2> estado = catalogo.reservas(pool);
			Optional<Integer> taxa = taxaDaPool.apply(pool);

			if (estado.isEmpty() || taxa.isEmpty()) {
				return Optional.empty();
			}

			byte[] tokenEntra = swap.caminho().get(i);
			byte[] tokenSai = swap.caminho().get(i + 1);
			Par par = catalogo.par(pool).orElseThrow();
			Direcao direcao = Arrays.equals(par.token0(), tokenEntra) ? Direcao.ZERO_PARA_UM : Direcao.UM_PARA_ZERO;

			br.com.node.transformacao.nucleo.Pool atual = solidlyStable.apply(pool)
					? new br.com.node.transformacao.nucleo.PoolSolidlyStable(estado.get().reserva0(), estado.get().reserva1(),
							taxa.get())
					: new PoolV2(estado.get().reserva0(), estado.get().reserva1(), taxa.get());
			Passo passo;
			try {
				passo = atual.aplicar(direcao, carregando);
			} catch (IllegalArgumentException e) {
				return Optional.empty();
			}

			if (passo.saida().signum() <= 0) {
				return Optional.empty();
			}

			passos.add(passo);
			carregando = passo.saida();
		}

		return Optional.of(List.copyOf(passos));
	}

	private static boolean pareia(Par par, byte[] token0, byte[] token1) {
		return (Arrays.equals(par.token0(), token0) && Arrays.equals(par.token1(), token1))
				|| (Arrays.equals(par.token0(), token1) && Arrays.equals(par.token1(), token0));
	}

	private static Optional<List<byte[]>> caminho(byte[] calldata, int offset) {

		if (offset < 0 || offset + PALAVRA > calldata.length) {
			return Optional.empty();
		}

		int tamanho = inteiro(new BigInteger(1, Arrays.copyOfRange(calldata, offset, offset + PALAVRA)));

		if (tamanho < 2 || tamanho > 8 || offset + PALAVRA + (long) tamanho * PALAVRA > calldata.length) {
			return Optional.empty();
		}

		List<byte[]> caminho = new ArrayList<>();

		for (int i = 0; i < tamanho; i++) {
			int base = offset + PALAVRA + i * PALAVRA;
			caminho.add(Arrays.copyOfRange(calldata, base + 12, base + PALAVRA));
		}

		return Optional.of(caminho);
	}

	private static BigInteger palavra(byte[] calldata, int indice) {
		int base = SELETOR + indice * PALAVRA;
		return new BigInteger(1, Arrays.copyOfRange(calldata, base, base + PALAVRA));
	}

	private static int inteiro(BigInteger valor) {
		return valor.bitLength() > 31 ? -1 : valor.intValueExact();
	}

	private static String hex(byte[] bytes, int inicio, int quantos) {
		return HEX.formatHex(bytes, inicio, inicio + quantos);
	}
}