package br.com.node.transformacao.catalogo;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import br.com.node.util.Keccak;
import br.com.node.vo.Log;

public final class Eventos {

	public static final byte[] SYNC_V2 = assinatura("Sync(uint112,uint112)");
	public static final byte[] SWAP_V2 = assinatura("Swap(address,uint256,uint256,uint256,uint256,address)");
	public static final byte[] SWAP_V3 = assinatura("Swap(address,address,int256,int256,uint160,uint128,int24)");

	public static final byte[] SWAP_V3_PANCAKE = assinatura(
			"Swap(address,address,int256,int256,uint160,uint128,int24,uint128,uint128)");
	public static final byte[] MINT_V3 = assinatura("Mint(address,address,int24,int24,uint128,uint256,uint256)");
	public static final byte[] BURN_V3 = assinatura("Burn(address,int24,int24,uint128,uint256,uint256)");
	public static final byte[] TRANSFER = assinatura("Transfer(address,address,uint256)");

	public static final byte[] GERENTE_V4 = java.util.HexFormat.of()
			.parseHex("28e2ea090877bf75740558f6bfb36a5ffee9e9df");
	public static final byte[] GERENTE_INFINITY_CL = java.util.HexFormat.of()
			.parseHex("a0ffb9c1ce1fe56963b0321b32e7a0302114058b");

	public static final byte[] SWAP_V4 = assinatura("Swap(bytes32,address,int128,int128,uint160,uint128,int24,uint24)");
	public static final byte[] SWAP_INFINITY_CL = assinatura(
			"Swap(bytes32,address,int128,int128,uint160,uint128,int24,uint24,uint16)");

	public static final byte[] MODIFICAR_LIQUIDEZ = assinatura(
			"ModifyLiquidity(bytes32,address,int24,int24,int256,bytes32)");

	public static final byte[] INICIALIZAR_V4 = assinatura(
			"Initialize(bytes32,address,address,uint24,int24,address,uint160,int24)");
	public static final byte[] INICIALIZAR_INFINITY_CL = assinatura(
			"Initialize(bytes32,address,address,address,uint24,bytes32,uint160,int24)");

	private Eventos() {
	}

	public static boolean ehInicializacaoDeGerente(Log log) {
		return Arrays.equals(log.endereco(), GERENTE_V4) && log.assinadoPor(INICIALIZAR_V4)
				|| Arrays.equals(log.endereco(), GERENTE_INFINITY_CL) && log.assinadoPor(INICIALIZAR_INFINITY_CL);
	}

	public static boolean ehSwapDeGerente(Log log) {
		return log.topicos().size() >= 2 && (Arrays.equals(log.endereco(), GERENTE_V4) && log.assinadoPor(SWAP_V4)
				|| Arrays.equals(log.endereco(), GERENTE_INFINITY_CL) && log.assinadoPor(SWAP_INFINITY_CL));
	}

	public static boolean ehLiquidezDeGerente(Log log) {
		return log.topicos().size() >= 2 && log.assinadoPor(MODIFICAR_LIQUIDEZ)
				&& (Arrays.equals(log.endereco(), GERENTE_V4) || Arrays.equals(log.endereco(), GERENTE_INFINITY_CL));
	}

	public static byte[] enderecoDoTopico(Log log, int indice) {

		byte[] topico = log.topicos().get(indice);

		return topico.length < 20 ? new byte[0] : Arrays.copyOfRange(topico, topico.length - 20, topico.length);
	}

	public static BigInteger palavraComSinalGrande(Log log, int indice) {

		int inicio = indice * 32;
		byte[] dados = log.dados();

		if (dados.length < inicio + 32) {
			return BigInteger.ZERO;
		}

		return new BigInteger(Arrays.copyOfRange(dados, inicio, inicio + 32));
	}

	public static BigInteger palavra(Log log, int indice) {

		int inicio = indice * 32;
		byte[] dados = log.dados();

		if (dados.length < inicio + 32) {
			return BigInteger.ZERO;
		}

		return new BigInteger(1, Arrays.copyOfRange(dados, inicio, inicio + 32));
	}

	public static long palavraComSinal(Log log, int indice) {

		int inicio = indice * 32;
		byte[] dados = log.dados();

		if (dados.length < inicio + 32) {
			return 0;
		}

		return new BigInteger(Arrays.copyOfRange(dados, inicio, inicio + 32)).longValueExact();
	}

	public static BigInteger liquidezMovida(Log log) {
		return log.assinadoPor(MINT_V3) ? palavra(log, 1) : palavra(log, 0).negate();
	}

	public static boolean ehSwapV3(Log log) {
		return log.assinadoPor(SWAP_V3) || log.assinadoPor(SWAP_V3_PANCAKE);
	}

	public static long tickDoTopico(Log log, int indice) {

		byte[] topico = log.topicos().get(indice);

		return new BigInteger(topico).longValueExact();
	}

	public static byte[] assinatura(String texto) {
		return Keccak.hash(texto.getBytes(StandardCharsets.US_ASCII));
	}
}