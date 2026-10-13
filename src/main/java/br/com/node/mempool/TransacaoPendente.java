package br.com.node.mempool;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpItem;
import br.com.node.util.Keccak;
import br.com.node.util.Secp256k1;

public record TransacaoPendente(byte[] hash, byte[] destino, BigInteger valor, byte[] entrada) {

	private static final long CADEIA = 56; // BSC

	// O REMETENTE da tx, recuperado da assinatura — quem o eth_simulateV1 usa como `from` do swap
	// pendente, pra a EVM aplicá-lo com o saldo e o allowance REAIS dele no `latest`, sem forjar
	// nada. Caro (recuperação ECDSA), então é sob demanda: só se chama para a tx que abriu fenda,
	// nunca para o fluxo inteiro da mempool. Vazio quando a assinatura não fecha (tx estranha).
	public static Optional<byte[]> remetente(byte[] crua) {

		if (crua == null || crua.length == 0) {
			return Optional.empty();
		}

		try {

			int tipo = crua[0] & 0xff;

			// Tipo 4 (EIP-7702) é tipado como o 2, com a lista de autorizações antes da assinatura. Antes caía no
			// legado e devolvia um endereço qualquer, calado.
			if (tipo == 1 || tipo == 2 || tipo == 4) {
				return remetenteTipado(tipo, Arrays.copyOfRange(crua, 1, crua.length));
			}

			if (tipo <= 0x7f) {
				throw new IllegalArgumentException("tipo de tx sem leitura de remetente: " + tipo);
			}

			return remetenteLegado(crua);

		} catch (RuntimeException ilegivel) {
			br.com.node.util.Falhas.anotar("remetente da tx pendente", ilegivel);
			return Optional.empty();
		}
	}

	// Legado: [nonce, gasPrice, gas, to, value, data, v, r, s]. O hash de assinatura é EIP-155
	// (com chainId e dois zeros) quando v >= 35; senão o pré-155 (só os seis primeiros campos).
	private static Optional<byte[]> remetenteLegado(byte[] rlp) {

		List<RlpItem> c = Rlp.paraLista(Rlp.decodificar(rlp));

		if (c.size() < 9) {
			return Optional.empty();
		}

		long v = inteiroGrande(c.get(6)).longValueExact();
		BigInteger r = inteiroGrande(c.get(7));
		BigInteger s = inteiroGrande(c.get(8));

		List<RlpItem> assinar = new java.util.ArrayList<>(c.subList(0, 6));
		int recuperacao;

		if (v >= 35) {
			assinar.add(Rlp.inteiro((v - 35) / 2));
			assinar.add(Rlp.inteiro(0));
			assinar.add(Rlp.inteiro(0));
			recuperacao = (int) ((v - 35) % 2);
		} else {
			recuperacao = (int) (v - 27);
		}

		return endereco(Keccak.hash(Rlp.codificar(Rlp.lista(assinar))), r, s, recuperacao);
	}

	// Tipada: tipo 2 assina os 9 primeiros campos (até a accessList), tipo 1 os 8; o hash leva o
	// byte do tipo na frente, e o yParity JÁ é o recovery id.
	private static Optional<byte[]> remetenteTipado(int tipo, byte[] semTipo) {

		List<RlpItem> c = Rlp.paraLista(Rlp.decodificar(semTipo));

		// Campos antes de (yParity, r, s): tipo 1 = 8; tipo 2 = 9; tipo 4 = 10 (+ authorizationList).
		int corte = switch (tipo) {
		case 1 -> 8;
		case 2 -> 9;
		case 4 -> 10;
		default -> throw new IllegalArgumentException("tipo sem remetente tipado: " + tipo);
		};

		if (c.size() < corte + 3) {
			return Optional.empty();
		}

		int recuperacao = (int) inteiroGrande(c.get(corte)).longValueExact();
		BigInteger r = inteiroGrande(c.get(corte + 1));
		BigInteger s = inteiroGrande(c.get(corte + 2));

		byte[] assinado = Rlp.codificar(Rlp.lista(c.subList(0, corte)));
		byte[] payload = new byte[1 + assinado.length];
		payload[0] = (byte) tipo;
		System.arraycopy(assinado, 0, payload, 1, assinado.length);

		return endereco(Keccak.hash(payload), r, s, recuperacao);
	}

