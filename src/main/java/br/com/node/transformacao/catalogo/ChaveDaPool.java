package br.com.node.transformacao.catalogo;

import java.util.Arrays;
import java.util.Optional;

import br.com.node.vo.Log;

public record ChaveDaPool(byte[] gerente, byte[] poolId, byte[] token0, byte[] token1, int taxaLp, int espacamento,
		byte[] hooks, int permissoes, long altura) {

	public static final int TAXA_DINAMICA = 0x800000;

	private static final byte[] SEM_HOOK = new byte[20];

	private static final int SWAP_V4 = 1 << 7 | 1 << 6 | 1 << 3 | 1 << 2;
	private static final int SWAP_INFINITY = 1 << 6 | 1 << 7 | 1 << 10 | 1 << 11;

	public boolean modelavel() {
		return taxaLp != TAXA_DINAMICA && Arrays.equals(hooks, SEM_HOOK);
	}

	public boolean hookForaDoSwap() {
		return !Arrays.equals(hooks, SEM_HOOK)
				&& (permissoes & (Arrays.equals(gerente, Eventos.GERENTE_V4) ? SWAP_V4 : SWAP_INFINITY)) == 0;
	}

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

	public boolean confere() {
		return Arrays.equals(br.com.node.util.Keccak.hash(abi()), poolId);
	}

	private static void palavra(byte[] destino, int indice, byte[] endereco) {
		System.arraycopy(endereco, 0, destino, indice * 32 + 32 - endereco.length, endereco.length);
	}

	private static void palavra(byte[] destino, int indice, java.math.BigInteger valor) {

		byte[] cru = valor.toByteArray();
		byte preenchimento = (byte) (valor.signum() < 0 ? 0xff : 0x00);

		Arrays.fill(destino, indice * 32, indice * 32 + 32, preenchimento);
		System.arraycopy(cru, Math.max(0, cru.length - 32), destino, indice * 32 + 32 - Math.min(32, cru.length),
				Math.min(32, cru.length));
	}

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

			return valida(new ChaveDaPool(log.endereco(), log.topicos().get(1), token0, token1, taxa, espacamento,
					hooks, permissoes, altura));

		} catch (ArithmeticException impossivel) {
			return Optional.empty();
		}
	}

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