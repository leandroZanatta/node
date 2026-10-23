package br.com.node.rede;

import java.io.ByteArrayOutputStream;

import br.com.node.exception.PeerException;

public final class Snappy {

	private static final int LITERAL = 0;
	private static final int COPIA_1 = 1;
	private static final int COPIA_2 = 2;

	private static final int LITERAL_CURTO = 60;
	private static final int BLOCO_LITERAL = 1 << 16;

	private static final int MAXIMO = 16 * 1024 * 1024;

	private Snappy() {
	}

	public static byte[] descomprimir(byte[] entrada) {

		LeituraSnappy cursor = new LeituraSnappy(entrada);

		int tamanho = cursor.varint();

		if (tamanho < 0 || tamanho > MAXIMO) {
			throw new PeerException("Snappy anuncia " + tamanho + " bytes");
		}

		byte[] saida = new byte[tamanho];

		int escrito = 0;

		while (cursor.temMais()) {

			int tag = cursor.byteSemSinal();

			escrito = switch (tag & 0x03) {

			case LITERAL -> literal(cursor, tag, saida, escrito);

			case COPIA_1 ->
				copiar(saida, escrito, 4 + ((tag >>> 2) & 0x07), ((tag >>> 5) << 8) | cursor.byteSemSinal());

			case COPIA_2 -> copiar(saida, escrito, (tag >>> 2) + 1, cursor.inteiroPequeno(2));

			default -> copiar(saida, escrito, (tag >>> 2) + 1, cursor.inteiroPequeno(4));
			};
		}

		if (escrito != tamanho) {
			throw new PeerException("Snappy produziu " + escrito + " bytes, anunciou " + tamanho);
		}

		return saida;
	}

	private static int literal(LeituraSnappy cursor, int tag, byte[] saida, int escrito) {

		int comprimento = tag >>> 2;

		if (comprimento >= LITERAL_CURTO) {
			comprimento = cursor.inteiroPequeno(comprimento - 59);
		}

		comprimento++;

		cursor.copiarPara(saida, escrito, comprimento);

		return escrito + comprimento;
	}

	private static int copiar(byte[] saida, int escrito, int comprimento, int deslocamento) {

		if (deslocamento <= 0 || deslocamento > escrito) {
			throw new PeerException("Deslocamento Snappy inválido: " + deslocamento);
		}

		if (escrito + comprimento > saida.length) {
			throw new PeerException("Cópia Snappy passa do tamanho anunciado");
		}

		for (int i = 0; i < comprimento; i++) {

			saida[escrito] = saida[escrito - deslocamento];

			escrito++;
		}

		return escrito;
	}

	public static byte[] comprimir(byte[] dados) {

		ByteArrayOutputStream saida = new ByteArrayOutputStream();

		escreverVarint(saida, dados.length);

		int pos = 0;

		while (pos < dados.length) {

			int bloco = Math.min(dados.length - pos, BLOCO_LITERAL);

			escreverLiteral(saida, dados, pos, bloco);

			pos += bloco;
		}

		return saida.toByteArray();
	}

	private static void escreverLiteral(ByteArrayOutputStream saida, byte[] dados, int pos, int comprimento) {

		int codificado = comprimento - 1;

		if (codificado < LITERAL_CURTO) {

			saida.write(codificado << 2);

		} else {

			int bytes = codificado <= 0xff ? 1 : codificado <= 0xffff ? 2 : codificado <= 0xffffff ? 3 : 4;

			saida.write((59 + bytes) << 2);

			for (int i = 0; i < bytes; i++) {
				saida.write((codificado >>> (8 * i)) & 0xff);
			}
		}

		saida.write(dados, pos, comprimento);
	}

	private static void escreverVarint(ByteArrayOutputStream saida, int valor) {

		int resto = valor;

		while ((resto & ~0x7f) != 0) {

			saida.write((resto & 0x7f) | 0x80);

			resto >>>= 7;
		}

		saida.write(resto);
	}
}