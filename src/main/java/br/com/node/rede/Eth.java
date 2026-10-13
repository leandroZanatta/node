package br.com.node.rede;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import br.com.node.exception.PeerException;
import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpItem;
import br.com.node.util.Keccak;
import br.com.node.vo.AnuncioBloco;
import br.com.node.vo.BlockHeaders;
import br.com.node.vo.Cabecalho;
import br.com.node.vo.ForkId;
import br.com.node.vo.GetBlockHeaders;
import br.com.node.vo.GetBlockHeadersDeHash;
import br.com.node.vo.GetReceipts;
import br.com.node.vo.MensagemEth;
import br.com.node.vo.NewBlock;
import br.com.node.vo.NewBlockHashes;
import br.com.node.vo.NewPooledTransactionHashes;
import br.com.node.vo.GetPooledTransactions;
import br.com.node.vo.Receipts;
import br.com.node.vo.Status;
import br.com.node.vo.PooledTransactions;
import br.com.node.vo.Transactions;
import br.com.node.vo.UpgradeStatus;

// Traduz mensagem do eth de e para RLP.
public final class Eth {

	private Eth() {
	}

	static RlpItem paraRlp(MensagemEth mensagem) {

		return switch (mensagem) {

		case Status status -> Rlp.lista(Rlp.inteiro(status.versao()), Rlp.inteiro(status.rede()),
				Rlp.inteiro(status.dificuldadeTotal()), Rlp.bytes(status.melhorBloco()), Rlp.bytes(status.genesis()),
				Rlp.lista(Rlp.bytes(status.fork().hash()), Rlp.inteiro(status.fork().proximo())));

		case UpgradeStatus upgrade -> Rlp.lista(Rlp.lista(Rlp.inteiro(upgrade.desligarBroadcastDeTx() ? 1 : 0)));

		case GetBlockHeaders pedido -> Rlp.lista(Rlp.inteiro(pedido.requisicaoId()),
				Rlp.lista(Rlp.inteiro(pedido.deAltura()), Rlp.inteiro(pedido.quantos()), Rlp.inteiro(pedido.pulo()),
						Rlp.inteiro(pedido.reverso() ? 1 : 0)));

		case GetBlockHeadersDeHash pedido -> Rlp.lista(Rlp.inteiro(pedido.requisicaoId()),
				Rlp.lista(Rlp.bytes(pedido.hash()), Rlp.inteiro(pedido.quantos()), Rlp.inteiro(pedido.pulo()),
						Rlp.inteiro(pedido.reverso() ? 1 : 0)));

		case GetReceipts pedido -> Rlp.lista(Rlp.inteiro(pedido.requisicaoId()),
				Rlp.lista(pedido.hashes().stream().map(Rlp::bytes).toList()));

		case GetPooledTransactions pedido -> Rlp.lista(Rlp.inteiro(pedido.requisicaoId()),
				Rlp.lista(pedido.hashes().stream().map(Rlp::bytes).toList()));

		// Estes quatro só chegam, nunca saem: anunciar bloco e servir cabeçalho, recibo ou transação
		// exigem ter cadeia, e ainda não temos. Entram quando o cursor existir.
		case NewBlockHashes ignorado -> throw new PeerException("O node ainda não anuncia bloco");

		case NewBlock ignorado -> throw new PeerException("O node ainda não envia um bloco inteiro");

		case NewPooledTransactionHashes ignorado -> throw new PeerException("O node ainda não anuncia transação");

		case Transactions ignorado -> throw new PeerException("O node ainda não serve transação");

		case BlockHeaders ignorado -> throw new PeerException("O node ainda não serve cabeçalho");

		case Receipts ignorado -> throw new PeerException("O node ainda não serve recibo");

		case PooledTransactions ignorado -> throw new PeerException("O node ainda não responde transação pendente");
		};
	}

	static MensagemEth deRlp(int codigo, byte[] corpo) {

		return switch (codigo) {

		case MensagemEth.STATUS -> status(corpo);

		case MensagemEth.UPGRADE_STATUS -> new UpgradeStatus(bandeira(corpo));

		case MensagemEth.NEW_BLOCK_HASHES -> anuncios(corpo);

		case MensagemEth.NEW_BLOCK -> bloco(corpo);

		case 0x02 -> transacoes(corpo);

		case 0x08 -> hashesDeTransacoes(corpo);

		case 0x0a -> transacoesPedidas(corpo);

		case MensagemEth.BLOCK_HEADERS -> cabecalhos(corpo);

		case MensagemEth.RECEIPTS -> recibos(corpo);

		default -> throw new PeerException("Código eth ainda não tratado: " + codigo);
		};
	}

