package br.com.node.rede;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import br.com.node.exception.PeerException;
import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpItem;
import br.com.node.rlp.RlpLista;
import br.com.node.vo.Endpoint;
import br.com.node.vo.EnrRequest;
import br.com.node.vo.EnrResponse;
import br.com.node.vo.FindNode;
import br.com.node.vo.ForkId;
import br.com.node.vo.MensagemDiscovery;
import br.com.node.vo.Neighbors;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.PacoteAberto;
import br.com.node.vo.PacoteDiscovery;
import br.com.node.vo.Ping;
import br.com.node.vo.Pong;

public final class Discv4 {

	private final Envelope envelope;

	public Discv4(Envelope envelope) {
		this.envelope = envelope;
	}

	public byte[] codificar(MensagemDiscovery mensagem) {
		return envelope.selar(mensagem.tipo(), Rlp.codificar(paraRlp(mensagem)));
	}

	public PacoteDiscovery decodificar(byte[] pacote) {

		PacoteAberto aberto = envelope.abrir(pacote);

		return new PacoteDiscovery(aberto.remetenteId(), aberto.hash(),
				deRlp(aberto.tipo(), Rlp.decodificar(aberto.carga())));
	}

	private static RlpItem paraRlp(MensagemDiscovery mensagem) {

		return switch (mensagem) {

		case Ping ping -> Rlp.lista(Rlp.inteiro(ping.versao()), endpointParaRlp(ping.de()),
				endpointParaRlp(ping.para()), Rlp.inteiro(ping.expiracao()));

		case Pong pong ->
			Rlp.lista(endpointParaRlp(pong.para()), Rlp.bytes(pong.pingHash()), Rlp.inteiro(pong.expiracao()));

		case FindNode busca -> Rlp.lista(Rlp.bytes(busca.alvo()), Rlp.inteiro(busca.expiracao()));

		case Neighbors vizinhos -> Rlp.lista(Rlp.lista(vizinhos.nos().stream().map(Discv4::noParaRlp).toList()),
				Rlp.inteiro(vizinhos.expiracao()));

		case EnrRequest pedido -> Rlp.lista(Rlp.inteiro(pedido.expiracao()));

		case EnrResponse ignorado -> throw new PeerException("O node ainda não publica registro próprio (EIP-868)");
		};
	}

	private static MensagemDiscovery deRlp(int tipo, RlpItem dados) {

		List<RlpItem> campos = Rlp.paraLista(dados);

		return switch (tipo) {

		case MensagemDiscovery.PING -> {
			exigir(campos, 4, "Ping");
			yield new Ping((int) Rlp.paraInteiro(campos.get(0)), endpointDeRlp(campos.get(1)),
					endpointDeRlp(campos.get(2)), Rlp.paraInteiro(campos.get(3)));
		}

		case MensagemDiscovery.PONG -> {
			exigir(campos, 3, "Pong");
			yield new Pong(endpointDeRlp(campos.get(0)), Rlp.paraBytes(campos.get(1)), Rlp.paraInteiro(campos.get(2)));
		}

		case MensagemDiscovery.FIND_NODE -> {
			exigir(campos, 2, "FindNode");
			yield new FindNode(Rlp.paraBytes(campos.get(0)), Rlp.paraInteiro(campos.get(1)));
		}

		case MensagemDiscovery.NEIGHBORS -> {
			exigir(campos, 2, "Neighbors");
			yield new Neighbors(nosDeRlp(campos.get(0)), Rlp.paraInteiro(campos.get(1)));
		}

		case MensagemDiscovery.ENR_REQUEST -> {
			exigir(campos, 1, "EnrRequest");
			yield new EnrRequest(Rlp.paraInteiro(campos.get(0)));
		}

		case MensagemDiscovery.ENR_RESPONSE -> {
			exigir(campos, 2, "EnrResponse");
			yield new EnrResponse(Rlp.paraBytes(campos.get(0)), redeDeRlp(campos.get(1)));
		}

		default -> throw new PeerException("Tipo de pacote discv4 desconhecido: " + tipo);
		};
	}

	private static Optional<ForkId> redeDeRlp(RlpItem registro) {

		List<RlpItem> campos = Rlp.paraLista(registro);

		for (int i = 2; i + 1 < campos.size(); i += 2) {

			if (!"eth".equals(new String(Rlp.paraBytes(campos.get(i)), StandardCharsets.US_ASCII))) {
				continue;
			}

			List<RlpItem> valor = Rlp.paraLista(campos.get(i + 1));

			if (valor.isEmpty()) {
				return Optional.empty();
			}

			List<RlpItem> forkId = valor.get(0) instanceof RlpLista ? Rlp.paraLista(valor.get(0)) : valor;

			if (forkId.size() < 2) {
				return Optional.empty();
			}

			return Optional.of(new ForkId(Rlp.paraBytes(forkId.get(0)), Rlp.paraInteiro(forkId.get(1))));
		}

		return Optional.empty();
	}

	private static RlpItem endpointParaRlp(Endpoint endpoint) {
		return Rlp.lista(Rlp.bytes(endpoint.ip().getAddress()), Rlp.inteiro(endpoint.portaUdp()),
				Rlp.inteiro(endpoint.portaTcp()));
	}

	private static RlpItem noParaRlp(NoRemoto no) {
		return Rlp.lista(Rlp.bytes(no.ip().getAddress()), Rlp.inteiro(no.portaUdp()), Rlp.inteiro(no.portaTcp()),
				Rlp.bytes(no.id()));
	}

	private static Endpoint endpointDeRlp(RlpItem item) {

		List<RlpItem> campos = Rlp.paraLista(item);

		exigir(campos, 3, "Endpoint");

		return new Endpoint(enderecoDeRlp(campos.get(0)), (int) Rlp.paraInteiro(campos.get(1)),
				(int) Rlp.paraInteiro(campos.get(2)));
	}

	private static List<NoRemoto> nosDeRlp(RlpItem item) {

		List<NoRemoto> nos = new ArrayList<>();

		for (RlpItem bruto : Rlp.paraLista(item)) {

			List<RlpItem> campos = Rlp.paraLista(bruto);

			exigir(campos, 4, "vizinho");

			nos.add(new NoRemoto(Rlp.paraBytes(campos.get(3)), enderecoDeRlp(campos.get(0)),
					(int) Rlp.paraInteiro(campos.get(1)), (int) Rlp.paraInteiro(campos.get(2))));
		}

		return List.copyOf(nos);
	}

	private static InetAddress enderecoDeRlp(RlpItem item) {

		byte[] bytes = Rlp.paraBytes(item);

		try {
			return InetAddress.getByAddress(bytes);
		} catch (UnknownHostException e) {
			throw new PeerException("Endereço IP inválido no pacote: " + bytes.length + " bytes");
		}
	}

	private static void exigir(List<RlpItem> campos, int minimo, String nome) {

		if (campos.size() < minimo) {
			throw new PeerException(nome + " com " + campos.size() + " campos, esperado ao menos " + minimo);
		}
	}
}