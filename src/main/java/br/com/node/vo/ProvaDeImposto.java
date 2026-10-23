package br.com.node.vo;

public record ProvaDeImposto(byte[] pool, byte[] token, int bps, boolean compra) {
}