package br.com.node.vo;

import java.util.List;

// Um log de recibo: quem emitiu, os tópicos indexados e o corpo.
//
// O tópico zero é o hash keccak da assinatura do evento — é por ele que se sabe se
// aquilo é um `Sync`, um `Swap` do V2 ou um `Swap` do V3, sem perguntar nada a
// ninguém. É daqui que a reserva vem.
public record Log(byte[] endereco, List<byte[]> topicos, byte[] dados) {

	public Log {
		topicos = List.copyOf(topicos);
	}

	public boolean assinadoPor(byte[] topicoZero) {
		return !topicos.isEmpty() && java.util.Arrays.equals(topicos.get(0), topicoZero);
	}
}
