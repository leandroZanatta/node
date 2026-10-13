package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Fabricas;
import br.com.node.nucleo.Perna;
import br.com.node.vo.Log;
import br.com.node.vo.SwapPendente;

// Os três defeitos do projetor medidos na noite de 23→24/09 (retorno N+1 mediano de +314%):
// pool arbitrária entre as do mesmo par, direção pela ordem da calldata, token repetido no caminho.
class ProjetorCertoTest {

	private static final byte[] USDT = hex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] X = hex("aa".repeat(20));
	private static final byte[] PANCAKE_V2_FACTORY = hex("ca143ce32fe78f1f7019d7d551a6402fc5350c73");
	private static final byte[] PANCAKE_V2_ROTEADOR = hex("10ed43c718714eb63d5aa57b78b54704e256024e");
	// O par WBNB/USDT da Pancake V2, como está na cadeia.
	private static final byte[] PAR_PANCAKE = hex("16b9a82891338f9ba80e2d6970fdda79d1eb0dae");
	private static final byte[] OUTRA_POOL = hex("33".repeat(20));

	@Test
	void create2DaFactoryBateComOParDaCadeia() {
		assertArrayEquals(PAR_PANCAKE, Fabricas.parV2(PANCAKE_V2_FACTORY, WBNB, USDT).orElseThrow());
		assertArrayEquals(PAR_PANCAKE, Fabricas.parV2(PANCAKE_V2_FACTORY, USDT, WBNB).orElseThrow());
	}

	// Duas pools WBNB/USDT no catálogo: com o roteador da Pancake, a da factory dele; sem roteador, recusa
	// — antes era a primeira que aparecesse.
	@Test
	void poolVemDoRoteadorOuNaoVem() {

		Catalogo catalogo = new Catalogo();
		catalogo.aplicarSync(new Log(OUTRA_POOL, List.of(), reservas(1_000, 1_000)), 1L);
		catalogo.aplicarSync(new Log(PAR_PANCAKE, List.of(), reservas(1_000, 1_000)), 1L);
		catalogo.aprenderPar(OUTRA_POOL, List.of(USDT, WBNB));
		catalogo.aprenderPar(PAR_PANCAKE, List.of(USDT, WBNB));

		SwapPendente swap = new SwapPendente(BigInteger.TEN, List.of(USDT, WBNB));

		List<byte[]> doRoteador = Roteamento.mapearParaPools(swap, catalogo, PANCAKE_V2_ROTEADOR);
		assertEquals(1, doRoteador.size());
		assertArrayEquals(PAR_PANCAKE, doRoteador.get(0));

		assertTrue(Roteamento.mapearParaPools(swap, catalogo, null).isEmpty(), "com duas pools do par, sem roteador, chutou");
	}

	// Um bot que escreve [saída, entrada, montante, pool, entrada de novo]: o caminho aprendido tem de sair
	// na ordem da TROCA (X → WBNB), sem o token repetido, e com a pool lida da calldata.
	@Test
	void aprendeDirecaoEPoolSemRepetir() {

		List<ExemploDeMovimento> exemplos = new ArrayList<>();

		for (int i = 1; i <= 3; i++) {
			BigInteger montante = BigInteger.valueOf(1_000 + i);
			byte[] calldata = calldataDoBot(montante);
			exemplos.add(new ExemploDeMovimento(new TransacaoAnunciada(hex("77".repeat(20)), calldata), List.of(WBNB, X),
					montante, List.of(new Perna(OUTRA_POOL, X, WBNB))));
		}

		LayoutAprendido layout = AprendizDeLayout.aprender(exemplos, 3).orElseThrow();

		Optional<SwapPendente> lido = layout.ler(calldataDoBot(BigInteger.valueOf(55_555)));

		assertTrue(lido.isPresent());
		assertEquals(2, lido.get().caminho().size());
		assertArrayEquals(X, lido.get().caminho().get(0), "a direção saiu pela ordem da calldata");
		assertArrayEquals(WBNB, lido.get().caminho().get(1));
		assertEquals(BigInteger.valueOf(55_555), lido.get().entra());
		assertArrayEquals(OUTRA_POOL, lido.get().pools().get(0));
		assertEquals(3, layout.confirmados());
	}

	private static byte[] calldataDoBot(BigInteger montante) {

		byte[] dados = new byte[4 + 20 + 20 + 32 + 20 + 20];
		dados[0] = 0x12;
		dados[1] = 0x34;
		dados[2] = 0x56;
		dados[3] = 0x78;
		System.arraycopy(WBNB, 0, dados, 4, 20);
		System.arraycopy(X, 0, dados, 24, 20);
		byte[] m = montante.toByteArray();
		System.arraycopy(m, 0, dados, 44 + 32 - m.length, m.length);
		System.arraycopy(OUTRA_POOL, 0, dados, 76, 20);
		System.arraycopy(X, 0, dados, 96, 20);
		return dados;
	}

	private static byte[] reservas(long r0, long r1) {
		byte[] dados = new byte[64];
		byte[] a = BigInteger.valueOf(r0).toByteArray();
		byte[] b = BigInteger.valueOf(r1).toByteArray();
		System.arraycopy(a, 0, dados, 32 - a.length, a.length);
		System.arraycopy(b, 0, dados, 64 - b.length, b.length);
		return dados;
	}

	private static byte[] hex(String h) {
		return HexFormat.of().parseHex(h);
	}
}
