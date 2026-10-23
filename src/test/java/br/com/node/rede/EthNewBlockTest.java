package br.com.node.rede;
import br.com.node.baixa.protocolo.*;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.exception.PeerException;
import br.com.node.transformacao.mempool.TransacaoPendente;
import br.com.node.baixa.protocolo.Rlp;
import br.com.node.baixa.protocolo.RlpItem;
import br.com.node.util.Keccak;
import br.com.node.vo.Cabecalho;
import br.com.node.vo.MensagemEth;
import br.com.node.vo.NewBlock;

class EthNewBlockTest {

	private static final long ALTURA = 12_345_678L;

	private static final byte[] PAI = HexFormat.of().parseHex("aa".repeat(32));

	private static final byte[] PARA = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");

	@Test
	@DisplayName("decodifica o NewBlock: header como no BlockHeaders e transações cruas preservadas")
	void decodifica() {

		RlpItem cabecalho = cabecalho(ALTURA, PAI);

		byte[] tx1 = transacaoLegado();

		byte[] tx2 = Rlp.codificar(Rlp.lista(Rlp.inteiro(7), Rlp.bytes(PARA)));

		byte[] corpo = corpo(cabecalho, Rlp.decodificar(tx1), Rlp.decodificar(tx2));

		NewBlock bloco = (NewBlock) Eth.deRlp(MensagemEth.NEW_BLOCK, corpo);

		assertEquals(ALTURA, bloco.cabecalho().numero());
		assertArrayEquals(Keccak.hash(Rlp.codificar(cabecalho)), bloco.cabecalho().hash());
		assertArrayEquals(PAI, bloco.cabecalho().hashDoPai());
		assertEquals(2, bloco.transacoes().size());
		assertArrayEquals(tx1, bloco.transacoes().get(0));

		TransacaoPendente pendente = TransacaoPendente.decodificar(bloco.transacoes().get(0)).orElseThrow();
		assertArrayEquals(PARA, pendente.destino());

		assertArrayEquals(tx2, bloco.transacoes().get(1));
	}

	
	
	
	
	@Test
	@DisplayName("decodifica o NewBlock como vem do par: [bloco, dificuldade total, sidecars]")
	void formaDoFio() {

		RlpItem cabecalho = cabecalho(ALTURA, PAI);
		RlpItem bloco = Rlp.lista(cabecalho, Rlp.lista(Rlp.decodificar(transacaoLegado())), Rlp.lista());
		byte[] corpo = Rlp.codificar(Rlp.lista(bloco, Rlp.inteiro(123_456_789L), Rlp.lista()));

		NewBlock novo = (NewBlock) Eth.deRlp(MensagemEth.NEW_BLOCK, corpo);

		assertEquals(ALTURA, novo.cabecalho().numero());
		assertArrayEquals(Keccak.hash(Rlp.codificar(cabecalho)), novo.cabecalho().hash());
		assertArrayEquals(PAI, novo.cabecalho().hashDoPai());
		assertEquals(1, novo.transacoes().size());
	}

	@Test
	@DisplayName("decodifica bloco sem transações")
	void blocoVazio() {

		byte[] corpo = corpo(cabecalho(ALTURA, PAI));

		NewBlock bloco = (NewBlock) Eth.deRlp(MensagemEth.NEW_BLOCK, corpo);

		assertEquals(ALTURA, bloco.cabecalho().numero());
		assertTrue(bloco.transacoes().isEmpty());
	}

	@Test
	@DisplayName("NewBlock só chega: codificar lança")
	void naoSai() {

		NewBlock bloco = new NewBlock(new Cabecalho(ALTURA, new byte[32], PAI), List.of());

		assertThrows(PeerException.class, () -> Eth.paraRlp(bloco));
	}

	@Test
	@DisplayName("o 0x17 do fio que a lotação contava como não tratado 23 vira NewBlock")
	void wire017ViraNewBlock() {

		byte[] corpo = corpo(cabecalho(ALTURA, PAI), Rlp.decodificar(transacaoLegado()));

		byte[] codigo = Rlp.codificar(Rlp.inteiro(0x17));

		byte[] carga = new byte[codigo.length + corpo.length];

		System.arraycopy(codigo, 0, carga, 0, codigo.length);
		System.arraycopy(corpo, 0, carga, codigo.length, corpo.length);

		NewBlock bloco = (NewBlock) new Rlpx().ler(carga);

		assertEquals(ALTURA, bloco.cabecalho().numero());
		assertEquals(1, bloco.transacoes().size());
	}

	
	private static RlpItem cabecalho(long numero, byte[] pai) {

		RlpItem[] campos = new RlpItem[15];

		for (int i = 0; i < campos.length; i++) {
			campos[i] = Rlp.bytes(new byte[0]);
		}

		campos[0] = Rlp.bytes(pai);
		campos[8] = Rlp.inteiro(numero);

		return Rlp.lista(campos);
	}

	private static byte[] transacaoLegado() {

		return Rlp.codificar(Rlp.lista(Rlp.inteiro(0), Rlp.inteiro(1_000_000_000L), Rlp.inteiro(21_000L),
				Rlp.bytes(PARA), Rlp.inteiro(new BigInteger("5000000000000000000")), Rlp.bytes(new byte[0])));
	}

	private static byte[] corpo(RlpItem cabecalho, RlpItem... transacoes) {
		return Rlp.codificar(Rlp.lista(cabecalho, Rlp.lista(transacoes), Rlp.lista()));
	}
}