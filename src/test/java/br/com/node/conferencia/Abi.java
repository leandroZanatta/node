package br.com.node.conferencia;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import br.com.node.util.Keccak;

// Escreve e lê palavras de ABI: seletor, inteiro, endereço, inteiro com sinal.
//
// Existe para a conferência ter UMA leitura de ABI: com o Multicall3 entrando, a mesma codificação
// passaria a morar em três classes, e codificação duplicada é onde uma cópia diverge da outra em
// silêncio — o instrumento lendo errado e chamando isso de divergência do node.
public final class Abi {

	private Abi() {
	}

	// Calculado do texto, nunca copiado em hex — o mesmo princípio de `Eventos`.
	// O seletor em bytes, para montar calldata concatenando palavras.
	public static byte[] seletorBytes(String assinatura) {
		return java.util.Arrays.copyOf(Keccak.hash(assinatura.getBytes(StandardCharsets.US_ASCII)), 4);
	}

	public static String seletor(String assinatura) {
		return "0x" + HexFormat.of().formatHex(Keccak.hash(assinatura.getBytes(StandardCharsets.US_ASCII))).substring(0, 8);
	}

	public static String palavra(BigInteger valor) {
		return String.format("%64s", valor.toString(16)).replace(' ', '0');
	}

	public static BigInteger palavra(String retorno, int indice) {
		return new BigInteger(retorno.substring(2 + indice * 64, 2 + indice * 64 + 64), 16);
	}

	// Complemento de dois sobre a palavra inteira: é assim que o ABI estende o sinal de int24.
	public static int comSinal(String retorno, int indice) {
		return new BigInteger(HexFormat.of().parseHex(retorno.substring(2 + indice * 64, 2 + indice * 64 + 64)))
				.intValueExact();
	}

	public static String endereco(String retorno) {
		return retorno.substring(2 + 24, 2 + 64).toLowerCase();
	}

	public static String hex(byte[] endereco) {
		return "0x" + HexFormat.of().formatHex(endereco);
	}
}
