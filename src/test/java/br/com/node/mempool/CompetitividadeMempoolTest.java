package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Catalogo;
import br.com.node.vo.Log;
import br.com.node.vo.SwapPendente;

// Traduce a números determinísticos a pergunta de viabilidade: a decisão cabe na janela do bloco?
//
// O `AtrasoTest` mediu na BSC viva a folga que existe entre ANUNCIAR o bloco e nós havelo
// aplicado: mediana 332 ms (docs/premissas.md). Essa é a janela que a projeção da mempool (P21)
// tem para calcular o efeito de um swap pendente antes de a fenda fechar — o próprio docs diz:
// "os 340 ms de folga são o orçamento que a proyeção tem para calcular". Si decodificar +
// projetar + avaliar estoura essa janela, a execução mais rápida do mundo não salva nada.
//
// O que falta para competir (contrato, assinatura, envio) não se prova aqui porque não existe.
// Se prova a metade que existe: que a decisión cabe holgadamente na janela, a escala real de
// pools que o AtrasoTest mediu (1902), e que a fronteira do gas decide como decide na corrida.
class CompetitividadeMempoolTest {

	private static final long JANELA_MS = 340;

	// Generoso de propósito: o código real corre em microsegundos por swap. O limite só caza
	// uma regresión catastrófica; o número que importa é o que se imprime na corrida.
	private static final long ORCAMENTO_MS = 2_000;

	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	@Test
	@DisplayName("decodifica calldata real de swapExactTokensForTokens (a matéria-prima da mempool)")
	void decodificaCalldataReal() {

		byte[] usdt = hex("55d398326f99059ff775485246999027b3197955");
		byte[] busd = hex("e9e7be1d5af13bfa7c933dd2d4f4f337b5126a93");

		byte[] calldata = swapExactTokensParaTokens(500_000, 100_000, List.of(usdt, WBNB, busd),
				hex("1111111111111111111111111111111111111111"), 1_800_000_000L);

		Optional<SwapPendente> swap = Roteamento.decodificar(calldata);

		assertTrue(swap.isPresent(), "a calldata de swapExactTokensForTokens tem que decodificar");
		assertEquals(BigInteger.valueOf(500_000), swap.get().entra());
		assertEquals(3, swap.get().caminho().size());
		assertTrue(java.util.Arrays.equals(usdt, swap.get().caminho().get(0)));
		assertTrue(java.util.Arrays.equals(WBNB, swap.get().caminho().get(1)));
		assertTrue(java.util.Arrays.equals(busd, swap.get().caminho().get(2)));
	}

	@Test
	@DisplayName("calldata que não é swapExactTokensForTokens não decodifica: não inventa fenda")
	void noDecodificaLoQueNãoESwap() {

		// Seletor de `transfer(address,uint256)` — o que mais chega na mempool, sem fenda que projetar.
		byte[] calldata = new byte[4 + 64];
		copiar(calldata, 0, hex("a9059cbb"));
		copiar(calldata, 4, palabra(0));
		copiar(calldata, 36, palabra(1_000_000));

		assertTrue(Roteamento.decodificar(calldata).isEmpty());

		// Calldata truncada: apuntar a um path que não existe deve virar vazio, nunca excepción.
		byte[] truncada = swapExactTokensParaTokens(500_000, 0, List.of(WBNB, WBNB, WBNB),
				hex("1111111111111111111111111111111111111111"), 0);
		byte[] corta = new byte[truncada.length - 8];
		System.arraycopy(truncada, 0, corta, 0, corta.length);

		assertTrue(Roteamento.decodificar(corta).isEmpty());
	}

