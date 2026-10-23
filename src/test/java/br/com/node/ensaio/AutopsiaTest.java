package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.Quarentena;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Perna;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.Travessia;

class AutopsiaTest {

	private static final String USDT = "55d398326f99059ff775485246999027b3197955";
	private static final String WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
	private static final String POOL_A = "7a3eb6e7ffbf9f8b65afd40c0ab0ac2bab0c5d8e";
	private static final String POOL_B = "47a90a2d92a8367a91efa1906bfc8c1e05bf10c4";

	
	
	
	@Test
	void calldataIgualAoCast() {

		List<Salto> rota = List.of(new Salto(POOL_A, USDT, WBNB, false, 5), new Salto(POOL_B, WBNB, USDT, true, 0));
		String cast = "d66c35a6" + "0000000000000000000000000000000000000000000000000000000000000040"
				+ "00000000000000000000000000000000000000000000000223a33008637eddd8"
				+ "0000000000000000000000000000000000000000000000000000000000000002"
				+ "0000000000000000000000007a3eb6e7ffbf9f8b65afd40c0ab0ac2bab0c5d8e"
				+ "00000000000000000000000055d398326f99059ff775485246999027b3197955"
				+ "000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"
				+ "0000000000000000000000000000000000000000000000000000000000000000"
				+ "0000000000000000000000000000000000000000000000000000000000000005"
				+ "00000000000000000000000047a90a2d92a8367a91efa1906bfc8c1e05bf10c4"
				+ "000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"
				+ "00000000000000000000000055d398326f99059ff775485246999027b3197955"
				+ "0000000000000000000000000000000000000000000000000000000000000001"
				+ "0000000000000000000000000000000000000000000000000000000000000000";

		assertEquals(cast, HexFormat.of().formatHex(Autopsia.calldata(rota, new BigInteger("39461437172537548248"))));
	}

	
	
	
	@Test
	void decodificaOLaudoDaCadeia() {

		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"execution reverted\",\"data\":\"0x69119bfe"
				+ "0000000000000000000000000000000000000000000000000000000000000120000000000000000000000000000000000000000000000000000000000000018000000000000000000000000000000000000000000000000000000000000001e0000000000000000000000000000000000000000000000000000000000000024000000000000000000000000000000000000000000000000000000000000002a00000000000000000000000000000000000000000000000000000000000000300ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000000000000000000000000000000000000000000000000000000000000036084800812000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000006bde5df08c7f1e66a914000000000000000000000000000000000000000009404d74473a06c269bf222b00000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000240c572d410e8f8bf20000000000000000000000000000000000000000000011ef938d6a2aa9f38c5e000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000223a33008637eddd800000000000000000000000000000000000000000000000000b6e83f29c6cbb0000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000223a33008637eddd800000000000000000000000000000000000000000000000000b6e83f29c6cbb0000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000b6e83f29c6cbb0000000000000000000000000000000000000000000000002230c59a0773549ac000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000b6e83f29c6cbb0000000000000000000000000000000000000000000000002230c59a0773549ac0000000000000000000000000000000000000000000000000000000000000000\"}}";

		Autopsia.Laudo laudo = Autopsia.lerLaudo(corpo).orElseThrow();

		assertEquals("84800812", laudo.callback());
		assertEquals(-1, laudo.falhouEm());
		assertEquals(new BigInteger("39461437172537548248"), laudo.enviado().get(0));
		assertEquals(new BigInteger("0b6e83f29c6cbb0", 16), laudo.chegou().get(0));
		assertEquals(new BigInteger("2230c59a0773549ac", 16), laudo.chegou().get(1));
		assertEquals(new BigInteger("6bde5df08c7f1e66a914", 16), laudo.estadoA().get(0));
		assertEquals("(revert sem dado)", laudo.motivo());
	}

	
	
	
	@Test
	void apontaAPernaQueDivergeEOMotivo() {

		PoolV2 a = new PoolV2(new BigInteger("500000000000000000000000"), new BigInteger("650000000000000000000"),
				2500);
		PoolV2 bModelo = new PoolV2(new BigInteger("700000000000000000000"), new BigInteger("600000000000000000000000"),
				2500);
		BigInteger bCadeiaR0 = new BigInteger("735000000000000000000");

		Fixture f = fixture(a, bModelo);

		BigInteger chegou0 = new Travessia(a, Direcao.ZERO_PARA_UM).aplicar(f.montante).saida();
		BigInteger modelo1 = new Travessia(bModelo, Direcao.ZERO_PARA_UM).aplicar(chegou0).saida();
		BigInteger chegou1 = modelo1.multiply(BigInteger.valueOf(9)).divide(BigInteger.TEN);

		Autopsia.Laudo laudo = new Autopsia.Laudo(List.of(a.reserva0(), bCadeiaR0),
				List.of(a.reserva1(), bModelo.reserva1()), List.of(f.montante, chegou0), List.of(f.montante, chegou0),
				List.of(chegou0, chegou1), List.of(chegou0, chegou1), -1, "", "84800812");

		Map<String, Object> registro = autopsia().comparar(f.achado, f.chamada, laudo);

		@SuppressWarnings("unchecked")
		Map<String, Object> culpada = (Map<String, Object>) registro.get("culpada");
		assertNotNull(culpada);
		assertEquals(1, culpada.get("indice"));
		assertEquals("ESTADO", culpada.get("classe"));
		assertEquals(1000L, culpada.get("desvio_bps"));
		assertEquals(false, registro.get("fecharia_agora"));
		assertEquals(true, registro.get("callback_conhecido"));
	}

	
	
	
	
	
	@Test
	void impostoAntesDoRevertLevaACulpaEFicaGravado() {

		PoolV2 a = new PoolV2(new BigInteger("500000000000000000000000"), new BigInteger("650000000000000000000"),
				2500);
		PoolV2 b = new PoolV2(new BigInteger("700000000000000000000"), new BigInteger("600000000000000000000000"),
				2500);
		Fixture f = fixture(a, b);

		BigInteger prometido0 = new Travessia(a, Direcao.ZERO_PARA_UM).aplicar(f.montante).saida();
		BigInteger chegou0 = prometido0.divide(BigInteger.TWO);

		Autopsia.Laudo laudo = new Autopsia.Laudo(List.of(a.reserva0(), b.reserva0()),
				List.of(a.reserva1(), b.reserva1()), List.of(f.montante, BigInteger.ZERO),
				List.of(f.montante, BigInteger.ZERO), List.of(prometido0, BigInteger.ZERO),
				List.of(chegou0, BigInteger.ZERO), 1, "ds-math-sub-underflow", "84800812");

		Impostos impostos = new Impostos();
		Autopsia autopsia = new Autopsia(new RpcDoEnsaio(), new Quarentena(), impostos,
				Path.of("target", "autopsia-teste.jsonl"));
		Map<String, Object> registro = autopsia.comparar(f.achado, f.chamada, laudo);

		@SuppressWarnings("unchecked")
		Map<String, Object> culpada = (Map<String, Object>) registro.get("culpada");
		assertEquals("IMPOSTO", culpada.get("classe"));
		assertEquals(0, culpada.get("indice"));

		autopsia.gravarImpostosDasPernas(registro);
		
		
		assertTrue(impostos.compra(HexFormat.of().parseHex(POOL_A), HexFormat.of().parseHex(WBNB)).getAsInt() >= 4_999,
				"o imposto de compra de 50% não ficou gravado na direção certa");
	}

