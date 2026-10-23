package br.com.node.ensaio;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import br.com.node.util.Keccak;

public final class AbiDoExecutor {

	public static final byte[] EXECUTAR = seletor("executar((address,address,address,uint8,uint16)[],uint256,uint256)");

	public static final byte[] EXECUTAR_COM_EMPRESTIMO = seletor(
			"executarComEmprestimo((address,address,address,uint8,uint16)[],uint256,uint256)");

	public static final byte[] EXECUTAR_V2 = seletor(
			"executar((address,address,address,uint8,uint16,bytes)[],uint256,uint256)");

	public static final byte[] EXECUTAR_COM_EMPRESTIMO_V2 = seletor(
			"executarComEmprestimo((address,address,address,uint8,uint16,bytes)[],uint256,uint256)");

	public static final byte[] VERSAO = seletor("versao()");

	private AbiDoExecutor() {
	}

	public static byte[] executarV2(List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {
		return encaixarV2(EXECUTAR_V2, rota, montante, lucroMinimo);
	}

	public static byte[] executarComEmprestimoV2(List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {
		return encaixarV2(EXECUTAR_COM_EMPRESTIMO_V2, rota, montante, lucroMinimo);
	}

	private static byte[] encaixarV2(byte[] seletor, List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {

		if (rota.isEmpty()) {
			throw new IllegalArgumentException("rota vazia não vira chamada");
		}

		if (montante.bitLength() > 256 || lucroMinimo.bitLength() > 256) {
			throw new IllegalArgumentException("montante ou lucro mínimo acima de 256 bits não se trunca, se recusa");
		}

		java.io.ByteArrayOutputStream elementos = new java.io.ByteArrayOutputStream();
		int n = rota.size();
		int[] offsets = new int[n];

		for (int i = 0; i < n; i++) {

			Salto t = rota.get(i);
			offsets[i] = n * 32 + elementos.size();

			elementos.writeBytes(palavra(hex(t.pool())));
			elementos.writeBytes(palavra(hex(t.entra())));
			elementos.writeBytes(palavra(hex(t.sai())));
			elementos.writeBytes(palavra(t.tipo()));
			elementos.writeBytes(palavra(t.taxaBps()));
			elementos.writeBytes(palavra(6 * 32));
			elementos.writeBytes(palavra(t.chave().length));
			elementos.writeBytes(Arrays.copyOf(t.chave(), (t.chave().length + 31) / 32 * 32));
		}

		java.io.ByteArrayOutputStream calldata = new java.io.ByteArrayOutputStream();

		calldata.writeBytes(seletor);
		calldata.writeBytes(palavra(0x60));
		calldata.writeBytes(palavra(montante));
		calldata.writeBytes(palavra(lucroMinimo));
		calldata.writeBytes(palavra(n));

		for (int offset : offsets) {
			calldata.writeBytes(palavra(offset));
		}

		calldata.writeBytes(elementos.toByteArray());

		return calldata.toByteArray();
	}

	public static byte[] executar(List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {
		return encaixar(EXECUTAR, rota, montante, lucroMinimo);
	}

	public static byte[] executarComEmprestimo(List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {
		return encaixar(EXECUTAR_COM_EMPRESTIMO, rota, montante, lucroMinimo);
	}

	private static byte[] encaixar(byte[] seletor, List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {

		if (rota.isEmpty()) {
			throw new IllegalArgumentException("rota vazia não vira chamada");
		}

		if (montante.bitLength() > 256 || lucroMinimo.bitLength() > 256) {
			throw new IllegalArgumentException("montante ou lucro mínimo acima de 256 bits não se trunca, se recusa");
		}

		int n = rota.size();

		byte[] calldata = new byte[4 + 96 + 32 + n * 160];

		copiar(calldata, 0, seletor);
		copiar(calldata, 4, palavra(0x60));
		copiar(calldata, 36, palavra(montante));
		copiar(calldata, 68, palavra(lucroMinimo));

		copiar(calldata, 100, palavra(n));

		for (int i = 0; i < n; i++) {

			Salto t = rota.get(i);
			int base = 132 + i * 160;

			copiar(calldata, base, palavra(hex(t.pool())));
			copiar(calldata, base + 32, palavra(hex(t.entra())));
			copiar(calldata, base + 64, palavra(hex(t.sai())));
			copiar(calldata, base + 96, palavra(t.tipo()));
			copiar(calldata, base + 128, palavra(t.taxaBps()));
		}

		return calldata;
	}

	public static byte[] seletor(String assinatura) {
		return Arrays.copyOf(Keccak.hash(assinatura.getBytes(StandardCharsets.US_ASCII)), 4);
	}

	private static byte[] palavra(BigInteger valor) {

		byte[] cru = valor.toByteArray();
		byte[] palavra = new byte[32];
		int copiar = Math.min(cru.length, 32);

		System.arraycopy(cru, cru.length - copiar, palavra, 32 - copiar, copiar);

		return palavra;
	}

	private static byte[] palavra(byte[] valores) {

		byte[] palavra = new byte[32];

		System.arraycopy(valores, 0, palavra, 32 - valores.length, valores.length);

		return palavra;
	}

	private static byte[] palavra(int valor) {
		return palavra(BigInteger.valueOf(valor));
	}

	private static byte[] hex(String hexadecimal) {
		String semPrefixo = hexadecimal.startsWith("0x") ? hexadecimal.substring(2) : hexadecimal;
		return java.util.HexFormat.of().parseHex(semPrefixo);
	}

	private static void copiar(byte[] destino, int desde, byte[] fonte) {
		System.arraycopy(fonte, 0, destino, desde, fonte.length);
	}
}