	@Test
	@DisplayName("decodificar + projetar + avaliar cabe na janela do bloco, com catálogo a escala real")
	void projecaoCabeNaJanelaDoBloco() {

		Catalogo catalogo = catalogoEscalaReal();

		byte[] entra = token(1000);
		byte[] sai = token(1001);

		int quantos = 250;
		List<byte[]> calldatas = new ArrayList<>();

		for (int i = 0; i < quantos; i++) {
			calldatas.add(swapExactTokensParaTokens(500_000 + i, 0, List.of(entra, WBNB, sai),
					hex("1111111111111111111111111111111111111111"), 1_800_000_000L));
		}

		long[] vezes = new long[quantos];
		int positivas = 0;

		long inicio = System.nanoTime();

		for (int i = 0; i < quantos; i++) {

			long t0 = System.nanoTime();

			Optional<SwapPendente> swap = Roteamento.decodificar(calldatas.get(i));
			assertTrue(swap.isPresent(), "a calldata gerada pelo teste tem que decodificar");

			MargemDeChegada margem = MargemDeChegada.avaliar(swap.get(), catalogo, pool -> Optional.of(2500),
					BigInteger.valueOf(100_000));

			vezes[i] = System.nanoTime() - t0;

			if (margem.chegaAntes()) {
				positivas++;
			}
		}

		long totalMs = (System.nanoTime() - inicio) / 1_000_000;

		java.util.Arrays.sort(vezes);
		double medianaUs = vezes[quantos / 2] / 1_000.0;
		double p90Us = vezes[(int) (quantos * 0.9)] / 1_000.0;

		System.out.printf("Competitividade: %d swaps · decodificar+projetar+avaliar: total %d ms · "
				+ "mediana %.0f µs · p90 %.0f µs · positivas %d · janela medida %d ms%n",
				quantos, totalMs, medianaUs, p90Us, positivas, JANELA_MS);

		assertTrue(positivas > 0, "no catálogo do teste a projeção tem que dar margem positiva");
		assertTrue(totalMs < ORCAMENTO_MS,
				"a decisão estourou o orçamento: " + totalMs + " ms para " + quantos + " swaps (a janela medida é "
						+ JANELA_MS + " ms)");
	}

	@Test
	@DisplayName("a fronteira do gas: custo de execução que come a fenda vira a decisão")
	void oGasComeAFenda() {

		Catalogo catalogo = catalogoPequeno();

		byte[] usdt = hex("55d398326f99059ff775485246999027b3197955");
		byte[] busd = hex("e9e7be1d5af13bfa7c933dd2d4f4f337b5126a93");

		SwapPendente swap = new SwapPendente(BigInteger.valueOf(500_000), List.of(usdt, WBNB, busd));

		// A fenda é sempre menor que a entrada (quem decide é a curva); um custo de 100x a
		// entrada a come inteira, aconteça o que acontecer na curva.
		MargemDeChegada semCusto = MargemDeChegada.avaliar(swap, catalogo, pool -> Optional.of(2500), BigInteger.ZERO);
		MargemDeChegada custosa = MargemDeChegada.avaliar(swap, catalogo, pool -> Optional.of(2500),
				BigInteger.valueOf(500_000).multiply(BigInteger.valueOf(100)));

		assertTrue(semCusto.chegaAntes(), "sem custo a fenda projetada é positiva");
		assertFalse(custosa.chegaAntes(), "um custo 100x a entrada não pode deixar margem");
	}

	// --- catálogos ----------------------------------------------------------

	// Escala real: as 1902 pools que o AtrasoTest mediu como conhecidas. O path do teste golpeia
	// as pools 1000 e 1001, então o barrido de `mapearParaPools` percorre ~mil pools por perna,
	// como em produção — e a decisão ainda tem que caber na janela.
	private static Catalogo catalogoEscalaReal() {

		Catalogo catalogo = new Catalogo();

		for (int i = 0; i < 2000; i++) {

			byte[] pool = pool(i);
			catalogo.aprenderPar(pool, List.of(token(i), WBNB));

			if (i == 1000) {
				// Leg 1 entra por token(1000): reserva0 (lado de entrada) pequena, WBNB grande.
				catalogo.aplicarSync(new Log(pool, List.of(), bytesReservas(1_000, 1_000_000)), 1L);
			} else if (i == 1001) {
				// Leg 2 entra por WBNB = token1: reserva1 pequena, token(1001) grande.
				catalogo.aplicarSync(new Log(pool, List.of(), bytesReservas(1_000_000, 1_000)), 1L);
			} else {
				catalogo.aplicarSync(new Log(pool, List.of(), bytesReservas(10_000_000, 10_000_000)), 1L);
			}
		}

		return catalogo;
	}

