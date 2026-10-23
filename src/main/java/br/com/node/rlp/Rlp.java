package br.com.node.rlp;

import java.util.Arrays;
import java.util.List;

import br.com.node.exception.RlpException;

public final class Rlp {

	private static final int LIMITE_CURTO = 55;

	private Rlp() {
	}

	public static RlpItem bytes(byte[] valor) {
		return new RlpBytes(valor);
	}

	public static RlpItem inteiro(long valor) {

		if (valor < 0) {
			throw new RlpException("RLP não codifica inteiro negativo: " + valor);
		}

		if (valor == 0) {
			return new RlpBytes(new byte[0]);
		}

		byte[] temporario = new byte[8];

		int i = 8;

		for (long v = valor; v != 0; v >>>= 8) {
			temporario[--i] = (byte) (v & 0xff);
		}

		return new RlpBytes(Arrays.copyOfRange(temporario, i, 8));
	}

	public static RlpItem inteiro(java.math.BigInteger valor) {

		byte[] bytes = valor.toByteArray();

		int inicio = 0;

		while (inicio < bytes.length && bytes[inicio] == 0) {
			inicio++;
		}

		return new RlpBytes(java.util.Arrays.copyOfRange(bytes, inicio, bytes.length));
	}

	public static RlpItem lista(RlpItem... itens) {
		return new RlpLista(List.of(itens));
	}

	public static RlpItem lista(List<RlpItem> itens) {
		return new RlpLista(List.copyOf(itens));
	}

	public static byte[] paraBytes(RlpItem item) {

		if (!(item instanceof RlpBytes valor)) {
			throw new RlpException("Esperava bytes, veio lista");
		}

		return valor.valor();
	}

	public static long paraInteiro(RlpItem item) {

		byte[] valor = paraBytes(item);

		if (valor.length > 8) {
			throw new RlpException("Inteiro RLP maior que 8 bytes: " + valor.length);
		}

		if (valor.length > 1 && valor[0] == 0) {
			throw new RlpException("Inteiro RLP com zero à esquerda");
		}

		long resultado = 0;

		for (byte b : valor) {
			resultado = (resultado << 8) | (b & 0xff);
		}

		return resultado;
	}

	public static List<RlpItem> paraLista(RlpItem item) {

		if (!(item instanceof RlpLista lista)) {
			throw new RlpException("Esperava lista, veio bytes");
		}

		return lista.itens();
	}

	public static byte[] codificar(RlpItem item) {

		byte[] saida = new byte[tamanhoDe(item)];

		escrever(item, saida, 0);

		return saida;
	}

	private static int tamanhoDe(RlpItem item) {

		if (item instanceof RlpBytes valor) {

			int tamanho = valor.valor().length;

			if (tamanho == 1 && (valor.valor()[0] & 0xff) < 0x80) {
				return 1;
			}

			return cabecalho(tamanho) + tamanho;
		}

		int carga = 0;

		for (RlpItem filho : ((RlpLista) item).itens()) {
			carga += tamanhoDe(filho);
		}

		return cabecalho(carga) + carga;
	}

	private static int cabecalho(int carga) {
		return carga <= LIMITE_CURTO ? 1 : 1 + bytesDoTamanho(carga);
	}

	private static int bytesDoTamanho(int valor) {

		int bytes = 0;

		for (int v = valor; v != 0; v >>>= 8) {
			bytes++;
		}

		return bytes;
	}

	private static int escrever(RlpItem item, byte[] destino, int onde) {

		if (item instanceof RlpBytes valor) {

			byte[] dados = valor.valor();

			if (dados.length == 1 && (dados[0] & 0xff) < 0x80) {

				destino[onde] = dados[0];

				return onde + 1;
			}

			onde = escreverCabecalho(destino, onde, dados.length, 0x80);

			System.arraycopy(dados, 0, destino, onde, dados.length);

			return onde + dados.length;
		}

		/*
		 * A carga é medida de novo aqui, e não guardada da primeira passada.
		 *
		 * Guardá-la exigiria um mapa por nó — alocação para evitar alocação. A
		 * recontagem é aritmética sobre uma árvore que já está em memória, e o perfil
		 * mostrou o custo no `byte[]`, não na contagem.
		 */
		int carga = 0;

		for (RlpItem filho : ((RlpLista) item).itens()) {
			carga += tamanhoDe(filho);
		}

		onde = escreverCabecalho(destino, onde, carga, 0xc0);

		for (RlpItem filho : ((RlpLista) item).itens()) {
			onde = escrever(filho, destino, onde);
		}

		return onde;
	}

	private static int escreverCabecalho(byte[] destino, int onde, int tamanho, int base) {

		if (tamanho <= LIMITE_CURTO) {

			destino[onde] = (byte) (base + tamanho);

			return onde + 1;
		}

		int bytes = bytesDoTamanho(tamanho);

		destino[onde] = (byte) (base + LIMITE_CURTO + bytes);

		for (int i = 0; i < bytes; i++) {
			destino[onde + 1 + i] = (byte) (tamanho >>> (8 * (bytes - 1 - i)));
		}

		return onde + 1 + bytes;
	}

	public static RlpItem decodificar(byte[] dados) {

		RlpLeitor leitor = new RlpLeitor(dados);

		RlpItem item = leitor.ler();

		if (leitor.posicao() != dados.length) {
			throw new RlpException("Sobraram " + (dados.length - leitor.posicao()) + " bytes depois do item RLP");
		}

		return item;
	}
}