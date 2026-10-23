package br.com.node.vo;

import java.util.List;

public record Log(byte[] endereco, List<byte[]> topicos, byte[] dados) {

	public Log {
		topicos = List.copyOf(topicos);
	}

	public boolean assinadoPor(byte[] topicoZero) {
		return !topicos.isEmpty() && java.util.Arrays.equals(topicos.get(0), topicoZero);
	}
}