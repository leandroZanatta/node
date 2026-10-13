package br.com.node.rede;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.com.node.exception.PeerException;

public final class PortaUdp implements Closeable {

	private static final Logger LOG = Logger.getLogger(PortaUdp.class.getName());

	private final int porta;

	private BiConsumer<InetSocketAddress, byte[]> ouvinte;
	private DatagramSocket socket;
	private Thread laco;

	private volatile boolean parado;

	public PortaUdp(int porta) {
		this.porta = porta;
	}

	public void abrir(BiConsumer<InetSocketAddress, byte[]> ouvinte) {

		this.ouvinte = ouvinte;

		try {
			socket = new DatagramSocket(porta);
		} catch (IOException e) {
			throw new PeerException("Falha ao abrir a porta UDP " + porta + ": " + e.getMessage());
		}

		laco = new Thread(this::receber, "discv4");
		laco.setDaemon(true);
		laco.start();

		LOG.info(() -> "porta UDP " + socket.getLocalPort() + " aberta");
	}

	public void enviar(InetSocketAddress destino, byte[] dados) {

		try {
			socket.send(new DatagramPacket(dados, dados.length, destino));
		} catch (IOException e) {
			throw new PeerException("Falha ao enviar datagrama para " + destino + ": " + e.getMessage());
		}
	}

	public int portaLocal() {
		return socket.getLocalPort();
	}

	@Override
	public void close() {

		parado = true;

		if (socket != null) {
			socket.close();
		}
	}

	private void receber() {

		byte[] buffer = new byte[Envelope.TAMANHO_MAXIMO];

		while (!parado) {

			DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

			try {

				socket.receive(pacote);

				ouvinte.accept((InetSocketAddress) pacote.getSocketAddress(),
						Arrays.copyOf(pacote.getData(), pacote.getLength()));

			} catch (IOException e) {

				if (!parado) {
					LOG.log(Level.WARNING, "falha recebendo datagrama", e);
				}

			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("datagrama descartado", e);
			}
		}

		LOG.info("porta UDP encerrada");
	}
}