	private static Catalogo catalogoPequeno() {

		Catalogo catalogo = new Catalogo();

		byte[] usdt = hex("55d398326f99059ff775485246999027b3197955");
		byte[] busd = hex("e9e7be1d5af13bfa7c933dd2d4f4f337b5126a93");

		byte[] pool1 = hex("1111111111111111111111111111111111111111");
		byte[] pool2 = hex("2222222222222222222222222222222222222222");

		catalogo.aprenderPar(pool1, List.of(usdt, WBNB));
		catalogo.aprenderPar(pool2, List.of(WBNB, busd));
		catalogo.aplicarSync(new Log(pool1, List.of(), bytesReservas(1_000, 1_000_000)), 1L);
		catalogo.aplicarSync(new Log(pool2, List.of(), bytesReservas(1_000, 1_000_000)), 1L);

		return catalogo;
	}

	// --- calldata ABI de swapExactTokensForTokens(uint,uint,address[],address,uint) --
	// seletor 38ed1739 · head de 5 palavras (amountIn, amountOutMin, offset do path, to,
	// deadline) · cauda: tamanho + endereços. O offset do path é relativo ao início dos
	// argumentos: 5 palavras = 0xa0.
	private static byte[] swapExactTokensParaTokens(long entra, long minSai, List<byte[]> caminho, byte[] para,
			long deadline) {

		int n = caminho.size();

		byte[] calldata = new byte[4 + 5 * 32 + 32 * (1 + n)];

		copiar(calldata, 0, hex("38ed1739"));
		copiar(calldata, 4, palabra(entra));
		copiar(calldata, 36, palabra(minSai));
		copiar(calldata, 68, palabra(160));
		copiar(calldata, 100, direccion(para));
		copiar(calldata, 132, palabra(deadline));
		copiar(calldata, 164, palabra(n));

		for (int i = 0; i < n; i++) {
			copiar(calldata, 164 + 32 + i * 32, direccion(caminho.get(i)));
		}

		return calldata;
	}

	private static byte[] palabra(long valor) {

		byte[] bytes = new byte[32];
		byte[] bruto = BigInteger.valueOf(valor).toByteArray();

		System.arraycopy(bruto, 0, bytes, 32 - bruto.length, bruto.length);

		return bytes;
	}

	private static byte[] direccion(byte[] endereco20) {
		byte[] bytes = new byte[32];
		System.arraycopy(endereco20, 0, bytes, 12, endereco20.length);
		return bytes;
	}

	private static void copiar(byte[] destino, int desde, byte[] fonte) {
		System.arraycopy(fonte, 0, destino, desde, fonte.length);
	}

	private static byte[] bytesReservas(long reserva0, long reserva1) {

		byte[] dados = new byte[64];

		byte[] r0 = BigInteger.valueOf(reserva0).toByteArray();
		byte[] r1 = BigInteger.valueOf(reserva1).toByteArray();

		System.arraycopy(r0, 0, dados, 32 - r0.length, r0.length);
		System.arraycopy(r1, 0, dados, 64 - r1.length, r1.length);

		return dados;
	}

	private static byte[] token(int i) {
		return hex(String.format("%040d", i));
	}

	private static byte[] pool(int i) {
		return hex("1" + String.format("%039d", i));
	}

	private static byte[] hex(String texto) {
		String semPrefixo = texto.startsWith("0x") ? texto.substring(2) : texto;
		return HexFormat.of().parseHex(semPrefixo);
	}
}