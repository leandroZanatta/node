package br.com.node.rede;

import java.util.Arrays;

import br.com.node.exception.PeerException;
import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpItem;
import br.com.node.rlp.RlpLeitor;
import br.com.node.vo.Desconhecida;
import br.com.node.vo.Hello;
import br.com.node.vo.MensagemEth;
import br.com.node.vo.MensagemP2p;
import br.com.node.vo.MensagemRlpx;

// O que fica dentro de um quadro RLPx: `rlp(codigo) || corpo`. Esta classe guarda o
// que é NEGOCIADO — o deslocamento do eth e a compressão — e despacha para o codec
// da família. Os campos de cada mensagem são de P2p e Eth.
public final class Rlpx {

	// O eth numera a partir daqui porque os códigos abaixo são do protocolo base.
	// Com mais de uma capacidade acordada este valor deixaria de ser constante —
	// hoje só negociamos eth, e então é.
	private static final int OFFSET_ETH = 0x10;

	private boolean enviouHello;
	private boolean recebeuHello;

	// A escolha do codec sai do TIPO: quem manda mensagem base não passa pelo
	// deslocamento do eth, e o compilador resolve qual sobrecarga é qual.
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

		// O Hello é o único que trafega em claro: é ele que estabelece a compressão.
		if (comprime() && codigo != MensagemP2p.HELLO) {
			corpo = descomprimir(codigo, corpo);
		}

		if (codigo == MensagemP2p.HELLO) {
			recebeuHello = true;
		}

		try {
			return codigo < OFFSET_ETH ? P2p.deRlp(codigo, corpo) : Eth.deRlp(codigo - OFFSET_ETH, corpo);
		} catch (PeerException e) {
			// Código que ainda não sabemos ler não é erro: é roteiro. Ver Desconhecida.
			return new Desconhecida(codigo, corpo);
		}
	}

	// O DISCONNECT PODE CHEGAR EM CLARO mesmo depois dos dois Hello: o par que recusa a conexão
	// logo após o aperto escreve o motivo antes de ligar a compressão do lado dele. Medido: 16
	// aberturas perdidas com "Snappy produziu 0 bytes, anunciou 449/705/1473" — e os três números
	// são exatamente o varint lido de corpos RLP em claro: `c1 03` (peer inútil), `c1 05` (já
	// conectado) e `c1 0b` (timeout). Não era defeito de descompressão: era a queda perdendo o
	// próprio motivo.
	//
	// Corpo em claro nunca descomprime por acaso: começa em 0xc0 ou acima, o que dá varint de
	// pelo menos 128 com dois bytes de entrada, e o Snappy recusa. Só se tenta em claro depois
	// da recusa, e só para o Disconnect.
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

	// Só vale depois dos dois Hello. Quem garante a ordem é a conexão: manda o nosso,
	// lê o dele, e só então qualquer outra coisa. Comprimir cedo produz um quadro que
	// o par descarta sem dizer nada.
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
