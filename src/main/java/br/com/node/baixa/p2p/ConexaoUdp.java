package br.com.node.baixa.p2p;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import br.com.node.exception.PeerException;
import br.com.node.baixa.protocolo.Discv4;
import br.com.node.baixa.protocolo.PortaUdp;
import br.com.node.util.Keccak;
import br.com.node.vo.Endpoint;
import br.com.node.vo.MensagemDiscovery;

public final class ConexaoUdp {

	private final PortaUdp porta;
	private final Discv4 discv4;
	private final InetSocketAddress destino;
	private final int portaTcpLocal;

	public ConexaoUdp(PortaUdp porta, Discv4 discv4, InetSocketAddress destino, int portaTcpLocal) {
		this.porta = porta;
		this.discv4 = discv4;
		this.destino = destino;
		this.portaTcpLocal = portaTcpLocal;
	}

	public Endpoint origem() {

		try {
			return new Endpoint(InetAddress.getByAddress(new byte[4]), porta.portaLocal(), portaTcpLocal);
		} catch (UnknownHostException e) {
			throw new PeerException("Endereço local inválido: " + e.getMessage());
		}
	}

	public byte[] enviar(MensagemDiscovery mensagem) {

		byte[] pacote = discv4.codificar(mensagem);

		porta.enviar(destino, pacote);

		return Arrays.copyOf(pacote, Keccak.TAMANHO);
	}
}