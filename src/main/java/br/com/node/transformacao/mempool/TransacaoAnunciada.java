package br.com.node.transformacao.mempool;

import java.util.HexFormat;

public record TransacaoAnunciada(byte[] destino, byte[] calldata) {

	public TransacaoAnunciada {

		if (destino == null || destino.length != 20) {
			throw new IllegalArgumentException("destino não é endereço de 20 bytes");
		}

		if (calldata == null || calldata.length < 4) {
			throw new IllegalArgumentException("calldata sem seletor não diz nada");
		}
	}

	/**
	 * Os quatro primeiros bytes, que dão o método — o layout é por contrato E por
	 * método.
	 */
	public String seletor() {
		return HexFormat.of().formatHex(calldata, 0, 4);
	}
}