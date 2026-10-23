package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Eventos;
import br.com.node.nucleo.Arbitragem;
import br.com.node.vo.Log;

class AutopsiaDoMercadoTest {

	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] A = hex("aa".repeat(20));
	private static final byte[] B = hex("bb".repeat(20));
	private static final byte[] P1 = hex("01".repeat(20));
	private static final byte[] P2 = hex("02".repeat(20));
	private static final byte[] P3 = hex("03".repeat(20));
	private static final byte[] ROTEADOR = hex("0e".repeat(20));
	private static final byte[] COBRADOR = hex("0f".repeat(20));
	private static final byte[] EXECUTOR = hex("ee".repeat(20));

	private final AutopsiaDoMercado autopsia = new AutopsiaDoMercado(new Catalogo(), null, null, null, null,
			Path.of("target", "teste-mercado.jsonl"));

	@Test
	@DisplayName("três pernas WBNB→A→B→WBNB, com roteador e cobrador no meio, viram um ciclo começando no lucro")
	void cicloDeTresPernas() {

		List<Log> logs = List.of(
				
				
				transfer(WBNB, EXECUTOR, ROTEADOR), transfer(WBNB, ROTEADOR, P1), transfer(A, P1, P2), 
																										
				transfer(B, P2, P3), transfer(B, P2, COBRADOR), 
				transfer(WBNB, P3, EXECUTOR));

		Arbitragem arbitragem = new Arbitragem(List.of(ROTEADOR, P1, P2, P3, COBRADOR), WBNB, BigInteger.TEN, 3, 0);

		List<AutopsiaDoMercado.PernaObservada> pernas = autopsia.pernas(arbitragem, logs);

		assertEquals(3, pernas.size(), "roteador e cobrador não são troca");
		assertArrayEquals(P1, pernas.get(0).pool());
		assertArrayEquals(WBNB, pernas.get(0).entra());
		assertArrayEquals(A, pernas.get(0).sai());
		assertArrayEquals(P3, pernas.get(2).pool());

		List<AutopsiaDoMercado.PernaObservada> ciclo = AutopsiaDoMercado.emCiclo(pernas, WBNB).orElseThrow();
		assertArrayEquals(WBNB, ciclo.get(0).entra(), "o ciclo começa no token do lucro");
	}

	@Test
	@DisplayName("o ciclo gira para começar no token do lucro, mesmo que a primeira troca do recibo seja outra")
	void giraParaOLucro() {

		List<AutopsiaDoMercado.PernaObservada> pernas = List.of(new AutopsiaDoMercado.PernaObservada(P2, A, B, 1),
				new AutopsiaDoMercado.PernaObservada(P3, B, WBNB, 2),
				new AutopsiaDoMercado.PernaObservada(P1, WBNB, A, 3));

		List<AutopsiaDoMercado.PernaObservada> ciclo = AutopsiaDoMercado.emCiclo(pernas, WBNB).orElseThrow();

		assertArrayEquals(P1, ciclo.get(0).pool());
		assertArrayEquals(P2, ciclo.get(1).pool());
		assertArrayEquals(P3, ciclo.get(2).pool());
	}

	@Test
	@DisplayName("rota dividida (duas pernas saindo do mesmo token) não é ciclo")
	void rotaDivididaNaoEhCiclo() {

		List<AutopsiaDoMercado.PernaObservada> pernas = List.of(new AutopsiaDoMercado.PernaObservada(P1, WBNB, A, 1),
				new AutopsiaDoMercado.PernaObservada(P2, WBNB, B, 2),
				new AutopsiaDoMercado.PernaObservada(P3, B, WBNB, 3));

		assertTrue(AutopsiaDoMercado.emCiclo(pernas, WBNB).isEmpty());
	}

	@Test
	@DisplayName("rota dividida (WBNB→A por duas pools que se juntam) vira dois ciclos simples, um por caminho")
	void rotaDivididaViraCiclosSimples() {

		byte[] p4 = hex("04".repeat(20));
		List<AutopsiaDoMercado.PernaObservada> pernas = List.of(new AutopsiaDoMercado.PernaObservada(P1, WBNB, A, 1),
				new AutopsiaDoMercado.PernaObservada(P2, WBNB, A, 2),
				new AutopsiaDoMercado.PernaObservada(P3, A, WBNB, 3));

		List<List<AutopsiaDoMercado.PernaObservada>> ciclos = AutopsiaDoMercado.ciclos(pernas, WBNB);

		assertEquals(2, ciclos.size());
		assertArrayEquals(P1, ciclos.get(0).get(0).pool());
		assertArrayEquals(P2, ciclos.get(1).get(0).pool());

		
		List<AutopsiaDoMercado.PernaObservada> duas = List.of(new AutopsiaDoMercado.PernaObservada(P1, WBNB, A, 1),
				new AutopsiaDoMercado.PernaObservada(P2, A, WBNB, 2),
				new AutopsiaDoMercado.PernaObservada(P3, WBNB, B, 3),
				new AutopsiaDoMercado.PernaObservada(p4, B, WBNB, 4));

		assertEquals(2, AutopsiaDoMercado.ciclos(duas, WBNB).size());
	}

	@Test
	@DisplayName("perna de gerente sem chave conhecida fica sem tokens, e o ciclo não se afirma")
	void gerenteSemChave() {

		byte[] poolId = hex("d3".repeat(32));
		byte[] dados = new byte[6 * 32];
		dados[31] = (byte) 0xff; 

		Log swap = new Log(Eventos.GERENTE_INFINITY_CL, List.of(Eventos.SWAP_INFINITY_CL, poolId, new byte[32]),
				new byte[7 * 32]);
		Arbitragem arbitragem = new Arbitragem(List.of(P1, poolId), WBNB, BigInteger.ONE, 1, 0);

		List<AutopsiaDoMercado.PernaObservada> pernas = autopsia.pernas(arbitragem,
				List.of(transfer(WBNB, EXECUTOR, P1), transfer(A, P1, EXECUTOR), swap));

		assertEquals(2, pernas.size());
		assertTrue(AutopsiaDoMercado.emCiclo(pernas, WBNB).isEmpty(), "sem os lados da chave não há ciclo");
	}

	private static Log transfer(byte[] token, byte[] de, byte[] para) {

		List<byte[]> topicos = new ArrayList<>();
		topicos.add(Eventos.TRANSFER);
		topicos.add(palavra(de));
		topicos.add(palavra(para));
		byte[] valor = new byte[32];
		valor[31] = 1;

		return new Log(token, topicos, valor);
	}

	private static byte[] palavra(byte[] endereco) {
		byte[] p = new byte[32];
		System.arraycopy(endereco, 0, p, 12, 20);
		return p;
	}

	private static byte[] hex(String h) {
		return HexFormat.of().parseHex(h);
	}
}