package br.com.node.baixa.protocolo;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import br.com.node.exception.PeerException;
import br.com.node.baixa.protocolo.Rlp;
import br.com.node.baixa.protocolo.RlpItem;
import br.com.node.baixa.protocolo.RlpLeitor;
import br.com.node.util.Ecies;
import br.com.node.util.Keccak;
import br.com.node.util.NodeKey;
import br.com.node.util.Secp256k1;

public final class Aperto {

	public static final int VERSAO = 4;

	private static final int TAMANHO_NONCE = 32;
	private static final int PREFIXO = 2;

	private static final int ENCHIMENTO_MINIMO = 100;
	private static final int ENCHIMENTO_VARIACAO = 200;

	private static final SecureRandom ALEATORIO = new SecureRandom();

	private final NodeKey chave;
	private final byte[] idRemoto;

	private final NodeKey efemera = NodeKey.gerar();
	private final byte[] nonce = new byte[TAMANHO_NONCE];

	private byte[] authEnviado;

	public Aperto(NodeKey chave, byte[] idRemoto) {

		this.chave = chave;
		this.idRemoto = idRemoto;

		ALEATORIO.nextBytes(nonce);
	}

	public byte[] auth() {

		byte[] segredoEstatico = chave.acordar(idRemoto);

		byte[] assinatura = efemera.assinar(xor(segredoEstatico, nonce));

		byte[] corpo = Rlp.codificar(
				Rlp.lista(Rlp.bytes(assinatura), Rlp.bytes(chave.id()), Rlp.bytes(nonce), Rlp.inteiro(VERSAO)));

		byte[] comEnchimento = comEnchimento(corpo);

		int total = comEnchimento.length + Ecies.SOBRECARGA;

		byte[] prefixo = new byte[] { (byte) (total >>> 8), (byte) total };

		byte[] cifrado = Ecies.cifrar(idRemoto, comEnchimento, prefixo);

		byte[] pacote = new byte[PREFIXO + cifrado.length];

		System.arraycopy(prefixo, 0, pacote, 0, PREFIXO);
		System.arraycopy(cifrado, 0, pacote, PREFIXO, cifrado.length);

		authEnviado = pacote;

		return pacote;
	}

	public static int tamanhoAck(byte[] prefixo) {
		return (((prefixo[0] & 0xff) << 8) | (prefixo[1] & 0xff)) + PREFIXO;
	}

	public Segredos aceitar(byte[] ack) {

		if (authEnviado == null) {
			throw new PeerException("ack chegou antes do auth ter sido enviado");
		}

		if (ack.length < PREFIXO + Ecies.SOBRECARGA) {
			throw new PeerException("ack com " + ack.length + " bytes");
		}

		byte[] prefixo = Arrays.copyOfRange(ack, 0, PREFIXO);

		byte[] corpo = Ecies.decifrar(chave, Arrays.copyOfRange(ack, PREFIXO, ack.length), prefixo);

		List<RlpItem> campos = Rlp.paraLista(decodificarTolerante(corpo));

		if (campos.size() < 2) {
			throw new PeerException("ack com " + campos.size() + " campos, esperava ao menos 2");
		}

		byte[] efemeraRemota = Rlp.paraBytes(campos.get(0));
		byte[] nonceRemoto = Rlp.paraBytes(campos.get(1));

		if (efemeraRemota.length != Secp256k1.TAMANHO_ID) {
			throw new PeerException("Chave efêmera remota com " + efemeraRemota.length + " bytes");
		}

		if (nonceRemoto.length != TAMANHO_NONCE) {
			throw new PeerException("Nonce remoto com " + nonceRemoto.length + " bytes");
		}

		byte[] segredoEfemero = efemera.acordar(efemeraRemota);

		return new Segredos(segredoEfemero, nonce, nonceRemoto, true, authEnviado, ack);
	}

	byte[] nonce() {
		return nonce.clone();
	}

	private static RlpItem decodificarTolerante(byte[] corpo) {

		RlpLeitor leitor = new RlpLeitor(corpo);

		return leitor.ler();
	}

	private static byte[] comEnchimento(byte[] corpo) {

		byte[] enchimento = new byte[ENCHIMENTO_MINIMO + ALEATORIO.nextInt(ENCHIMENTO_VARIACAO)];

		ALEATORIO.nextBytes(enchimento);

		byte[] saida = Arrays.copyOf(corpo, corpo.length + enchimento.length);

		System.arraycopy(enchimento, 0, saida, corpo.length, enchimento.length);

		return saida;
	}

	private static byte[] xor(byte[] a, byte[] b) {

		byte[] saida = new byte[Keccak.TAMANHO];

		for (int i = 0; i < saida.length; i++) {
			saida[i] = (byte) (a[i] ^ b[i]);
		}

		return saida;
	}
}