package br.com.node.nucleo;

// Uma travessia: qual pool, o que entra e o que sai. Sempre por endereço.
public record Perna(byte[] pool, byte[] entra, byte[] sai) {
}
