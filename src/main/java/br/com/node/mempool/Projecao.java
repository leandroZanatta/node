package br.com.node.mempool;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

// Uma projeção de swap pendente sobre o estado: as pools que ele tocaria, quando o vimos, e o
// que entraria e sairia se ele fechasse. É a fotografia que o observatório correlaciona com a
// arbitragem confirmada depois, para medir se chegamos antes do bloco fechar.
record Projecao(String hash, Instant instante, List<byte[]> pools, BigInteger entrada, BigInteger saida) {
}
