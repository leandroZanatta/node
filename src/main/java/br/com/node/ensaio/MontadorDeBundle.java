package br.com.node.ensaio;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import br.com.node.rlp.Rlp;
import br.com.node.util.Falhas;
import br.com.node.util.Keccak;
import br.com.node.util.Keystore;
import br.com.node.util.Secp256k1;

final class MontadorDeBundle {

	private static final Logger LOG = Logger.getLogger(MontadorDeBundle.class.getName());
	private static final Path KEYSTORE = Path.of("operador.keystore");
	private static final String WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";

	record Pacote(byte[] arbRaw, String arbHash, RelayPrivado.BidArgs bid,
			java.util.function.Function<byte[], List<String>> lista, long nonceOperadorUsado,
			long nonceAtualizadoUsado) {
	}

	private final Optional<BigInteger> privada;
	private final String endereco;
	final boolean armado;
	private final String contrato;

	private volatile long nonce;
	private volatile long nonceAtualizadoEmMs;
	private volatile BigInteger saldoOperador;
	private volatile BigInteger saldoExecutorWBNB = BigInteger.ZERO;

	MontadorDeBundle(String contrato) {
		this.contrato = contrato;
		this.privada = carregarChave();
		this.endereco = privada
				.map(p -> HexFormat.of().formatHex(Arrays.copyOfRange(Keccak.hash(Secp256k1.id(p)), 12, 32)))
				.orElse(null);
		this.armado = "SIM".equals(System.getenv("ARB_ARMADO")) && privada.isPresent();
	}

	boolean temChave() {
		return privada.isPresent();
	}

	String endereco() {
		return endereco;
	}

	long nonce() {
		return nonce;
	}

	long nonceAtualizadoEmMs() {
		return nonceAtualizadoEmMs;
	}

	BigInteger saldoOperador() {
		return saldoOperador;
	}

	BigInteger saldoExecutorWBNB() {
		return saldoExecutorWBNB;
	}

	void atualizarNonce(RpcDoEnsaio rpc) {
		if (privada.isEmpty()) {
			return;
		}
		try {
			nonce = rpc.contagemDeTransacoes(endereco);
			nonceAtualizadoEmMs = System.currentTimeMillis();
		} catch (RuntimeException e) {
			Falhas.anotar("nonce do operador", e);
		}
		try {
			saldoOperador = rpc.saldoDe(endereco);
		} catch (RuntimeException e) {
			Falhas.anotar("saldo do operador", e);
		}
		try {
			saldoExecutorWBNB = rpc.saldoErc20(WBNB, contrato);
		} catch (RuntimeException e) {
			Falhas.anotar("saldo WBNB do executor", e);
		}
	}

	Pacote empacotar(byte[] cruaPendente, long alturaProximoBloco, byte[] parentHash,
			BigInteger prioridade, BigInteger maxTaxa, long gasFinal,
			BigInteger gasDaCauda, byte[] calldata, BigInteger pagamentoBnb) {

		long nonceOperador = nonce;
		long nonceAtualizado = nonceAtualizadoEmMs;
		boolean paga = pagamentoBnb.signum() > 0;

		byte[] arbRaw = AssinadorDeTransacao.assinar(nonceOperador, prioridade, maxTaxa, gasFinal,
				HexFormat.of().parseHex(contrato), BigInteger.ZERO, calldata, privada.get());
		String arbHash = "0x" + HexFormat.of().formatHex(Keccak.hash(arbRaw));

		List<byte[]> bundleTxsBytes = new ArrayList<>();
		if (cruaPendente != null) {
			bundleTxsBytes.add(cruaPendente);
		}
		bundleTxsBytes.add(arbRaw);

		BigInteger gasFee = gasDaCauda.multiply(prioridade);
		byte[] payBidTxBytes = paga
				? AssinadorDeTransacao.assinar(nonceOperador + 1, prioridade, maxTaxa,
						CustoDeGas.GAS_TX_PAGAMENTO_BUILDER, RelayPrivado.COLATERAL_48CLUB_BID,
						pagamentoBnb, new byte[0], privada.get())
				: new byte[0];

		var txsRlp = bundleTxsBytes.stream().map(Rlp::bytes).toList();
		var unrevRlp = bundleTxsBytes.stream().map(tx -> Rlp.bytes(Keccak.hash(tx))).toList();
		byte[] rawBidEncoded = Rlp.codificar(Rlp.lista(Rlp.inteiro(alturaProximoBloco),
				Rlp.bytes(parentHash), Rlp.lista(txsRlp), Rlp.lista(unrevRlp), Rlp.inteiro(gasDaCauda),
				Rlp.inteiro(gasFee), Rlp.inteiro(pagamentoBnb)));
		byte[] assinatura = Secp256k1.assinar(Keccak.hash(rawBidEncoded), privada.get(), Secp256k1.id(privada.get()));

		RelayPrivado.BidArgs bid = new RelayPrivado.BidArgs(alturaProximoBloco, parentHash, bundleTxsBytes,
				gasDaCauda.longValueExact(), gasFee, pagamentoBnb, assinatura, payBidTxBytes, pagamentoBnb);

		String arbRawHex = "0x" + HexFormat.of().formatHex(arbRaw);
		byte[] cruaFinal = cruaPendente;

		java.util.function.Function<byte[], List<String>> lista = destino -> {
			List<String> bundle = new ArrayList<>();
			if (cruaFinal != null) {
				bundle.add("0x" + HexFormat.of().formatHex(cruaFinal));
			}
			bundle.add(arbRawHex);
			if (paga) {
				bundle.add("0x" + HexFormat.of().formatHex(AssinadorDeTransacao.assinar(
						nonceOperador + 1, prioridade, maxTaxa, CustoDeGas.GAS_TX_PAGAMENTO_BUILDER,
						destino, pagamentoBnb, new byte[0], privada.get())));
			}
			return bundle;
		};

		return new Pacote(arbRaw, arbHash, bid, lista, nonceOperador, nonceAtualizado);
	}

	private static Optional<BigInteger> carregarChave() {
		String senha = System.getenv("OPERADOR_SENHA");
		if (!Files.exists(KEYSTORE) || senha == null || senha.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(new BigInteger(1, Keystore.decifrar(Files.readString(KEYSTORE), senha.toCharArray())));
		} catch (RuntimeException | java.io.IOException e) {
			if ("SIM".equals(System.getenv("ARB_ARMADO"))) {
				throw new IllegalStateException("ARB_ARMADO=SIM e o keystore não abriu: " + e.getMessage(), e);
			}
			LOG.warning(() -> "montador: keystore não abriu (" + e.getMessage() + ") — inerte");
			return Optional.empty();
		}
	}
}
