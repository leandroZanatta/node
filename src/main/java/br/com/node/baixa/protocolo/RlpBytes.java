package br.com.node.baixa.protocolo;

import java.util.Arrays;
import java.util.HexFormat;

public record RlpBytes(byte[] valor) implements RlpItem {

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof RlpBytes outro && Arrays.equals(valor, outro.valor);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(valor);
	}

	@Override
	public String toString() {
		return "RlpBytes[0x" + HexFormat.of().formatHex(valor) + "]";
	}
}