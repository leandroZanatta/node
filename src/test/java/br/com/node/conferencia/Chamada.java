package br.com.node.conferencia;

// Um `eth_call`: a quem perguntar e o que perguntar, em hex com 0x.
public record Chamada(String para, String dados) {
}
