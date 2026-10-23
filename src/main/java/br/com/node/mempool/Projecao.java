package br.com.node.mempool;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

record Projecao(String hash, Instant instante, List<byte[]> pools, BigInteger entrada, BigInteger saida) {
}