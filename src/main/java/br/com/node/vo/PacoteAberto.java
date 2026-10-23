package br.com.node.vo;

public record PacoteAberto(byte[] remetenteId, byte[] hash, int tipo, byte[] carga) {
}