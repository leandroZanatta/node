package br.com.node.vo;

public record Capacidade(String nome, int versao) {

	public static final String ETH = "eth";

	@Override
	public String toString() {
		return nome + "/" + versao;
	}
}
