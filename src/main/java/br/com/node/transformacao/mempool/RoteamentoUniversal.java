package br.com.node.transformacao.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.nucleo.Direcao;
import br.com.node.transformacao.nucleo.EstadoV3;
import br.com.node.transformacao.nucleo.Par;
import br.com.node.transformacao.nucleo.Passo;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.transformacao.nucleo.PoolV3;
import br.com.node.vo.SwapPendente;

public final class RoteamentoUniversal {

	private static final HexFormat HEX = HexFormat.of();
	private static final String SELETOR = "24856bc3";

	private static final String SELETOR_COM_DEADLINE = "3593564c";
	private static final int V3_SWAP_EXACT_IN = 0x00;

	private static final int V2_SWAP_EXACT_IN = 0x08;
	private static final int MASCARA_COMANDO = 0x3f;
	private static final int PALAVRA = 32;

	public record SwapV3(BigInteger entra, List<byte[]> tokens, List<Integer> fees) {
	}

	private RoteamentoUniversal() {
	}

	public static Map<String, Pool> projetar(SwapV3 swap, Catalogo catalogo,
			java.util.function.Function<byte[], Optional<Integer>> taxa,
			java.util.function.Function<byte[], java.util.OptionalInt> espacamento) {

		Map<String, Pool> overlay = new LinkedHashMap<>();
		BigInteger carregando = swap.entra();

		if (carregando.signum() <= 0) {
			return Map.of();
		}

		for (int i = 0; i < swap.fees().size(); i++) {

			byte[] tokenIn = swap.tokens().get(i);
			byte[] tokenOut = swap.tokens().get(i + 1);
			Optional<byte[]> pool = poolV3(catalogo, taxa, tokenIn, tokenOut, swap.fees().get(i));

			if (pool.isEmpty()) {
				return Map.of();
			}

			Par par = catalogo.par(pool.get()).orElseThrow();
			EstadoV3 estado = catalogo.estado(pool.get()).orElseThrow();

			java.util.OptionalInt esp = espacamento.apply(pool.get());
			if (esp.isEmpty()) {
				return Map.of();
			}
			PoolV3 v3 = new PoolV3(estado.sqrtPrecoX96(), estado.liquidez(), estado.tick(), swap.fees().get(i),
					esp.getAsInt());
			Direcao direcao = Arrays.equals(par.token0(), tokenIn) ? Direcao.ZERO_PARA_UM : Direcao.UM_PARA_ZERO;

			try {
				Passo passo = v3.aplicar(direcao, carregando);
				overlay.put(HEX.formatHex(pool.get()), passo.depois());
				carregando = passo.saida();
			} catch (IllegalArgumentException cruzaOuSemLiquidez) {
				return Map.of();
			}
		}

		return overlay;
	}

	private static Optional<byte[]> poolV3(Catalogo catalogo,
			java.util.function.Function<byte[], Optional<Integer>> taxa, byte[] a, byte[] b, int fee) {

		return catalogo.poolsDoPar(a, b).stream().filter(p -> catalogo.estado(p).isPresent())
				.filter(p -> taxa.apply(p).map(t -> t == fee).orElse(false))
				.filter(p -> catalogo.par(p).map(par -> pareia(par, a, b)).orElse(false)).findFirst();
	}

	private static boolean pareia(Par par, byte[] a, byte[] b) {
		return (Arrays.equals(par.token0(), a) && Arrays.equals(par.token1(), b))
				|| (Arrays.equals(par.token0(), b) && Arrays.equals(par.token1(), a));
	}

	static boolean ehUniversalRouter(byte[] calldata) {
		if (calldata == null || calldata.length < 4)
			return false;
		String sel = HEX.formatHex(calldata, 0, 4);
		return SELETOR.equals(sel) || SELETOR_COM_DEADLINE.equals(sel);
	}

	public static Optional<SwapV3> decodificar(byte[] calldata) {

		if (calldata == null || calldata.length < 4 + PALAVRA * 2) {
			return Optional.empty();
		}
		String sel = hex(calldata, 0, 4);
		if (!sel.equals(SELETOR) && !sel.equals(SELETOR_COM_DEADLINE)) {
			return Optional.empty();
		}

		int args = 4;
		int offComandos = args + inteiro(palavra(calldata, args, 0));
		int offEntradas = args + inteiro(palavra(calldata, args, 1));

		Optional<byte[]> comandos = fatia(calldata, offComandos);
		Optional<List<byte[]>> entradas = arrayDeBytes(calldata, offEntradas);

		if (comandos.isEmpty() || entradas.isEmpty()) {
			return Optional.empty();
		}

		byte[] cmds = comandos.get();

		for (int i = 0; i < cmds.length && i < entradas.get().size(); i++) {
			if ((cmds[i] & MASCARA_COMANDO) == V3_SWAP_EXACT_IN) {
				return entradaV3(entradas.get().get(i));
			}
		}

		return Optional.empty();
	}

