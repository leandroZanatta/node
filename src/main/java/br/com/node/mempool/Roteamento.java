package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import br.com.node.catalogo.Catalogo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Passo;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.ReservasV2;
import br.com.node.vo.SwapPendente;

// Decodifica a calldata de um swap de roteador em uma intenção projetável.
//
// A mempool nos entrega transações pendentes cruas (`Transactions`), e a maioria do fluxo que abre
// fenda é `swap...` de roteador. Aqui lê-se a intenção — quanto entra e por qual caminho de tokens —
// para projetar o efeito antes do bloco. A saída real não vem daqui: vem de aplicar a curva de cada
// pool; isto só diz o que o usuário vai empurrar.
//
// SÓ OS MÉTODOS token->token de entrada exata, que têm layout ABI idêntico:
//   swapExactTokensForTokens(uint,uint,address[],address,uint)
//   ...SupportingFeeOnTransferTokens(idem)
// Os de ETH (entrada em `value`, fora da calldata) e de saída exata ficam para depois — cada um é um
// layout, e decodificar errado é pior que não decodificar: projeta uma fenda que não existe.
//
// CALLDATA É NÃO CONFIÁVEL: vem de terceiro, pode ser truncada ou mentirosa. Toda leitura confere
// limite antes, e o que não fecha vira vazio — nunca exceção que derrube quem chama.
public final class Roteamento {

	// Métodos de router com o MESMO layout: (amountIn, amountOutMin, path[], to, deadline) — amountIn
	// na palavra 0, offset do path na palavra 2. Token->token e token->ETH movem os pools igual (o ETH
	// é só o WBNB desembrulhado no fim), então a projeção é a mesma. As variantes fee-on-transfer têm
	// layout idêntico. NÃO entram aqui os ETH->token (entrada no `value`, fora do calldata) nem os de
	// saída exata (amountOut na palavra 0) — layout diferente, decodificar errado projeta fenda falsa.
	private static final Set<String> ENTRADA_EXATA = Set.of(
			"38ed1739",  // swapExactTokensForTokens
			"5c11d795",  // swapExactTokensForTokensSupportingFeeOnTransferTokens
			"18cbafe5",  // swapExactTokensForETH
			"791ac947"); // swapExactTokensForETHSupportingFeeOnTransferTokens

	// Variante com address extra no início: (address, amountIn, amountOutMin, path[], to, deadline, address).
	// amountIn na palavra 1, offset do path na palavra 3. Verificado em `d8396489`.
	private static final Set<String> ENTRADA_EXATA_COM_PREFIXO = Set.of(
			"d8396489"); // swapExactTokensForTokens(address,uint256,uint256,address[],address,uint256,address)

	// ETH-in: o amountIn é o `value` da tx (fora do calldata), e o path começa no WORD 1, não no 2 —
	// não há palavra de amountIn na frente. `path[0]` é o WBNB (o BNB embrulhado), então tocam pools
	// WBNB direto: recall alinhado aos nossos ciclos enraizados em WBNB. Layout:
	//   swapExactETHForTokens(amountOutMin, path[], to, deadline)
	private static final Set<String> ENTRADA_ETH = Set.of(
			"7ff36ab5",  // swapExactETHForTokens
			"b6f9de95"); // swapExactETHForTokensSupportingFeeOnTransferTokens

	private static final int PALAVRA = 32;
	private static final int SELETOR = 4;

	private Roteamento() {
	}

	// Overload sem valor: só decodifica os token->token (o `value` não importa neles). Mantido para
	// chamadores que não têm a tx à mão (testes de layout token-in).
	public static Optional<SwapPendente> decodificar(byte[] calldata) {
		return decodificar(calldata, null);
	}

	// `valor` é o `value` da tx pendente — o amountIn dos swaps ETH-in. Nos token->token é ignorado.
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

			// palavra 0 = amountIn; palavra 2 = offset do array `path`.
			int offsetDoCaminho = SELETOR + inteiro(palavra(calldata, 2));

