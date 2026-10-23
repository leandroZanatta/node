package br.com.node.vo;

public record Desconhecida(int codigo, byte[] corpo) implements MensagemRlpx {

	@Override
	public int tipo() {
		return codigo;
	}
}