	public static Optional<SwapPendente> decodificarComoV2(byte[] calldata) {

		if (calldata == null || calldata.length < 4 + PALAVRA * 2) {
			return Optional.empty();
		}
		String sel = hex(calldata, 0, 4);
		if (!sel.equals(SELETOR) && !sel.equals(SELETOR_COM_DEADLINE)) {
			return Optional.empty();
		}

		int args = 4;
		int offComandos = args + inteiro(palavra(calldata, args, 0));
		int offEntradas = args + inteiro(palavra(calldata, args, 1));

		Optional<byte[]> comandos = fatia(calldata, offComandos);
		Optional<List<byte[]>> entradas = arrayDeBytes(calldata, offEntradas);

		if (comandos.isEmpty() || entradas.isEmpty()) {
			return Optional.empty();
		}

		byte[] cmds = comandos.get();

		for (int i = 0; i < cmds.length && i < entradas.get().size(); i++) {
			if ((cmds[i] & MASCARA_COMANDO) == V2_SWAP_EXACT_IN) {
				return entradaV2(entradas.get().get(i));
			}
		}

		return Optional.empty();
	}

	private static Optional<SwapPendente> entradaV2(byte[] entrada) {

		if (entrada.length < PALAVRA * 5) {
			return Optional.empty();
		}

		BigInteger entra = palavra(entrada, 0, 1);
		int offPath = inteiro(palavra(entrada, 0, 3));

		if (offPath < 0 || offPath + PALAVRA > entrada.length) {
			return Optional.empty();
		}

		int count = inteiro(new BigInteger(1, Arrays.copyOfRange(entrada, offPath, offPath + PALAVRA)));
		if (count < 2 || offPath + PALAVRA + (long) count * PALAVRA > entrada.length) {
			return Optional.empty();
		}

		List<byte[]> caminho = new ArrayList<>();
		int base = offPath + PALAVRA;
		for (int i = 0; i < count; i++) {

			int slot = base + i * PALAVRA;
			caminho.add(Arrays.copyOfRange(entrada, slot + 12, slot + 32));
		}

		return Optional.of(new SwapPendente(entra, caminho));
	}

	private static Optional<SwapV3> entradaV3(byte[] entrada) {

		if (entrada.length < PALAVRA * 5) {
			return Optional.empty();
		}

		BigInteger entra = palavra(entrada, 0, 1);
		int offPath = inteiro(palavra(entrada, 0, 3));

		return caminhoV3(entrada, offPath).filter(c -> c.tokens().size() >= 2)
				.map(c -> new SwapV3(entra, c.tokens(), c.fees()));
	}

	private static Optional<SwapV3> caminhoV3(byte[] dados, int offset) {

		if (offset < 0 || offset + PALAVRA > dados.length) {
			return Optional.empty();
		}

		int tamanho = inteiro(new BigInteger(1, Arrays.copyOfRange(dados, offset, offset + PALAVRA)));
		int base = offset + PALAVRA;

		if (tamanho < 43 || (tamanho - 20) % 23 != 0 || base + tamanho > dados.length) {
			return Optional.empty();
		}

		List<byte[]> tokens = new ArrayList<>();
		List<Integer> fees = new ArrayList<>();
		int p = base;

		tokens.add(Arrays.copyOfRange(dados, p, p + 20));
		p += 20;

		while (p + 23 <= base + tamanho) {
			fees.add(new BigInteger(1, Arrays.copyOfRange(dados, p, p + 3)).intValueExact());
			tokens.add(Arrays.copyOfRange(dados, p + 3, p + 23));
			p += 23;
		}

		return Optional.of(new SwapV3(BigInteger.ZERO, tokens, fees));
	}

	private static Optional<byte[]> fatia(byte[] calldata, int offset) {

		if (offset < 0 || offset + PALAVRA > calldata.length) {
			return Optional.empty();
		}

		int tamanho = inteiro(new BigInteger(1, Arrays.copyOfRange(calldata, offset, offset + PALAVRA)));

		if (tamanho < 0 || offset + PALAVRA + (long) tamanho > calldata.length) {
			return Optional.empty();
		}

		return Optional.of(Arrays.copyOfRange(calldata, offset + PALAVRA, offset + PALAVRA + tamanho));
	}

	private static Optional<List<byte[]>> arrayDeBytes(byte[] calldata, int offset) {

		if (offset < 0 || offset + PALAVRA > calldata.length) {
			return Optional.empty();
		}

		int count = inteiro(new BigInteger(1, Arrays.copyOfRange(calldata, offset, offset + PALAVRA)));

		if (count < 0 || count > 16 || offset + PALAVRA + (long) count * PALAVRA > calldata.length) {
			return Optional.empty();
		}

		int base = offset + PALAVRA;
		List<byte[]> itens = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			int off = base + inteiro(
					new BigInteger(1, Arrays.copyOfRange(calldata, base + i * PALAVRA, base + i * PALAVRA + PALAVRA)));
			Optional<byte[]> item = fatia(calldata, off);
			if (item.isEmpty()) {
				return Optional.empty();
			}
			itens.add(item.get());
		}

		return Optional.of(itens);
	}

	private static BigInteger palavra(byte[] dados, int base, int indice) {
		int i = base + indice * PALAVRA;
		return new BigInteger(1, Arrays.copyOfRange(dados, i, i + PALAVRA));
	}

	private static int inteiro(BigInteger valor) {
		return valor.bitLength() > 31 ? -1 : valor.intValueExact();
	}

	private static String hex(byte[] bytes, int inicio, int quantos) {
		return HEX.formatHex(bytes, inicio, inicio + quantos);
	}
}