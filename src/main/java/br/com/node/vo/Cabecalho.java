package br.com.node.vo;

import java.util.Arrays;
import java.util.HexFormat;

// O cabeçalho como precisamos dele hoje: a altura e o hash. O hash não vem no
// pacote — é o keccak do próprio cabeçalho recodificado, e recodificar reproduz os
// bytes porque cabeçalho de bloco é RLP canônico.
//
// `hashDoPai` entrou quando o cursor passou a existir: é ele que prova a
// continuidade. `raizDosRecibos` continua fora — campo sem leitor não nasce.
//
// `instanteMs` entrou com a linha do tempo da reação: quando o validador fez o bloco, em ms. A BSC
// guarda os segundos no `timestamp` e o milissegundo no `mixHash` (0–999; conferido em 25/09 nos blocos
// 123894822–5: 350, 800, 250, 700). É o marco zero de "quanto chegamos tarde". 0 quando desconhecido.
public record Cabecalho(long numero, byte[] hash, byte[] hashDoPai, long instanteMs) {

	public Cabecalho(long numero, byte[] hash, byte[] hashDoPai) {
		this(numero, hash, hashDoPai, 0);
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof Cabecalho outro && numero == outro.numero && Arrays.equals(hash, outro.hash)
				&& Arrays.equals(hashDoPai, outro.hashDoPai);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(hash);
	}

	@Override
	public String toString() {
		return "Cabecalho[" + numero + "@" + HexFormat.of().formatHex(hash).substring(0, 12) + "…]";
	}
}