			return caminho(calldata, offsetDoCaminho).filter(c -> c.size() >= 2)
					.map(c -> new SwapPendente(palavra(calldata, 0), c));
		}

		if (ENTRADA_EXATA_COM_PREFIXO.contains(seletor)) {

			if (calldata.length < SELETOR + PALAVRA * 4) {
				return Optional.empty();
			}

			// palavra 0 = address extra (ignorado); palavra 1 = amountIn; palavra 3 = offset do path.
			int offsetDoCaminho = SELETOR + inteiro(palavra(calldata, 3));

			return caminho(calldata, offsetDoCaminho).filter(c -> c.size() >= 2)
					.map(c -> new SwapPendente(palavra(calldata, 1), c));
		}

		if (ENTRADA_ETH.contains(seletor) && valor != null && valor.signum() > 0) {

			// amountIn = value (fora do calldata); palavra 1 = offset do array `path`.
			int offsetDoCaminho = SELETOR + inteiro(palavra(calldata, 1));

			return caminho(calldata, offsetDoCaminho).filter(c -> c.size() >= 2)
					.map(c -> new SwapPendente(valor, c));
		}

		return Optional.empty();
	}

	// Mapeia cada par consecutivo do caminho para a pool conhecida que liga esses dois tokens.
	// Se alguma perna não tem pool conhecida, devolve vazio — é melhor recusar a projeção
	// do que inventar uma pool que não existe no catálogo.
	// A factory de cada roteador V2 conhecido — perguntada ao próprio contrato (`factory()`) em 24/09.
	// O roteador só troca nos pares DELA, e o endereço do par sai por CREATE2: não há o que escolher.
	private static final java.util.HexFormat HEX = java.util.HexFormat.of();

	private static final java.util.Map<String, byte[]> FACTORY_DO_ROTEADOR = java.util.Map.of(
			"10ed43c718714eb63d5aa57b78b54704e256024e", hexa("ca143ce32fe78f1f7019d7d551a6402fc5350c73"), // PancakeSwap V2
			"05ff2b0db69458a0750badebc4f9e13add608c7f", hexa("bcfccbde45ce874adcb698cc183debcf17952812"), // PancakeSwap V1
			"3a6d8ca21d1cf76f653a67577fa0d27453350dd8", hexa("858e3312ed3a876947ea49d572a7c42de08af7ee"), // Biswap
			"cf0febd3f17cef5b47b0cd257acf6025c5bff3b7", hexa("0841bd0b734e4f5853f0dd8d7ea041c241fb0da6"), // ApeSwap
			"7dae51bd3e3376b8c7c4900e9107f12be3af1ba8", hexa("3cd1c46068daea5ebb0d3f55f6915b10648062b8"), // MDEX
			"1b02da8cb0d097eb8d57a175b88c7d8b47997506", hexa("c35dadb65012ec5796536bd9864ed8773abc74c4")); // SushiSwap

	private static byte[] hexa(String hex) {
		return HEX.parseHex(hex);
	}

	// Sem saber o roteador: só pools declaradas no swap, ou a ÚNICA pool do par no catálogo.
	public static List<byte[]> mapearParaPools(SwapPendente swap, Catalogo catalogo) {
		return mapearParaPools(swap, catalogo, null);
	}

	// A pool de cada perna, na ordem — ou vazio se alguma não se sabe.
	//
	// CICATRIZ de 24/09: aqui era "a PRIMEIRA pool do catálogo que tem esse par". WBNB/USDT tem dezenas
	// de pools (Pancake, Biswap, Ape, forks sem nome), e o swap que ia pela Pancake era projetado numa
	// pool qualquer: o preço dela se movia no modelo e não na cadeia, e o grafo via uma "fenda" entre ela
	// e as outras. Medido na noite: melhor retorno das projeções N+1 com mediana de +314% e máximo
	// infinito. Agora a pool vem de quem SABE, nesta ordem:
	//   1. as pools declaradas no próprio swap (o layout aprendido que as leu na calldata);
	//   2. o par da factory do roteador, por CREATE2 — tem de estar no catálogo;
	//   3. a única pool do par no catálogo. Mais de uma, recusa: projetar na errada é pior que não projetar.
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

		byte[] factory = roteador == null ? null
				: FACTORY_DO_ROTEADOR.get(HEX.formatHex(roteador));

		List<byte[]> pools = new ArrayList<>();

		for (int i = 0; i < pernas; i++) {
			byte[] token0 = swap.caminho().get(i);
			byte[] token1 = swap.caminho().get(i + 1);

			Optional<byte[]> pool;

			if (factory != null) {
				pool = br.com.node.catalogo.Fabricas.parV2(factory, token0, token1)
						.filter(endereco -> catalogo.par(endereco).map(par -> pareia(par, token0, token1)).orElse(false));
			} else {
				List<byte[]> candidatas = catalogo.poolsDoPar(token0, token1).stream()
						.filter(endereco -> catalogo.conhece(endereco)).limit(2).toList();
				if (candidatas.size() == 1) {
					pool = Optional.of(candidatas.get(0));
				} else if (!candidatas.isEmpty()) {
					List<Optional<byte[]>> porFactory = FACTORY_DO_ROTEADOR.values().stream()
							.map(f -> br.com.node.catalogo.Fabricas.parV2(f, token0, token1)
									.filter(addr -> catalogo.par(addr).map(par -> pareia(par, token0, token1)).orElse(false)))
							.filter(Optional::isPresent)
							.limit(2).toList();
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

	// Projeta o efeito de um swap pendente sobre o estado conhecido de cada pool no caminho.
	// Aceita a taxa da pool como função: em produção ela vem do Tarifario; aqui, o teste fornece
	// a taxa fixa para manter o caso pequeno e determinístico.
	public static Optional<List<Passo>> projetar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool) {
		return projetar(swap, catalogo, taxaDaPool, null, pool -> false);
	}

	public static Optional<List<Passo>> projetar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool, byte[] roteador) {
		return projetar(swap, catalogo, taxaDaPool, roteador, pool -> false);
	}

	// `solidlyStable`: true quando a pool usa a curva estável (x³y+xy³=k) — cria PoolSolidlyStable.
	public static Optional<List<Passo>> projetar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool, byte[] roteador,
			Function<byte[], Boolean> solidlyStable) {

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

			br.com.node.nucleo.Pool atual = solidlyStable.apply(pool)
					? new br.com.node.nucleo.PoolSolidlyStable(estado.get().reserva0(), estado.get().reserva1(), taxa.get())
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

	// No offset: uma palavra de tamanho, e então `tamanho` endereços (12 bytes de zero + 20 do endereço).
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

	// O offset/tamanho cabe num int em qualquer calldata sã; um valor absurdo (calldata mentirosa)
	// estoura aqui e o chamador trata como não decodificável.
	private static int inteiro(BigInteger valor) {
		return valor.bitLength() > 31 ? -1 : valor.intValueExact();
	}

	private static String hex(byte[] bytes, int inicio, int quantos) {
		return HEX.formatHex(bytes, inicio, inicio + quantos);
	}
}
