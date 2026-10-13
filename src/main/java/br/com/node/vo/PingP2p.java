package br.com.node.vo;

// Não responder isto é levar Disconnect 0x0b. Nome com sufixo porque `Ping` já é
// do discv4, e dois tipos com o mesmo nome é veto.
public record PingP2p() implements MensagemP2p {

	@Override
	public int tipo() {
		return PING;
	}
}
