package br.com.node.vo;

import java.time.Instant;

public sealed interface MensagemDiscovery permits Ping, Pong, FindNode, Neighbors, EnrRequest, EnrResponse {

	int PING = 0x01;
	int PONG = 0x02;
	int FIND_NODE = 0x03;
	int NEIGHBORS = 0x04;
	int ENR_REQUEST = 0x05;
	int ENR_RESPONSE = 0x06;

	int VERSAO = 4;

	int tipo();

	long expiracao();

	default boolean expirada(Instant agora) {
		return expiracao() < agora.getEpochSecond();
	}
}
