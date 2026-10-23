package br.com.node.baixa.protocolo;

import java.util.ArrayList;
import java.util.List;

import br.com.node.exception.PeerException;
import br.com.node.baixa.protocolo.Rlp;
import br.com.node.baixa.protocolo.RlpItem;
import br.com.node.vo.Capacidade;
import br.com.node.vo.Disconnect;
import br.com.node.vo.Hello;
import br.com.node.vo.MensagemP2p;
import br.com.node.vo.PingP2p;
import br.com.node.vo.PongP2p;

public final class P2p {

	private P2p() {
	}

	static RlpItem paraRlp(MensagemP2p mensagem) {

		return switch (mensagem) {

		case Hello hello -> Rlp.lista(Rlp.inteiro(hello.versao()), Rlp.bytes(hello.cliente().getBytes()),
				Rlp.lista(hello.capacidades().stream()
						.map(c -> Rlp.lista(Rlp.bytes(c.nome().getBytes()), Rlp.inteiro(c.versao()))).toList()),
				Rlp.inteiro(hello.porta()), Rlp.bytes(hello.id()));

		case Disconnect fim -> Rlp.lista(Rlp.inteiro(fim.motivo()));

		case PingP2p ignorado -> Rlp.lista();

		case PongP2p ignorado -> Rlp.lista();
		};
	}

	static MensagemP2p deRlp(int codigo, byte[] corpo) {

		return switch (codigo) {

		case MensagemP2p.HELLO -> hello(corpo);

		case MensagemP2p.DISCONNECT -> new Disconnect(motivo(corpo));

		case MensagemP2p.PING -> new PingP2p();

		case MensagemP2p.PONG -> new PongP2p();

		default -> throw new PeerException("Código p2p desconhecido: " + codigo);
		};
	}

	private static Hello hello(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		if (campos.size() < 5) {
			throw new PeerException("Hello com " + campos.size() + " campos");
		}

		List<Capacidade> capacidades = new ArrayList<>();

		for (RlpItem bruto : Rlp.paraLista(campos.get(2))) {

			List<RlpItem> par = Rlp.paraLista(bruto);

			if (par.size() >= 2) {
				capacidades
						.add(new Capacidade(new String(Rlp.paraBytes(par.get(0))), (int) Rlp.paraInteiro(par.get(1))));
			}
		}

		return new Hello((int) Rlp.paraInteiro(campos.get(0)), new String(Rlp.paraBytes(campos.get(1))), capacidades,
				(int) Rlp.paraInteiro(campos.get(3)), Rlp.paraBytes(campos.get(4)));
	}

	private static int motivo(byte[] corpo) {

		RlpItem item = Rlp.decodificar(corpo);

		try {
			List<RlpItem> lista = Rlp.paraLista(item);
			return lista.isEmpty() ? -1 : (int) Rlp.paraInteiro(lista.get(0));
		} catch (RuntimeException e) {
			return (int) Rlp.paraInteiro(item);
		}
	}
}