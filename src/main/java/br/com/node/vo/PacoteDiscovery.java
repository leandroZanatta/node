package br.com.node.vo;

public record PacoteDiscovery(byte[] remetenteId, byte[] hash, MensagemDiscovery mensagem) {
}