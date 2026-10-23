package br.com.node.mempool;
import br.com.node.transformacao.mempool.*;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Eventos;
import br.com.node.baixa.protocolo.Rlp;
import br.com.node.util.Keccak;
import br.com.node.vo.Log;
import br.com.node.vo.SwapPendente;

class LoopDeAprendizadoTest {

	private static final byte[] SELETOR = HexFormat.of().parseHex("1a2b3c4d");

	private static final byte[] CONTRATO = endereco(70);
	private static final byte[] A = endereco(1);
	private static final byte[] B = endereco(2);

	private static ObservatorioMempool observatorio(AtomicInteger projetadas) {
		Catalogo catalogo = new Catalogo();
		catalogo.aprenderPar(CONTRATO, List.of(A, B));
		catalogo.aplicarSync(new Log(CONTRATO, List.of(Eventos.SYNC_V2), juntar(palavra(100_000), palavra(200_000))),
				1);
		return new ObservatorioMempool(catalogo, pool -> Optional.of(2500),
				(overlay, crua) -> projetadas.incrementAndGet());
	}

	private static byte[] calldata(long entrada, byte[]... tokens) {

		byte[] dados = new byte[4 + 32 + 20 * tokens.length];
		System.arraycopy(SELETOR, 0, dados, 0, 4);

		byte[] m = BigInteger.valueOf(entrada).toByteArray();
		System.arraycopy(m, 0, dados, 4 + 32 - m.length, m.length);

		for (int i = 0; i < tokens.length; i++) {
			System.arraycopy(tokens[i], 0, dados, 4 + 32 + 20 * i, 20);
		}

		return dados;
	}

	private static byte[] crua(byte[] calldata) {
		return Rlp.codificar(Rlp.lista(Rlp.inteiro(1), Rlp.inteiro(1), Rlp.inteiro(100_000), Rlp.bytes(CONTRATO),
				Rlp.inteiro(0), Rlp.bytes(calldata), Rlp.inteiro(37), Rlp.inteiro(1), Rlp.inteiro(1)));
	}

	private static String hashDa(byte[] crua) {
		return HexFormat.of().formatHex(Keccak.hash(crua));
	}

	private static Log swapV2(long a0in, long a1in, long a0out, long a1out) {
		return new Log(CONTRATO, List.of(Eventos.SWAP_V2),
				juntar(palavra(a0in), palavra(a1in), palavra(a0out), palavra(a1out)));
	}

	private static void ciclo(ObservatorioMempool mempool, byte[] calldataCrua, long entrada) {
		byte[] crua = crua(calldataCrua);
		mempool.observar(null, new br.com.node.vo.Transactions(List.of(crua)));
		mempool.aguardarLotes();
		mempool.blocoFechado(Map.of(hashDa(crua), List.of(swapV2(entrada, 0, 0, entrada - 10))));
	}

	@Test
	@DisplayName("primeiras tx não projetam (opacas); após 3 blocos o caderno lê local")
	void aprendeEPorRoteamentoNaSegunda() {

		AtomicInteger projetadas = new AtomicInteger();
		ObservatorioMempool mempool = observatorio(projetadas);

		byte[] primeira = calldata(1000, A, B);
		mempool.observar(null, new br.com.node.vo.Transactions(List.of(crua(primeira))));
		mempool.aguardarLotes();

		assertEquals(0, projetadas.get(), "formato opaco: roteamento não lê, e o caderno ainda não aprendeu");

		ciclo(mempool, primeira, 1000);
		ciclo(mempool, calldata(2000, A, B), 2000);
		ciclo(mempool, calldata(3000, A, B), 3000);

		Optional<SwapPendente> lido = mempool.lerDoCaderno(CONTRATO, calldata(500, A, B));
		assertTrue(lido.isPresent(), "três exemplos pareados têm de virar regra que lê local");
		assertEquals(BigInteger.valueOf(500), lido.get().entra());

		mempool.observar(null, new br.com.node.vo.Transactions(List.of(crua(calldata(500, A, B)))));
		mempool.aguardarLotes();

		assertTrue(projetadas.get() >= 1, "com a regra viva, a tx opaca projeta pelo caderno");
	}

	@Test
	@DisplayName("transação que não entrou no bloco não vira exemplo — sem rótulo, sem aprendizado")
	void anunciadaSemConfirmaNaoAprende() {

		ObservatorioMempool mempool = observatorio(new AtomicInteger());

		mempool.observar(null, new br.com.node.vo.Transactions(List.of(crua(calldata(1000, A, B)))));
		mempool.aguardarLotes();

		mempool.blocoFechado(Map.of());

		assertTrue(mempool.lerDoCaderno(CONTRATO, calldata(500, A, B)).isEmpty(),
				"sem confirma no bloco, o caderno não aprendeu");
	}

	@Test
	@DisplayName("conferência conta acerto no placar; caderno mantém a regra viva")
	void conferirContaAcertoNoPlacar() {

		ObservatorioMempool mempool = observatorio(new AtomicInteger());

		ciclo(mempool, calldata(1000, A, B), 1000);
		ciclo(mempool, calldata(500, A, B), 500);
		ciclo(mempool, calldata(700, A, B), 700);
		ciclo(mempool, calldata(900, A, B), 900);

		Map<String, long[]> placar = mempool.placarDoCaderno();
		assertEquals(1, placar.size(), "um contrato aprendido = um placar");
		long[] c = placar.values().iterator().next();
		assertEquals(2, c[0], "duas declarações registradas após a regra nascer no 3o bloco");
		assertEquals(2, c[1], "duas confirmações contra o gabarito");
	}

	private static byte[] juntar(byte[]... partes) {

		byte[] junto = new byte[partes.length * 32];
		for (int i = 0; i < partes.length; i++) {
			System.arraycopy(partes[i], 0, junto, i * 32, 32);
		}

		return junto;
	}

	private static byte[] palavra(long valor) {

		byte[] bytes = BigInteger.valueOf(valor).toByteArray();
		byte[] palavra = new byte[32];
		System.arraycopy(bytes, 0, palavra, 32 - bytes.length, bytes.length);

		return palavra;
	}

	private static byte[] endereco(int semente) {
		byte[] endereco = new byte[20];
		new Random(semente).nextBytes(endereco);
		return endereco;
	}
}