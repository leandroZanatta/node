package br.com.node.rede;

import br.com.node.exception.PeerException;

public final class LeituraSnappy {

	private final byte[] dados;

	private int pos;

	LeituraSnappy(byte[] dados) {
		this.dados = dados;
	}

	boolean temMais() {
		return pos < dados.length;
	}

	int byteSemSinal() {

		if (pos >= dados.length) {
			throw new PeerException("Bloco Snappy truncado");
		}

		return dados[pos++] & 0xff;
	}

	int inteiroPequeno(int bytes) {

		int valor = 0;

		for (int i = 0; i < bytes; i++) {
			valor |= byteSemSinal() << (8 * i);
		}

		return valor;
	}

	int varint() {

		int valor = 0;

		for (int deslocamento = 0; deslocamento < 32; deslocamento += 7) {

			int b = byteSemSinal();

			valor |= (b & 0x7f) << deslocamento;

			if ((b & 0x80) == 0) {
				return valor;
			}
		}

		throw new PeerException("Varint Snappy longo demais");
	}

	void copiarPara(byte[] destino, int offset, int comprimento) {

		if (pos + comprimento > dados.length || offset + comprimento > destino.length) {
			throw new PeerException("Literal Snappy truncado");
		}

		System.arraycopy(dados, pos, destino, offset, comprimento);

		pos += comprimento;
	}
}