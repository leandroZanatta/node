package br.com.node.vo;

public sealed interface MensagemP2p extends MensagemRlpx permits Hello, Disconnect, PingP2p, PongP2p {

	int HELLO = 0x00;
	int DISCONNECT = 0x01;
	int PING = 0x02;
	int PONG = 0x03;
}