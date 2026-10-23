package br.com.node.rlp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.com.node.exception.RlpException;

public class RlpLeitor {

	private static final int LIMITE_CURTO = 55;

	private final byte[] dados;

	private int pos;

	public RlpLeitor(byte[] dados) {
		this.dados = dados;
	}

	public int posicao() {
		return pos;
	}

	public RlpItem ler() {

		int prefixo = proximoByte();

		if (prefixo <= 0x7f) {
			return new RlpBytes(new byte[] { (byte) prefixo });
		}

		if (prefixo <= 0xb7) {
			return new RlpBytes(fatiar(prefixo - 0x80));
		}

		if (prefixo <= 0xbf) {
			return new RlpBytes(fatiar(lerTamanho(prefixo - 0xb7)));
		}

		if (prefixo <= 0xf7) {
			return lerLista(prefixo - 0xc0);
		}

		return lerLista(lerTamanho(prefixo - 0xf7));
	}

	private RlpLista lerLista(int tamanho) {

		int fim = exigirDisponivel(tamanho);

		List<RlpItem> itens = new ArrayList<>();

		while (pos < fim) {
			itens.add(ler());
		}

		if (pos != fim) {
			throw new RlpException("Lista RLP ultrapassou o próprio tamanho");
		}

		return new RlpLista(itens);
	}

	private int lerTamanho(int bytesDoTamanho) {

		if (bytesDoTamanho < 1 || bytesDoTamanho > 4) {
			throw new RlpException("Tamanho RLP com " + bytesDoTamanho + " bytes");
		}

		int fim = exigirDisponivel(bytesDoTamanho);

		if (dados[pos] == 0) {
			throw new RlpException("Tamanho RLP com zero à esquerda");
		}

		int tamanho = 0;

		while (pos < fim) {
			tamanho = (tamanho << 8) | (dados[pos++] & 0xff);
		}

		if (tamanho <= LIMITE_CURTO) {
			throw new RlpException("Tamanho RLP não-canônico: " + tamanho + " cabia na forma curta");
		}

		return tamanho;
	}

	private byte[] fatiar(int tamanho) {

		int fim = exigirDisponivel(tamanho);

		byte[] recorte = Arrays.copyOfRange(dados, pos, fim);

		pos = fim;

		return recorte;
	}

	private int proximoByte() {

		if (pos >= dados.length) {
			throw new RlpException("RLP truncado");
		}

		return dados[pos++] & 0xff;
	}

	private int exigirDisponivel(int tamanho) {

		if (tamanho < 0 || pos + tamanho > dados.length) {
			throw new RlpException("RLP truncado: faltam bytes para " + tamanho);
		}

		return pos + tamanho;
	}
}