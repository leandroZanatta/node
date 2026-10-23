package br.com.node.transformacao.mempool;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public record Projecao(String hash, Instant instante, List<byte[]> pools, BigInteger entrada, BigInteger saida) {
}