	@Test
	void impostoVenceEstadoNaClassificacao() {
		assertEquals(Autopsia.Classe.OK, Autopsia.classificar(30, 500, 0, 500));
		assertEquals(Autopsia.Classe.IMPOSTO, Autopsia.classificar(900, 0, 800, 0));
		assertEquals(Autopsia.Classe.ESTADO, Autopsia.classificar(900, 0, 0, 40));
		assertEquals(Autopsia.Classe.CURVA_OU_TAXA, Autopsia.classificar(900, 0, 0, 0));
	}

	@Test
	void reconheceOInvarianteDosForks() {
		assertTrue(Autopsia.violouInvariante("Pancake: K"));
		assertTrue(Autopsia.violouInvariante("K"));
		assertFalse(Autopsia.violouInvariante("TransferHelper: TRANSFER_FAILED"));
	}

	@Test
	void perdaSoContaOLadoQueFazReverter() {
		assertEquals(0, Autopsia.perda(BigInteger.valueOf(100), BigInteger.valueOf(120)));
		assertEquals(2500, Autopsia.perda(BigInteger.valueOf(100), BigInteger.valueOf(75)));
	}

	
	
	
	@Test
	void laudoDaCadeiaDeVerdade() {

		List<Salto> rota = List.of(new Salto(POOL_A, USDT, WBNB, false, 25), new Salto(POOL_B, WBNB, USDT, true, 0));
		com.google.gson.JsonObject codigo = new com.google.gson.JsonObject();
		codigo.addProperty("code", "0x" + new String(lerRecurso(), java.nio.charset.StandardCharsets.US_ASCII).trim());
		com.google.gson.JsonObject override = new com.google.gson.JsonObject();
		override.add("0xcdf83fc9c7c62e3545c4891b7e26e4d6a27c97e5", codigo);

		Optional<Autopsia.Laudo> laudo = new RpcDoEnsaio().chamarComOverride("e5c933480364616cbc0319a5a5841bc3d0e3eb94",
				"cdf83fc9c7c62e3545c4891b7e26e4d6a27c97e5",
				HexFormat.of().formatHex(Autopsia.calldata(rota, new BigInteger("1000000000000000000"))), override)
				.flatMap(Autopsia::lerLaudo);

		assertTrue(laudo.isPresent(), "a cadeia não devolveu laudo");
		assertEquals("84800812", laudo.get().callback());
		assertEquals(-1, laudo.get().falhouEm());
		assertFalse(laudo.get().chegou().get(1).signum() <= 0);
	}

	
	
	
	
	
	@Test
	void achaATaxaQueAPoolCobraDeNos() {

		List<Salto> rota = List.of(new Salto("16b9a82891338f9ba80e2d6970fdda79d1eb0dae", USDT, WBNB, false, 25),
				new Salto(POOL_A, WBNB, USDT, false, 25));

		Optional<Integer> taxa = autopsia().sondarTaxa("e5c933480364616cbc0319a5a5841bc3d0e3eb94",
				"cdf83fc9c7c62e3545c4891b7e26e4d6a27c97e5",
				new RotaDoModelo.Chamada(rota, new BigInteger("10000000000000000000"), BigInteger.ONE), 1, "latest");

		assertEquals(Optional.of(1_000), taxa);
	}

