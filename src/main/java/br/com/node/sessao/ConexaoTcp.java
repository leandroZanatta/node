package br.com.node.sessao;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import br.com.node.exception.PeerException;
import br.com.node.rede.Aperto;
import br.com.node.rede.Quadros;
import br.com.node.rede.Rlpx;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.Capacidade;
import br.com.node.vo.Disconnect;
import br.com.node.vo.Hello;
import br.com.node.vo.MensagemEth;
import br.com.node.vo.MensagemP2p;
import br.com.node.vo.MensagemRlpx;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.PingP2p;
import br.com.node.vo.PongP2p;

public final class ConexaoTcp implements Closeable {

	private static final int VERSAO_P2P = 5;

	// Anunciamos só a versão cujo formato de Status está implementado. O eth/70
	// acrescenta a faixa de blocos servida, e anunciá-lo sem produzi-la é mentir
	// no protocolo — o par emudece e o sintoma não é erro, é silêncio.
	private static final int VERSAO_ETH = 68;

	private static final String CLIENTE = "node-java/0.1";

	private static final int PREFIXO_ACK = 2;

	private final NoRemoto no;
	private final NodeKey chave;
	private final Rlpx rlpx = new Rlpx();

	private Socket socket;
	private InputStream entrada;
	private OutputStream saida;
	private Quadros quadros;

	public ConexaoTcp(NoRemoto no, NodeKey chave) {
		this.no = no;
		this.chave = chave;
	}

	public Hello abrir() {

		conectar();

		Aperto aperto = new Aperto(chave, no.id());

		escrever(aperto.auth());

		quadros = new Quadros(aperto.aceitar(lerAck()));

		enviar(new Hello(VERSAO_P2P, CLIENTE, List.of(new Capacidade(Capacidade.ETH, VERSAO_ETH)),
				no.portaTcp(), chave.id()));

		// A ordem é obrigatória: o nosso Hello sai, o dele entra, e só então há
		// compressão. Qualquer mensagem no meio vira quadro que o par descarta.
		return esperar(Hello.class);
	}

	// SINCRONIZADAS porque duas threads escrevem nesta conexão: o leitor, que responde o
	// Ping do par, e quem pede. E não é só o socket — `Quadros.escrever` avança o estado
	// do MAC de saída, então dois quadros montados ao mesmo tempo saem com MAC trocado e
	// o par derruba por "quebra de protocolo".
	public synchronized void enviar(MensagemP2p mensagem) {
		escrever(quadros.escrever(rlpx.escrever(mensagem)));
	}

	public synchronized void enviar(MensagemEth mensagem) {
		escrever(quadros.escrever(rlpx.escrever(mensagem)));
	}

	public MensagemRlpx ler() {
		return rlpx.ler(proximaCarga());
	}

	// Lê até chegar o que se espera, respondendo o Ping do par no caminho. Está aqui e
	// não na sessão porque Ping/Pong é o protocolo DESTA conexão — manter o fluxo de
	// pé é trabalho de quem o conduz.
	//
	// Limitado por PRAZO, não por contagem: o par intercala milhares de anúncios de
	// mempool com a resposta — medido, 4.549 contra 256 de qualquer outra coisa.
	// Contar tentativas desistiria antes da resposta chegar.
	public <T extends MensagemRlpx> T esperar(Class<T> esperado) {

		long limite = System.currentTimeMillis() + Resources.inteiro("rlpx.pedido.timeout.ms");

		while (System.currentTimeMillis() < limite) {

			MensagemRlpx mensagem = ler();

			if (esperado.isInstance(mensagem)) {
				return esperado.cast(mensagem);
			}

			if (mensagem instanceof PingP2p) {
				enviar(new PongP2p());
			}

			if (mensagem instanceof Disconnect fim) {
				throw new PeerException(
						"Par " + no + " derrubou antes do " + esperado.getSimpleName() + ": " + fim.descricao());
			}
		}

		throw new PeerException(esperado.getSimpleName() + " não chegou de " + no);
	}

	@Override
	public void close() {

		if (socket == null) {
			return;
		}

		try {
			socket.close();
		} catch (IOException e) {
			throw new PeerException("Falha ao fechar TCP com " + no + ": " + e.getMessage());
		}
	}

	private void conectar() {

		try {

			socket = new Socket();

			socket.connect(new InetSocketAddress(no.ip(), no.portaTcp()),
					Resources.inteiro("rlpx.timeout.conexao.ms"));

			// O defeito nº 1 do node anterior: socket sem isto, e a thread de leitura
			// ficava presa para sempre num par morto, ocupando um dos 25 slots.
			socket.setSoTimeout(Resources.inteiro("rlpx.timeout.leitura.ms"));

			entrada = socket.getInputStream();
			saida = socket.getOutputStream();

		} catch (IOException e) {
			throw new PeerException("Falha ao conectar em " + no + ": " + e.getMessage());
		}
	}

	// O ack vem com prefixo de dois bytes EM CLARO dizendo o tamanho — ele é
	// anterior aos segredos, e o prefixo também entra no MAC do ECIES.
	private byte[] lerAck() {

		byte[] prefixo = exatos(PREFIXO_ACK);

		int total = Aperto.tamanhoAck(prefixo);

		byte[] ack = new byte[total];

		System.arraycopy(prefixo, 0, ack, 0, PREFIXO_ACK);
		System.arraycopy(exatos(total - PREFIXO_ACK), 0, ack, PREFIXO_ACK, total - PREFIXO_ACK);

		return ack;
	}

	// Um quadro pode vir picado em vários pacotes, e vários quadros podem vir no
	// mesmo. Quem sabe onde um acaba é o `Quadros`, então alimenta-se ele até
	// sobrar uma carga.
	private byte[] proximaCarga() {

		byte[] carga = quadros.ler(null);

		while (carga == null) {

			byte[] pedaco = new byte[4096];

			int lidos = ler(pedaco);

			if (lidos < 0) {
				throw new PeerException("Par " + no + " fechou a conexão");
			}

			carga = quadros.ler(java.util.Arrays.copyOf(pedaco, lidos));
		}

		return carga;
	}

	private byte[] exatos(int quantos) {

		byte[] bytes = new byte[quantos];

		int lidos = 0;

		while (lidos < quantos) {

			int agora = ler(bytes, lidos, quantos - lidos);

			if (agora < 0) {
				throw new PeerException("Par " + no + " fechou antes de completar " + quantos + " bytes");
			}

			lidos += agora;
		}

		return bytes;
	}

	private int ler(byte[] destino) {
		return ler(destino, 0, destino.length);
	}

	private int ler(byte[] destino, int inicio, int quantos) {

		try {
			return entrada.read(destino, inicio, quantos);
		} catch (IOException e) {
			throw new PeerException("Falha lendo de " + no + ": " + e.getMessage());
		}
	}

	private void escrever(byte[] dados) {

		try {
			saida.write(dados);
			saida.flush();
		} catch (IOException e) {
			throw new PeerException("Falha escrevendo para " + no + ": " + e.getMessage());
		}
	}
}