	private static Status status(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		if (campos.size() < 5) {
			throw new PeerException("Status com " + campos.size() + " campos");
		}

		ForkId fork = new ForkId(new byte[0], 0);

		if (campos.size() > 5) {

			List<RlpItem> declarado = Rlp.paraLista(campos.get(5));

			if (declarado.size() >= 2) {
				fork = new ForkId(Rlp.paraBytes(declarado.get(0)), Rlp.paraInteiro(declarado.get(1)));
			}
		}

		return new Status((int) Rlp.paraInteiro(campos.get(0)), Rlp.paraInteiro(campos.get(1)),
				new BigInteger(1, Rlp.paraBytes(campos.get(2))), Rlp.paraBytes(campos.get(3)),
				Rlp.paraBytes(campos.get(4)), fork);
	}

	// Bandeira do UpgradeStatus, aninhada em duas listas. Ausente ou ilegível conta
	// como falso: o que importa é responder, não o valor.
	private static boolean bandeira(byte[] corpo) {

		try {
			return Rlp.paraInteiro(Rlp.paraLista(Rlp.paraLista(Rlp.decodificar(corpo)).get(0)).get(0)) != 0;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static NewBlockHashes anuncios(byte[] corpo) {

		List<AnuncioBloco> anuncios = new ArrayList<>();

		for (RlpItem bruto : Rlp.paraLista(Rlp.decodificar(corpo))) {

			List<RlpItem> par = Rlp.paraLista(bruto);

			if (par.size() >= 2) {
				anuncios.add(new AnuncioBloco(Rlp.paraBytes(par.get(0)), Rlp.paraInteiro(par.get(1))));
			}
		}

		return new NewBlockHashes(anuncios);
	}

	private static NewPooledTransactionHashes hashesDeTransacoes(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		// eth/68: [types, sizes, hashes]. Clientes antigos mandavam só [hashes] ou a lista crua.
		List<RlpItem> brutos = campos.size() >= 3 && campos.get(2) instanceof br.com.node.rlp.RlpLista
				? Rlp.paraLista(campos.get(2))
				: campos.size() == 1 && campos.get(0) instanceof br.com.node.rlp.RlpLista ? Rlp.paraLista(campos.get(0))
						: campos;
		List<byte[]> hashes = brutos.stream().filter(item -> !(item instanceof br.com.node.rlp.RlpLista))
				.map(Rlp::paraBytes).filter(hash -> hash.length == 32).toList();

		// Os tamanhos do eth/68, na ordem dos hashes. Só valem se casarem um a um — o filtro acima tira
		// hash malformado, e aí a correspondência se perde: melhor sem tamanho que com o tamanho errado.
		List<Long> tamanhos = List.of();

		if (campos.size() >= 3 && campos.get(1) instanceof br.com.node.rlp.RlpLista lista
				&& Rlp.paraLista(lista).size() == hashes.size() && brutos.size() == hashes.size()) {
			tamanhos = Rlp.paraLista(lista).stream().map(item -> {
				byte[] b = Rlp.paraBytes(item);
				return b.length == 0 ? 0L : new java.math.BigInteger(1, b).longValue();
			}).toList();
		}

		return new NewPooledTransactionHashes(hashes, tamanhos);
	}

	private static Transactions transacoes(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		return new Transactions(campos.stream().map(Eth::transacaoCrua).toList());
	}

	private static PooledTransactions transacoesPedidas(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		if (campos.size() < 2) {
			throw new PeerException("PooledTransactions com " + campos.size() + " campos");
		}

		return new PooledTransactions(Rlp.paraInteiro(campos.get(0)),
				Rlp.paraLista(campos.get(1)).stream().map(Eth::transacaoCrua).toList());
	}

	private static byte[] transacaoCrua(RlpItem item) {
		return item instanceof br.com.node.rlp.RlpLista ? Rlp.codificar(item) : Rlp.paraBytes(item);
	}

	private static BlockHeaders cabecalhos(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		if (campos.size() < 2) {
			throw new PeerException("BlockHeaders com " + campos.size() + " campos");
		}

		List<Cabecalho> cabecalhos = new ArrayList<>();

		for (RlpItem bruto : Rlp.paraLista(campos.get(1))) {

			// A altura é o campo 8 do cabeçalho, e o hash é o keccak do cabeçalho inteiro —
			// ele não viaja no pacote. Recodificar reproduz os bytes porque cabeçalho de
			// bloco é RLP canônico; se não fosse, o hash sairia diferente e a cadeia não
			// fecharia.
			List<RlpItem> campo = Rlp.paraLista(bruto);

			if (campo.size() < 9) {
				continue;
			}

			// Campo 0 é o hash do pai e campo 8 é a altura. O pai é o que encadeia o cursor:
			// sem ele, "não passar por furo" não é verificável, é esperança.
			cabecalhos.add(new Cabecalho(Rlp.paraInteiro(campo.get(8)), Keccak.hash(Rlp.codificar(bruto)),
					Rlp.paraBytes(campo.get(0)), instanteMs(campo)));
		}

		return new BlockHeaders(Rlp.paraInteiro(campos.get(0)), cabecalhos);
	}

	// Campo 11 é o `timestamp` (s) e 13 o `mixHash`, onde a BSC põe o milissegundo (0–999). Cabeçalho
	// curto ou `mixHash` fora da faixa (cadeia sem ms) dá o segundo cheio; sem campo 11, 0.
	static long instanteMs(List<RlpItem> campo) {

		if (campo.size() < 12) {
			return 0;
		}

		long segundos = Rlp.paraInteiro(campo.get(11));
		long ms = 0;

		if (campo.size() > 13) {
			java.math.BigInteger mistura = new java.math.BigInteger(1, Rlp.paraBytes(campo.get(13)));
			ms = mistura.compareTo(java.math.BigInteger.valueOf(1000)) < 0 ? mistura.longValue() : 0;
		}

		return segundos * 1000 + ms;
	}

	private static NewBlock bloco(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		// O FIO É `[bloco, dificuldade total, (sidecars)]` — a especificação eth e o `NewBlockPacket` da
		// BSC —, e o bloco é que é `[header, transações, tios, …]`. CICATRIZ de 25/09: este parser lia a
		// mensagem como se fosse o bloco, via 2 campos, lançava, e o `Rlpx` rebaixava tudo a
		// `Desconhecida(23)`: 9.836 blocos inteiros jogados fora numa corrida de 64 min no servidor,
		// enquanto o node gastava uma ida (~142 ms) pedindo o cabeçalho que tinha acabado de chegar.
		// Reconhece-se o bloco pelo primeiro elemento: a header é LISTA; na forma solta, é o campo 0.
		if (!campos.isEmpty() && campos.get(0) instanceof br.com.node.rlp.RlpLista bloco
				&& !Rlp.paraLista(bloco).isEmpty() && Rlp.paraLista(bloco).get(0) instanceof br.com.node.rlp.RlpLista) {
			campos = Rlp.paraLista(bloco);
		}

		if (campos.size() < 3) {
			throw new PeerException("NewBlock com " + campos.size() + " campos");
		}

		// campos 0 = header, 1 = transações, 2 = tios. A mesma dissecção do BlockHeaders:
		// a altura é o campo 8 da header, o hash é o keccak do RLP canônico dela e o campo 0
		// é o pai. As transações ficam cruas para quem precisa do desfecho.
		RlpItem headerCruda = campos.get(0);

		List<RlpItem> campo = Rlp.paraLista(headerCruda);

		if (campo.size() < 9) {
			throw new PeerException("NewBlock com header de " + campo.size() + " campos");
		}

		Cabecalho cabecalho = new Cabecalho(Rlp.paraInteiro(campo.get(8)), Keccak.hash(Rlp.codificar(headerCruda)),
				Rlp.paraBytes(campo.get(0)), instanteMs(campo));

		List<byte[]> transacoes = Rlp.paraLista(campos.get(1)).stream().map(Eth::transacaoCrua).toList();

		return new NewBlock(cabecalho, transacoes);
	}

	private static Receipts recibos(byte[] corpo) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(corpo));

		if (campos.size() < 2) {
			throw new PeerException("Receipts com " + campos.size() + " campos");
		}

		// Recodificar preserva o bloco como ele chegou. Decodificar log é trabalho de
		// quem monta o catálogo, e ainda não existe quem leia.
		List<byte[]> porBloco = Rlp.paraLista(campos.get(1)).stream().map(Rlp::codificar).toList();

		return new Receipts(Rlp.paraInteiro(campos.get(0)), porBloco);
	}
}
