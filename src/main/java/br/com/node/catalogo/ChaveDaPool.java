package br.com.node.catalogo;

import java.util.Arrays;
import java.util.Optional;

import br.com.node.vo.Log;

// O `PoolKey` de uma pool que mora num gerente (Uniswap V4, PancakeSwap Infinity CL): o que o `Swap` não
// diz — os dois tokens, o espaçamento e os hooks. Sai do `Initialize`, emitido UMA vez, quando a pool
// nasce; por isso a varredura histórica existe.
//
// `taxaLp` é a taxa de IDENTIDADE, não a cobrada. Medido em 25/09: em 642 de 761 swaps de pools sem hook
// a taxa cobrada era maior (1 → 2, 75 → 95, 355 → 449), porque a de protocolo entra por cima. Quem cota
// usa a do `Swap` (`EstadoNoGerente.taxa`); esta só diz se a taxa é fixa ou dinâmica.
//
// Token zero é o BNB NATIVO, não o WBNB: as duas coisas são tokens diferentes para o gerente, e ligar
// uma à outra é trabalho do Executor (embrulhar/desembrulhar), não do catálogo.
public record ChaveDaPool(byte[] gerente, byte[] poolId, byte[] token0, byte[] token1, int taxaLp, int espacamento,
		byte[] hooks, int permissoes, long altura) {

	// A taxa que diz "o hook decide a cada swap". Igual nos dois gerentes.
	public static final int TAXA_DINAMICA = 0x800000;

	private static final byte[] SEM_HOOK = new byte[20];

	// Os bits de permissão que deixam o hook mexer no swap: antes/depois do swap e os dois "devolve
	// delta". Na V4 moram nos 14 bits baixos do ENDEREÇO do hook; no Infinity, no mapa de bits de
	// `parameters`, em outras posições.
	private static final int SWAP_V4 = 1 << 7 | 1 << 6 | 1 << 3 | 1 << 2;
	private static final int SWAP_INFINITY = 1 << 6 | 1 << 7 | 1 << 10 | 1 << 11;

	// O que dá para modelar HOJE com a matemática provada (`GerenteTest`): pool SEM hook e de taxa fixa.
	// Decisão de 25/09, com número: na V4, 549 de 555 swaps da amostra eram em pool sem hook; no
	// Infinity só 212 de 910 — o resto passa por hooks com `beforeSwap` que não mexem no montante mas
	// podem REVERTER para quem chama (anti-MEV, lista de permissão). Esses entram quando uma simulação
	// pelo Executor provar que passam; até lá, fora.
	public boolean modelavel() {
		return taxaLp != TAXA_DINAMICA && Arrays.equals(hooks, SEM_HOOK);
	}

	// Hook presente mas sem nenhuma permissão de swap: o montante não muda. Contado à parte para a
	// próxima decisão, não usado para modelar.
	public boolean hookForaDoSwap() {
		return !Arrays.equals(hooks, SEM_HOOK)
				&& (permissoes & (Arrays.equals(gerente, Eventos.GERENTE_V4) ? SWAP_V4 : SWAP_INFINITY)) == 0;
	}

	// O `PoolKey` como o Executor o recebe e o gerente o hasheia: `abi.encode` da struct do gerente. O BNB
	// nativo vai como endereço zero, que é o que ele é para o gerente.
	//   V4:       moeda0, moeda1, taxa, espaçamento, hooks
	//   Infinity: moeda0, moeda1, hooks, gerente, taxa, parameters (espaçamento << 16 | permissões)
	public byte[] abi() {

		boolean v4 = Arrays.equals(gerente, Eventos.GERENTE_V4);
		byte[] saida = new byte[(v4 ? 5 : 6) * 32];

		palavra(saida, 0, token0);
		palavra(saida, 1, token1);

		if (v4) {
			palavra(saida, 2, java.math.BigInteger.valueOf(taxaLp));
			palavra(saida, 3, java.math.BigInteger.valueOf(espacamento));
			palavra(saida, 4, hooks);
		} else {
			palavra(saida, 2, hooks);
			palavra(saida, 3, gerente);
			palavra(saida, 4, java.math.BigInteger.valueOf(taxaLp));
			palavra(saida, 5, java.math.BigInteger.valueOf((long) (espacamento & 0xffffff) << 16 | permissoes));
		}

		return saida;
	}

	// A chave reconstruída tem de dar o PRÓPRIO `poolId`: é assim que o gerente acha a pool. Se não der, um
	// campo foi lido ou remontado errado, e a perna não se monta — mandar ao gerente uma chave de outra
	// pool (ou de nenhuma) é reverter no melhor caso.
	public boolean confere() {
		return Arrays.equals(br.com.node.util.Keccak.hash(abi()), poolId);
	}

	private static void palavra(byte[] destino, int indice, byte[] endereco) {
		System.arraycopy(endereco, 0, destino, indice * 32 + 32 - endereco.length, endereco.length);
	}

	// Com extensão de sinal: o espaçamento é int24 e o ABI o põe numa palavra inteira.
	private static void palavra(byte[] destino, int indice, java.math.BigInteger valor) {

		byte[] cru = valor.toByteArray();
		byte preenchimento = (byte) (valor.signum() < 0 ? 0xff : 0x00);

		Arrays.fill(destino, indice * 32, indice * 32 + 32, preenchimento);
		System.arraycopy(cru, Math.max(0, cru.length - 32), destino, indice * 32 + 32 - Math.min(32, cru.length),
				Math.min(32, cru.length));
	}

	// Lê o `Initialize` dos dois gerentes, que têm corpos DIFERENTES:
	//   V4:       fee, tickSpacing, hooks, sqrtPriceX96, tick
	//   Infinity: hooks, fee, parameters (mapa de bits nos 16 baixos, tickSpacing nos 24 seguintes), …
	// Conferido em 25/09 contra a cadeia: nos 92 `ModifyLiquidity` da amostra os dois ticks eram
	// múltiplos do espaçamento lido daqui.
	public static Optional<ChaveDaPool> doInitialize(Log log, long altura) {

		if (!Eventos.ehInicializacaoDeGerente(log) || log.topicos().size() < 4 || log.dados().length < 3 * 32) {
			return Optional.empty();
		}

		boolean v4 = Arrays.equals(log.endereco(), Eventos.GERENTE_V4);

		byte[] token0 = Eventos.enderecoDoTopico(log, 2);
		byte[] token1 = Eventos.enderecoDoTopico(log, 3);

		try {

			if (v4) {

				int taxa = Eventos.palavra(log, 0).intValueExact();
				int espacamento = (int) Eventos.palavraComSinal(log, 1);
				byte[] hooks = enderecoDaPalavra(log, 2);
				int permissoes = ((hooks[18] & 0xff) << 8 | hooks[19] & 0xff) & 0x3fff;

				return valida(new ChaveDaPool(log.endereco(), log.topicos().get(1), token0, token1, taxa, espacamento,
						hooks, permissoes, altura));
			}

			byte[] hooks = enderecoDaPalavra(log, 0);
			int taxa = Eventos.palavra(log, 1).intValueExact();
			long parametros = Eventos.palavra(log, 2).longValue();
			int permissoes = (int) (parametros & 0xffff);
			int espacamento = (int) (parametros >> 16 & 0xffffff);
			espacamento = espacamento >= 1 << 23 ? espacamento - (1 << 24) : espacamento;

			return valida(new ChaveDaPool(log.endereco(), log.topicos().get(1), token0, token1, taxa, espacamento, hooks,
					permissoes, altura));

		} catch (ArithmeticException impossivel) {
			return Optional.empty();
		}
	}

	// Espaçamento fora de [1, 32767] ou taxa acima de 100% sem ser a dinâmica: não é pool que o gerente
	// aceitaria criar, é payload que não entendemos.
	private static Optional<ChaveDaPool> valida(ChaveDaPool chave) {

		boolean taxaOk = chave.taxaLp == TAXA_DINAMICA || chave.taxaLp >= 0 && chave.taxaLp < 1_000_000;

		return chave.espacamento >= 1 && chave.espacamento <= Short.MAX_VALUE && taxaOk ? Optional.of(chave)
				: Optional.empty();
	}

	private static byte[] enderecoDaPalavra(Log log, int indice) {

		int fim = (indice + 1) * 32;

		return Arrays.copyOfRange(log.dados(), fim - 20, fim);
	}
}
