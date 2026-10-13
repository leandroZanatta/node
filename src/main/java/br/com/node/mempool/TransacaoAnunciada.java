package br.com.node.mempool;

import java.util.HexFormat;

// O que a mempool nos deu de uma transação: para onde ela vai e o que ela carrega.
//
// É a metade X do par de aprendizado. O pareador casa isto com o efeito que o bloco mostrou (Y) pelo id
// da tx, e é desse par — rótulo vindo da CADEIA, não da nossa leitura — que sai o layout do contrato.
// Rótulo que viesse da nossa própria decodificação seria circular: aprenderíamos o que já supomos.
public record TransacaoAnunciada(byte[] destino, byte[] calldata) {

	public TransacaoAnunciada {

		if (destino == null || destino.length != 20) {
			throw new IllegalArgumentException("destino não é endereço de 20 bytes");
		}

		if (calldata == null || calldata.length < 4) {
			throw new IllegalArgumentException("calldata sem seletor não diz nada");
		}
	}

	/** Os quatro primeiros bytes, que dão o método — o layout é por contrato E por método. */
	public String seletor() {
		return HexFormat.of().formatHex(calldata, 0, 4);
	}
}
