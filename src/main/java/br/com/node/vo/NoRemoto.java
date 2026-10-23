package br.com.node.vo;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;

public record NoRemoto(byte[] id, InetAddress ip, int portaUdp, int portaTcp) {

	public String idHex() {
		return HexFormat.of().formatHex(id);
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof NoRemoto outro && Arrays.equals(id, outro.id) && ip.equals(outro.ip)
				&& portaUdp == outro.portaUdp && portaTcp == outro.portaTcp;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(id) * 31 + portaUdp;
	}

	@Override
	public String toString() {
		return "NoRemoto[" + idHex().substring(0, 16) + "…@" + ip.getHostAddress() + ":" + portaUdp + "]";
	}
}