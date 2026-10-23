package br.com.node.baixa.protocolo;

import java.util.Arrays;

import br.com.node.exception.PeerException;
import br.com.node.baixa.protocolo.Rlp;
import br.com.node.baixa.protocolo.RlpItem;
import br.com.node.baixa.protocolo.RlpLeitor;
import br.com.node.vo.Desconhecida;
import br.com.node.vo.Hello;
import br.com.node.vo.MensagemEth;
import br.com.node.vo.MensagemP2p;
import br.com.node.vo.MensagemRlpx;

public final class Rlpx {

	private static final int OFFSET_ETH = 0x10;

	private boolean enviouHello;
	private boolean recebeuHello;

	public byte[] escrever(MensagemP2p mensagem) {

		if (mensagem instanceof Hello) {
			enviouHello = true;
		}

		return montar(mensagem.tipo(), P2p.paraRlp(mensagem), !(mensagem instanceof Hello));
	}

	public byte[] escrever(MensagemEth mensagem) {
		return montar(mensagem.tipo() + OFFSET_ETH, Eth.paraRlp(mensagem), true);
	}

	public MensagemRlpx ler(byte[] carga) {

		RlpLeitor leitor = new RlpLeitor(carga);

		int codigo = (int) Rlp.paraInteiro(leitor.ler());

		byte[] corpo = Arrays.copyOfRange(carga, leitor.posicao(), carga.length);

		if (comprime() && codigo != MensagemP2p.HELLO) {
			corpo = descomprimir(codigo, corpo);
		}

		if (codigo == MensagemP2p.HELLO) {
			recebeuHello = true;
		}

		try {
			return codigo < OFFSET_ETH ? P2p.deRlp(codigo, corpo) : Eth.deRlp(codigo - OFFSET_ETH, corpo);
		} catch (PeerException e) {

			return new Desconhecida(codigo, corpo);
		}
	}

	private static byte[] descomprimir(int codigo, byte[] corpo) {

		try {
			return Snappy.descomprimir(corpo);
		} catch (PeerException naoComprimido) {

			if (codigo == MensagemP2p.DISCONNECT) {
				return corpo;
			}

			throw naoComprimido;
		}
	}

	private boolean comprime() {
		return enviouHello && recebeuHello;
	}

	private byte[] montar(int codigo, RlpItem corpo, boolean comprimivel) {

		byte[] cabecalho = Rlp.codificar(Rlp.inteiro(codigo));
		byte[] dados = Rlp.codificar(corpo);

		if (comprimivel && comprime()) {
			dados = Snappy.comprimir(dados);
		}

		byte[] carga = new byte[cabecalho.length + dados.length];

		System.arraycopy(cabecalho, 0, carga, 0, cabecalho.length);
		System.arraycopy(dados, 0, carga, cabecalho.length, dados.length);

		return carga;
	}
}