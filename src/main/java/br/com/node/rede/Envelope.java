package br.com.node.rede;

import java.util.Arrays;

import br.com.node.exception.PeerException;
import br.com.node.util.Keccak;
import br.com.node.util.NodeKey;
import br.com.node.util.Secp256k1;
import br.com.node.vo.PacoteAberto;

public final class Envelope {

	public static final int TAMANHO_MAXIMO = 1280;

	private static final int OFFSET_ASSINATURA = Keccak.TAMANHO;
	private static final int OFFSET_TIPO = OFFSET_ASSINATURA + Secp256k1.TAMANHO_ASSINATURA;
	private static final int OFFSET_CARGA = OFFSET_TIPO + 1;

	private final NodeKey chave;

	public Envelope(NodeKey chave) {
		this.chave = chave;
	}

	public byte[] selar(int tipo, byte[] carga) {

		byte[] tipoECarga = new byte[1 + carga.length];

		tipoECarga[0] = (byte) tipo;

		System.arraycopy(carga, 0, tipoECarga, 1, carga.length);

		byte[] assinatura = chave.assinar(Keccak.hash(tipoECarga));
		byte[] hash = Keccak.hash(assinatura, tipoECarga);

		byte[] pacote = new byte[hash.length + assinatura.length + tipoECarga.length];

		System.arraycopy(hash, 0, pacote, 0, hash.length);
		System.arraycopy(assinatura, 0, pacote, OFFSET_ASSINATURA, assinatura.length);
		System.arraycopy(tipoECarga, 0, pacote, OFFSET_TIPO, tipoECarga.length);

		if (pacote.length > TAMANHO_MAXIMO) {
			throw new PeerException("Pacote discv4 com " + pacote.length + " bytes, máximo " + TAMANHO_MAXIMO);
		}

		return pacote;
	}

	public PacoteAberto abrir(byte[] pacote) {

		if (pacote.length <= OFFSET_CARGA) {
			throw new PeerException("Pacote discv4 truncado: " + pacote.length + " bytes");
		}

		byte[] hash = Arrays.copyOfRange(pacote, 0, OFFSET_ASSINATURA);
		byte[] calculado = Keccak.hash(pacote, OFFSET_ASSINATURA, pacote.length - OFFSET_ASSINATURA);

		if (!Arrays.equals(hash, calculado)) {
			throw new PeerException("Hash do pacote discv4 não confere");
		}

		byte[] assinatura = Arrays.copyOfRange(pacote, OFFSET_ASSINATURA, OFFSET_TIPO);
		byte[] remetente = Secp256k1.recuperarId(Keccak.hash(pacote, OFFSET_TIPO, pacote.length - OFFSET_TIPO),
				assinatura);

		return new PacoteAberto(remetente, hash, pacote[OFFSET_TIPO] & 0xff,
				Arrays.copyOfRange(pacote, OFFSET_CARGA, pacote.length));
	}
}