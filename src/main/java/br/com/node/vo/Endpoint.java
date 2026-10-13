package br.com.node.vo;

import java.net.InetAddress;

public record Endpoint(InetAddress ip, int portaUdp, int portaTcp) {
}
