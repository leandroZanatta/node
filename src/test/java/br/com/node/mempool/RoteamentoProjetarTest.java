package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Catalogo;
import br.com.node.mempool.MargemDeChegada;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Passo;
import br.com.node.nucleo.PoolV2;
import br.com.node.vo.Log;
import br.com.node.vo.SwapPendente;

class RoteamentoProjetarTest {

	@Test
	@DisplayName("mapeia o caminho do swap para as pools conhecidas do catálogo")
	void mapeiaOPathParaPools() {

		Catalogo catalogo = new Catalogo();

		byte[] usdt = hex("55d398326f99059ff775485246999027b3197955");
		byte[] wbnb = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
		byte[] busd = hex("e9e7be1d5af13bfa7c933dd2d4f4f337b5126a93");
		byte[] pool1 = hex("1111111111111111111111111111111111111111");
		byte[] pool2 = hex("2222222222222222222222222222222222222222");

		catalogo.aplicarSync(new Log(pool1, List.of(), bytesReservas(1_000_000, 500_000)), 1L);
		catalogo.aplicarSync(new Log(pool2, List.of(), bytesReservas(2_000_000, 2_000_000)), 1L);
		catalogo.aprenderPar(pool1, List.of(usdt, wbnb));
		catalogo.aprenderPar(pool2, List.of(wbnb, busd));

		SwapPendente swap = new SwapPendente(BigInteger.TEN, List.of(usdt, wbnb, busd));

		List<byte[]> pools = Roteamento.mapearParaPools(swap, catalogo);

		assertEquals(2, pools.size());
		assertArrayEquals(pool1, pools.get(0));
		assertArrayEquals(pool2, pools.get(1));
	}

	@Test
	@DisplayName("projeta o swap pendente sobre reservas conhecidas de cada pool")
	void projetaSwapSobreReservasConhecidas() {

		Catalogo catalogo = new Catalogo();

		byte[] usdt = hex("55d398326f99059ff775485246999027b3197955");
		byte[] wbnb = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
		byte[] busd = hex("e9e7be1d5af13bfa7c933dd2d4f4f337b5126a93");
		byte[] pool1 = hex("1111111111111111111111111111111111111111");
		byte[] pool2 = hex("2222222222222222222222222222222222222222");

		catalogo.aprenderPar(pool1, List.of(usdt, wbnb));
		catalogo.aprenderPar(pool2, List.of(wbnb, busd));
		catalogo.aplicarSync(new Log(pool1, List.of(), bytesReservas(10_000_000, 5_000_000)), 1L);
		catalogo.aplicarSync(new Log(pool2, List.of(), bytesReservas(20_000_000, 10_000_000)), 1L);

		SwapPendente swap = new SwapPendente(BigInteger.valueOf(500_000), List.of(usdt, wbnb, busd));

		Optional<List<Passo>> passos = Roteamento.projetar(swap, catalogo, pool -> Optional.of(2500));

		assertTrue(passos.isPresent(), "swap com path e pools conhecidas devia projetar");
		assertEquals(2, passos.get().size());

		BigInteger entrada = swap.entra();
		Passo primeiro = new PoolV2(BigInteger.valueOf(10_000_000), BigInteger.valueOf(5_000_000), 2500)
				.aplicar(Direcao.ZERO_PARA_UM, entrada);
		Passo segundo = new PoolV2(BigInteger.valueOf(20_000_000), BigInteger.valueOf(10_000_000), 2500)
				.aplicar(Direcao.ZERO_PARA_UM, primeiro.saida());

		assertEquals(segundo.saida(), passos.get().get(1).saida());
	}

	@Test
	@DisplayName("mede se a projeção dá margem para chegar antes dos outros")
	void medeMargemDeChegada() {

		Catalogo catalogo = new Catalogo();

		byte[] usdt = hex("55d398326f99059ff775485246999027b3197955");
		byte[] wbnb = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
		byte[] busd = hex("e9e7be1d5af13bfa7c933dd2d4f4f337b5126a93");
		byte[] pool1 = hex("1111111111111111111111111111111111111111");
		byte[] pool2 = hex("2222222222222222222222222222222222222222");

		catalogo.aprenderPar(pool1, List.of(usdt, wbnb));
		catalogo.aprenderPar(pool2, List.of(wbnb, busd));
		catalogo.aplicarSync(new Log(pool1, List.of(), bytesReservas(1_000, 1_000_000)), 1L);
		catalogo.aplicarSync(new Log(pool2, List.of(), bytesReservas(1_000, 1_000_000)), 1L);

		SwapPendente swap = new SwapPendente(BigInteger.valueOf(500_000), List.of(usdt, wbnb, busd));
		MargemDeChegada margem = MargemDeChegada.avaliar(swap, catalogo, pool -> Optional.of(2500),
				BigInteger.valueOf(1_000));

		assertTrue(margem.chegaAntes(), "a projeção devia dar margem positiva para chegar antes");
		assertTrue(margem.margemEmPontosBase() > 0, "a margem em bps devia ser positiva para a projeção");
	}

	private static byte[] bytesReservas(long reserva0, long reserva1) {
		byte[] dados = new byte[64];
		System.arraycopy(BigInteger.valueOf(reserva0).toByteArray(), 0, dados, 32 - Math.min(32, BigInteger.valueOf(reserva0).toByteArray().length), Math.min(32, BigInteger.valueOf(reserva0).toByteArray().length));
		System.arraycopy(BigInteger.valueOf(reserva1).toByteArray(), 0, dados, 64 - Math.min(32, BigInteger.valueOf(reserva1).toByteArray().length), Math.min(32, BigInteger.valueOf(reserva1).toByteArray().length));
		return dados;
	}

	private static byte[] hex(String texto) {
		String semPrefixo = texto.startsWith("0x") ? texto.substring(2) : texto;
		return java.util.HexFormat.of().parseHex(semPrefixo);
	}
}
