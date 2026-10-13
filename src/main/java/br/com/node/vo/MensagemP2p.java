package br.com.node.vo;

// O protocolo base do RLPx: existe só para negociar e manter a conexão. Os
// códigos 0x00..0x0f são dele; as capacidades acordadas no Hello começam depois.
public sealed interface MensagemP2p extends MensagemRlpx permits Hello, Disconnect, PingP2p, PongP2p {

	int HELLO = 0x00;
	int DISCONNECT = 0x01;
	int PING = 0x02;
	int PONG = 0x03;
}