	private static byte[] lerRecurso() {
		try (var in = AutopsiaTest.class.getResourceAsStream("/autopsia-runtime.hex")) {
			return in.readAllBytes();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private record Fixture(Achado achado, RotaDoModelo.Chamada chamada, BigInteger montante) {
	}

	
	private static Fixture fixture(PoolV2 a, PoolV2 b) {

		byte[] usdt = HexFormat.of().parseHex(USDT);
		byte[] wbnb = HexFormat.of().parseHex(WBNB);
		BigInteger montante = new BigInteger("10000000000000000000");

		List<Travessia> travessias = List.of(new Travessia(a, Direcao.ZERO_PARA_UM),
				new Travessia(b, Direcao.ZERO_PARA_UM));
		BigInteger saida = travessias.get(1).aplicar(travessias.get(0).aplicar(montante).saida()).saida();

		Achado achado = new Achado(1,
				new Ciclo(List.of(new Perna(HexFormat.of().parseHex(POOL_A), usdt, wbnb),
						new Perna(HexFormat.of().parseHex(POOL_B), wbnb, usdt))),
				new Oportunidade(montante, saida), travessias);

		RotaDoModelo.Chamada chamada = new RotaDoModelo.Chamada(
				List.of(new Salto(POOL_A, USDT, WBNB, false, 25), new Salto(POOL_B, WBNB, USDT, false, 25)), montante,
				BigInteger.ONE);

		return new Fixture(achado, chamada, montante);
	}

	private static Autopsia autopsia() {
		return new Autopsia(new RpcDoEnsaio(), new Quarentena(), new Impostos(),
				Path.of("target", "autopsia-teste.jsonl"));
	}

	
	
	@Test
	void proibicaoDoParNaoEhEstadoPassageiro() {
		org.junit.jupiter.api.Assertions.assertTrue(Autopsia.proibicao("DEMAX PAIR : FORBIDDEN"));
		org.junit.jupiter.api.Assertions.assertFalse(Autopsia.proibicao("Pancake: INSUFFICIENT_LIQUIDITY"));
	}
}