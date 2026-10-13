package br.com.node.rede;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import br.com.node.exception.PeerException;
import br.com.node.rlp.Rlp;

public final class Quadros {

	private static final int BLOCO = 16;
	private static final int CABECALHO = 16;
	private static final int MAC = 16;

	private static final byte[] DADOS_CABECALHO = Rlp.codificar(Rlp.lista(Rlp.inteiro(0), Rlp.inteiro(0)));

	private final Segredos segredos;

	private final SICBlockCipher cifraEgresso;
	private final SICBlockCipher cifraIngresso;

	private final AESEngine aesMac;

	private final ByteArrayOutputStream acumulado = new ByteArrayOutputStream();

	private int corpoEsperado = -1;

	public Quadros(Segredos segredos) {

		this.segredos = segredos;

		this.cifraEgresso = fluxo(segredos.aes());
		this.cifraIngresso = fluxo(segredos.aes());

		this.aesMac = new AESEngine();

		this.aesMac.init(true, new KeyParameter(segredos.mac()));
	}

	// --- escrita --------------------------------------------------------------

	public synchronized byte[] escrever(byte[] carga) {

		byte[] cabecalho = new byte[CABECALHO];

		cabecalho[0] = (byte) (carga.length >>> 16);
		cabecalho[1] = (byte) (carga.length >>> 8);
		cabecalho[2] = (byte) carga.length;

		System.arraycopy(DADOS_CABECALHO, 0, cabecalho, 3, DADOS_CABECALHO.length);

		byte[] cabecalhoCifrado = new byte[CABECALHO];

		cifraEgresso.processBytes(cabecalho, 0, CABECALHO, cabecalhoCifrado, 0);

		byte[] macCabecalho = atualizarMac(segredos.egresso(), cabecalhoCifrado);

		byte[] corpo = preencher(carga);
		byte[] corpoCifrado = new byte[corpo.length];

		cifraEgresso.processBytes(corpo, 0, corpo.length, corpoCifrado, 0);

		segredos.egresso().update(corpoCifrado, 0, corpoCifrado.length);

		byte[] macCorpo = atualizarMac(segredos.egresso(), null);

		byte[] quadro = new byte[CABECALHO + MAC + corpoCifrado.length + MAC];

		int pos = 0;

		pos = copiar(cabecalhoCifrado, quadro, pos);
		pos = copiar(macCabecalho, quadro, pos);
		pos = copiar(corpoCifrado, quadro, pos);

		copiar(macCorpo, quadro, pos);

		return quadro;
	}

	// --- leitura --------------------------------------------------------------

	public synchronized byte[] ler(byte[] pedaco) {

		if (pedaco != null) {
			acumulado.writeBytes(pedaco);
		}

		if (corpoEsperado < 0 && !lerCabecalho()) {
			return null;
		}

		return lerCorpo();
	}

	private boolean lerCabecalho() {

		if (acumulado.size() < CABECALHO + MAC) {
			return false;
		}

		byte[] buffer = acumulado.toByteArray();

		byte[] cabecalhoCifrado = Arrays.copyOfRange(buffer, 0, CABECALHO);
		byte[] macRecebido = Arrays.copyOfRange(buffer, CABECALHO, CABECALHO + MAC);

		byte[] macEsperado = atualizarMac(segredos.ingresso(), cabecalhoCifrado);

		if (!java.security.MessageDigest.isEqual(macRecebido, macEsperado)) {
			throw new PeerException("MAC do cabeçalho RLPx não confere");
		}

		byte[] cabecalho = new byte[CABECALHO];

		cifraIngresso.processBytes(cabecalhoCifrado, 0, CABECALHO, cabecalho, 0);

		corpoEsperado = ((cabecalho[0] & 0xff) << 16) | ((cabecalho[1] & 0xff) << 8) | (cabecalho[2] & 0xff);

		consumir(buffer, CABECALHO + MAC);

		return true;
	}

	private byte[] lerCorpo() {

		int corpoCifradoTamanho = preenchido(corpoEsperado);

		int total = corpoCifradoTamanho + MAC;

		if (acumulado.size() < total) {
			return null;
		}

		byte[] buffer = acumulado.toByteArray();

		byte[] corpoCifrado = Arrays.copyOfRange(buffer, 0, corpoCifradoTamanho);
		byte[] macRecebido = Arrays.copyOfRange(buffer, corpoCifradoTamanho, total);

		segredos.ingresso().update(corpoCifrado, 0, corpoCifrado.length);

		byte[] macEsperado = atualizarMac(segredos.ingresso(), null);

		if (!java.security.MessageDigest.isEqual(macRecebido, macEsperado)) {
			throw new PeerException("MAC do corpo RLPx não confere");
		}

		byte[] corpo = new byte[corpoCifradoTamanho];

		cifraIngresso.processBytes(corpoCifrado, 0, corpoCifradoTamanho, corpo, 0);

		byte[] carga = Arrays.copyOfRange(corpo, 0, corpoEsperado);

		corpoEsperado = -1;

		consumir(buffer, total);

		return carga;
	}

	private void consumir(byte[] buffer, int quantos) {

		acumulado.reset();
		acumulado.write(buffer, quantos, buffer.length - quantos);
	}
	// --- MAC ------------------------------------------------------------------

	private byte[] atualizarMac(KeccakDigest digest, byte[] dado) {

		byte[] resumo = Segredos.resumo(digest);

		byte[] cifrado = new byte[BLOCO];

		aesMac.processBlock(resumo, 0, cifrado, 0);

		byte[] base = dado != null ? dado : resumo;

		for (int i = 0; i < BLOCO; i++) {
			cifrado[i] ^= base[i];
		}

		digest.update(cifrado, 0, BLOCO);

		return Arrays.copyOfRange(Segredos.resumo(digest), 0, MAC);
	}

	// --- apoio ----------------------------------------------------------------

	private static SICBlockCipher fluxo(byte[] chave) {

		SICBlockCipher cifra = new SICBlockCipher(new AESEngine());

		cifra.init(true, new ParametersWithIV(new KeyParameter(chave), new byte[BLOCO]));

		return cifra;
	}

	private static int preenchido(int tamanho) {
		return tamanho % BLOCO == 0 ? tamanho : tamanho + BLOCO - tamanho % BLOCO;
	}

	private static byte[] preencher(byte[] carga) {
		return carga.length % BLOCO == 0 ? carga : Arrays.copyOf(carga, preenchido(carga.length));
	}

	private static int copiar(byte[] origem, byte[] destino, int pos) {

		System.arraycopy(origem, 0, destino, pos, origem.length);

		return pos + origem.length;
	}
}
