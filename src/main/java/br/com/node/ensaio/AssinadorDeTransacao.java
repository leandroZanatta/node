package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpItem;
import br.com.node.util.Keccak;
import br.com.node.util.Secp256k1;

public final class AssinadorDeTransacao {

	private static final long CADEIA = 56;
	private static final byte TIPO_1559 = 0x02;

	private AssinadorDeTransacao() {
	}

	public static byte[] assinar(long nonce, BigInteger maxPrioridade, BigInteger maxTaxa, long gas, byte[] para,
			BigInteger valor, byte[] dados, BigInteger privada) {

		List<RlpItem> campos = new ArrayList<>(
				List.of(Rlp.inteiro(CADEIA), Rlp.inteiro(nonce), Rlp.inteiro(maxPrioridade), Rlp.inteiro(maxTaxa),
						Rlp.inteiro(gas), Rlp.bytes(para), Rlp.inteiro(valor), Rlp.bytes(dados), Rlp.lista(List.of())));

		byte[] hash = Keccak.hash(comTipo(Rlp.codificar(Rlp.lista(campos))));

		byte[] assinatura = Secp256k1.assinar(hash, privada, Secp256k1.id(privada));
		int yParity = assinatura[64] & 0xff;
		BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(assinatura, 0, 32));
		BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(assinatura, 32, 64));

		campos.add(Rlp.inteiro(yParity));
		campos.add(Rlp.inteiro(r));
		campos.add(Rlp.inteiro(s));

		return comTipo(Rlp.codificar(Rlp.lista(campos)));
	}

	private static byte[] comTipo(byte[] rlp) {

		byte[] comTipo = new byte[1 + rlp.length];
		comTipo[0] = TIPO_1559;
		System.arraycopy(rlp, 0, comTipo, 1, rlp.length);

		return comTipo;
	}
}