	private static Optional<byte[]> endereco(byte[] hashAssinatura, BigInteger r, BigInteger s, int recuperacao) {

		if (recuperacao < 0 || recuperacao > 3) {
			return Optional.empty();
		}

		byte[] assinatura = new byte[65];
		alinhar(r, assinatura, 0);
		alinhar(s, assinatura, 32);
		assinatura[64] = (byte) recuperacao;

		byte[] chave = Secp256k1.recuperarId(hashAssinatura, assinatura);

		return Optional.of(Arrays.copyOfRange(Keccak.hash(chave), 12, 32));
	}

	private static void alinhar(BigInteger valor, byte[] destino, int offset) {

		byte[] bytes = valor.toByteArray();
		int copiar = Math.min(bytes.length, 32);

		System.arraycopy(bytes, bytes.length - copiar, destino, offset + 32 - copiar, copiar);
	}

	public static Optional<TransacaoPendente> decodificar(byte[] crua) {

		if (crua == null || crua.length == 0) {
			return Optional.empty();
		}

		try {

			byte[] hash = Keccak.hash(crua);

			int tipo = crua[0] & 0xff;

			if (tipo == 1) {
				return typed(hash, Arrays.copyOfRange(crua, 1, crua.length), 4);
			}

			// 2 (EIP-1559) e 4 (EIP-7702) têm DOIS campos de preço onde o 1 tem um: tudo anda uma posição.
			if (tipo == 2 || tipo == 4) {
				return typed(hash, Arrays.copyOfRange(crua, 1, crua.length), 5);
			}

			if (tipo <= 0x7f) {
				return Optional.empty();
			}

			return legado(hash, crua);

		} catch (RuntimeException ilegivel) {
			br.com.node.util.Falhas.anotar("decodificar tx pendente", ilegivel);
			return Optional.empty();
		}
	}

	// O PREÇO POR GÁS que o bloco vê desta tx, em wei. Legado e tipo 1: o `gasPrice`. Tipos 2 e 4: a gorjeta
	// limitada ao `maxFee` — na BSC a taxa base é zero (medido em 25/09: `baseFeePerGas` = 0 em 12 de 12 blocos),
	// então o efetivo É a gorjeta. É o número que o builder usa para ordenar, e o que o lance tem de bater.
	public static Optional<BigInteger> precoPorGas(byte[] crua) {

		if (crua == null || crua.length == 0) {
			return Optional.empty();
		}

		try {
			int tipo = crua[0] & 0xff;

			if (tipo == 1) {
				return Optional.of(inteiroGrande(Rlp.paraLista(Rlp.decodificar(Arrays.copyOfRange(crua, 1, crua.length))).get(2)));
			}

			if (tipo == 2 || tipo == 4) {
				List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(Arrays.copyOfRange(crua, 1, crua.length)));
				return Optional.of(inteiroGrande(campos.get(2)).min(inteiroGrande(campos.get(3))));
			}

			if (tipo <= 0x7f) {
				return Optional.empty();
			}

			return Optional.of(inteiroGrande(Rlp.paraLista(Rlp.decodificar(crua)).get(1)));

		} catch (RuntimeException ilegivel) {
			br.com.node.util.Falhas.anotar("preço por gás da tx", ilegivel);
			return Optional.empty();
		}
	}

	private static Optional<TransacaoPendente> legado(byte[] hash, byte[] rlp) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(rlp));

		if (campos.size() < 6) {
			return Optional.empty();
		}

		return Optional.of(new TransacaoPendente(hash, Rlp.paraBytes(campos.get(3)),
				inteiroGrande(campos.get(4)), Rlp.paraBytes(campos.get(5))));
	}

	// `destino` é a posição do `to`; `value` e `data` vêm logo depois. CICATRIZ: o tipo 2 era lido com as
	// posições do tipo 1 — o `gasLimit` virava destino e o `value` virava calldata. Toda tx EIP-1559 da
	// mempool saía ilegível (swap nunca decodificado) e o destino de 2–3 bytes derrubava o peer com
	// "destino não é endereço de 20 bytes": 270 quedas numa corrida de 55 min.
	private static Optional<TransacaoPendente> typed(byte[] hash, byte[] rlpSemTipo, int destino) {

		List<RlpItem> campos = Rlp.paraLista(Rlp.decodificar(rlpSemTipo));

		if (campos.size() < destino + 3) {
			return Optional.empty();
		}

		return Optional.of(new TransacaoPendente(hash, Rlp.paraBytes(campos.get(destino)),
				inteiroGrande(campos.get(destino + 1)), Rlp.paraBytes(campos.get(destino + 2))));
	}

	private static BigInteger inteiroGrande(RlpItem item) {
		byte[] bytes = Rlp.paraBytes(item);
		return bytes.length == 0 ? BigInteger.ZERO : new BigInteger(1, bytes);
	}
}
