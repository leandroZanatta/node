package br.com.node.rede;
import br.com.node.baixa.protocolo.*;


import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.node.baixa.protocolo.Rlp;
import br.com.node.vo.NewPooledTransactionHashes;

class EthAnuncioDeTransacoesTest {

	@Test
	void guardaOTamanhoDeCadaHash() {

		byte[] a = new byte[32];
		byte[] b = new byte[32];
		b[0] = 1;

		byte[] corpo = Rlp.codificar(Rlp.lista(Rlp.bytes(new byte[] { 2, 0 }),
				Rlp.lista(Rlp.inteiro(110), Rlp.inteiro(612)), Rlp.lista(Rlp.bytes(a), Rlp.bytes(b))));

		NewPooledTransactionHashes anuncio = (NewPooledTransactionHashes) Eth.deRlp(0x08, corpo);

		assertEquals(2, anuncio.hashes().size());
		assertEquals(110, anuncio.tamanho(0));
		assertEquals(612, anuncio.tamanho(1));
	}

	@Test
	void formatoAntigoSemTamanho() {

		byte[] corpo = Rlp.codificar(Rlp.lista(List.of(Rlp.bytes(new byte[32]))));

		NewPooledTransactionHashes anuncio = (NewPooledTransactionHashes) Eth.deRlp(0x08, corpo);

		assertEquals(1, anuncio.hashes().size());
		assertEquals(-1, anuncio.tamanho(0));
	}
}