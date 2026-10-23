package br.com.node.conferencia;
import br.com.node.baixa.sync.*;
import br.com.node.baixa.rpc.*;
import br.com.node.transformacao.catalogo.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.baixa.sync.Crivo;
import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Fabricas;
import br.com.node.transformacao.catalogo.Origem;
import br.com.node.transformacao.catalogo.Tarifario;
import br.com.node.transformacao.catalogo.Taxas;
import br.com.node.transformacao.nucleo.Direcao;
import br.com.node.transformacao.nucleo.EstadoV3;
import br.com.node.transformacao.nucleo.Par;
import br.com.node.transformacao.nucleo.PoolV2;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.baixa.sync.Cursor;
import br.com.node.baixa.sync.Frente;
import br.com.node.util.Resources;

class ConferenciaNaCadeiaTest {

	private static final int ESPERA_DESCOBERTA = 120;
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");

	private static final int RECUO_DE_PARTIDA = 400;
	private static final int BLOCOS_DE_APRENDIZADO = 256;
	private static final int POR_LOTE = 16;

	
	
	
	
	private static final int PERTO_DA_CABECA = 5;
	private static final int RODADAS_ATE_A_CABECA = 6;

	
	
	
	
	
	private static final Map<String, String> ROTEADOR_DA_FACTORY = Map.of("ca143ce32fe78f1f7019d7d551a6402fc5350c73",
			"10ed43c718714eb63d5aa57b78b54704e256024e", 
			"bcfccbde45ce874adcb698cc183debcf17952812", "05ff2b0db69458a0750badebc4f9e13add608c7f", 
			"858e3312ed3a876947ea49d572a7c42de08af7ee", "3a6d8ca21d1cf76f653a67577fa0d27453350dd8", 
			"0841bd0b734e4f5853f0dd8d7ea041c241fb0da6", "cf0febd3f17cef5b47b0cd257acf6025c5bff3b7", 
			"01bf7c66c6bd861915cdaae475042d3c4bae16a7", "cde540d7eafe93ac5fe6233bee57e1270d3e330f", 
			"3cd1c46068daea5ebb0d3f55f6915b10648062b8", "7dae51bd3e3376b8c7c4900e9107f12be3af1ba8", 
			"c35dadb65012ec5796536bd9864ed8773abc74c4", "1b02da8cb0d097eb8d57a175b88c7d8b47997506"); 

	private static final String GET_RESERVES = Abi.seletor("getReserves()");
	private static final String TOKEN0 = Abi.seletor("token0()");
	private static final String TOKEN1 = Abi.seletor("token1()");
	private static final String FACTORY = Abi.seletor("factory()");
	private static final String SLOT0 = Abi.seletor("slot0()");
	private static final String LIQUIDITY = Abi.seletor("liquidity()");
	private static final String FEE = Abi.seletor("fee()");
	private static final String TICK_SPACING = Abi.seletor("tickSpacing()");
	private static final String GET_AMOUNTS_OUT = Abi.seletor("getAmountsOut(uint256,address[])");

	private static final int LEITURAS_V2 = 4;
	private static final int LEITURAS_V3 = 7;

	private static final List<String> ESTRITAS = List.of("v2 reservas", "v2 tokens", "v2 factory pela identidade",
			"v3 preço", "v3 tick", "v3 liquidez", "v3 tokens", "v3 factory pela identidade", "v3 taxa",
			"v3 espaçamento", "v2 cotação (taxa do tarifário)");

	private final RpcDeConferencia rpc = new RpcDeConferencia();

	@Test
	@DisplayName("estado, tokens, identidade, taxa e espaçamento do node batem com o contrato na mesma altura")
	void batemComOContrato() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		Taxas taxas = new Taxas();
		Fabricas fabricas = new Fabricas();
		Tarifario tarifario = new Tarifario(catalogo, taxas, fabricas);
		Placar placar = new Placar();

		long altura;

		try {

			
			
			
			Cabeca cabeca = new Cabeca();

			node.iniciar();
			node.lotar(Crivo.corteDeHoje()::aceita, cabeca::anunciar);

			System.out.println("pool: " + node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA) + " sessões ativas");

			assertTrue(esperarAnuncio(cabeca), "nenhum bloco novo foi anunciado: sem cabeça não há foto perto dela");

			long partida = cabeca.alvo() - RECUO_DE_PARTIDA;
			int aprendidos = percorrer(node, catalogo, taxas, partida, BLOCOS_DE_APRENDIZADO);

			assertTrue(aprendidos > 0, "o cursor não aprendeu bloco nenhum");

			altura = chegarPertoDaCabeca(node, cabeca, catalogo, taxas, partida + aprendidos - 1);

			System.out.println("catálogo congelado na altura " + altura + ": " + catalogo.poolsV2() + " V2, "
					+ catalogo.poolsV3() + " V3, " + taxas.quantasProvadas() + " taxas provadas por troca");
			System.out.println("foto a " + (rpc.cabeca() - altura) + " blocos da cabeça da rede (anunciada ao node: "
					+ cabeca.alvo() + ")");

			List<String[]> tokensDaCadeia = conferirEstado(catalogo, tarifario, fabricas, altura, placar);

			conferirCotacoes(catalogo, tarifario, altura, tokensDaCadeia, placar);

			System.out.println("conferência terminou a " + (rpc.cabeca() - altura) + " blocos da cabeça da rede");

		} finally {
			node.close();
		}

		resumirIdentidade(catalogo, tarifario, fabricas);

		placar.imprimir();

		assertTrue(placar.conferidos("v2 reservas") > 0, "nenhuma reserva V2 foi conferida");
		assertTrue(placar.semRespostaEm("v2 reservas") < placar.conferidos("v2 reservas"),
				"a maioria ficou sem resposta: a altura " + altura + " saiu da janela de estado dos nós públicos");

		for (String categoria : ESTRITAS) {
			assertEquals(0, placar.divergentes(categoria), "o node diverge do contrato em: " + categoria);
		}
	}

	private static boolean esperarAnuncio(Cabeca cabeca) throws InterruptedException {

		long fim = System.currentTimeMillis() + ESPERA_DESCOBERTA * 1000L;

		while (cabeca.alvo() == 0 && System.currentTimeMillis() < fim) {
			Thread.sleep(500);
		}

		return cabeca.alvo() > 0;
	}

	private int percorrer(NodeDeTeste node, Catalogo catalogo, Taxas taxas, long partida, int quantos) {

		try (Frente frente = new Frente(node.sessoes, POR_LOTE);
				Cursor cursor = new Cursor(frente, catalogo, taxas::observar)) {

			frente.abrir(cursor::montar, POOL_MINIMO);

			return cursor.percorrer(partida, quantos, POR_LOTE);
		}
	}

	
	
	
	private long chegarPertoDaCabeca(NodeDeTeste node, Cabeca cabeca, Catalogo catalogo, Taxas taxas, long ultima) {

		for (int rodada = 0; rodada < RODADAS_ATE_A_CABECA; rodada++) {

			long alvo = cabeca.alvo() - PERTO_DA_CABECA;

			if (alvo - ultima <= PERTO_DA_CABECA) {
				break;
			}

			int andados = percorrer(node, catalogo, taxas, ultima + 1, (int) (alvo - ultima));

			if (andados == 0) {
				break;
			}

			ultima += andados;
		}

		return ultima;
	}

	
	
	
	private List<String[]> conferirEstado(Catalogo catalogo, Tarifario tarifario, Fabricas fabricas, long altura,
			Placar placar) throws InterruptedException {

		List<byte[]> v2 = catalogo.pools().stream().filter(pool -> catalogo.reservas(pool).isPresent()).toList();
		List<byte[]> v3 = catalogo.pools().stream().filter(pool -> catalogo.estado(pool).isPresent()).toList();

		List<Chamada> chamadas = new ArrayList<>();

		v2.forEach(pool -> List.of(GET_RESERVES, TOKEN0, TOKEN1, FACTORY)
				.forEach(seletor -> chamadas.add(new Chamada(Abi.hex(pool), seletor))));
		v3.forEach(pool -> List.of(SLOT0, LIQUIDITY, TOKEN0, TOKEN1, FEE, TICK_SPACING, FACTORY)
				.forEach(seletor -> chamadas.add(new Chamada(Abi.hex(pool), seletor))));

		List<Optional<String>> respostas = rpc.chamarAgregado(chamadas, altura);

		List<String[]> tokensDaCadeia = new ArrayList<>();

		for (int i = 0; i < v2.size(); i++) {
			tokensDaCadeia.add(conferirV2(v2.get(i), catalogo, fabricas,
					respostas.subList(i * LEITURAS_V2, (i + 1) * LEITURAS_V2), placar));
		}

		int base = v2.size() * LEITURAS_V2;

		for (int i = 0; i < v3.size(); i++) {
			conferirV3(v3.get(i), catalogo, tarifario, fabricas,
					respostas.subList(base + i * LEITURAS_V3, base + (i + 1) * LEITURAS_V3), placar);
		}

		return tokensDaCadeia;
	}

	private String[] conferirV2(byte[] pool, Catalogo catalogo, Fabricas fabricas, List<Optional<String>> r,
			Placar placar) {

		
		
		if (r.get(0).isEmpty()) {
			placar.semResposta("v2 reservas");
			return null;
		}

		ReservasV2 nossas = catalogo.reservas(pool).orElseThrow();
		BigInteger reserva0 = Abi.palavra(r.get(0).get(), 0);
		BigInteger reserva1 = Abi.palavra(r.get(0).get(), 1);

		placar.conferir("v2 reservas", reserva0.equals(nossas.reserva0()) && reserva1.equals(nossas.reserva1()),
				Abi.hex(pool) + " node " + nossas.reserva0() + "/" + nossas.reserva1() + " (altura " + nossas.altura()
						+ ") contrato " + reserva0 + "/" + reserva1);

		if (r.stream().skip(1).anyMatch(Optional::isEmpty)) {
			placar.semResposta("v2 tokens");
			return null;
		}

		String[] tokens = { Abi.endereco(r.get(1).get()), Abi.endereco(r.get(2).get()), Abi.endereco(r.get(3).get()) };

		catalogo.par(pool).ifPresent(par -> {
			conferirTokens("v2 tokens", pool, par, tokens, placar);
			conferirIdentidade("v2 factory pela identidade", pool, par, fabricas, tokens[2], placar);
		});

		return tokens;
	}

	private void conferirV3(byte[] pool, Catalogo catalogo, Tarifario tarifario, Fabricas fabricas,
			List<Optional<String>> r, Placar placar) {

		if (r.stream().limit(4).anyMatch(Optional::isEmpty)) {
			placar.semResposta("v3 preço");
			return;
		}

		EstadoV3 nosso = catalogo.estado(pool).orElseThrow();
		BigInteger sqrt = Abi.palavra(r.get(0).get(), 0);
		int tick = Abi.comSinal(r.get(0).get(), 1);
		BigInteger liquidez = Abi.palavra(r.get(1).get(), 0);
		String onde = Abi.hex(pool) + " (altura do node " + nosso.altura() + ")";

		placar.conferir("v3 preço", sqrt.equals(nosso.sqrtPrecoX96()),
				onde + " node " + nosso.sqrtPrecoX96() + " contrato " + sqrt);
		placar.conferir("v3 tick", tick == nosso.tick(), onde + " node " + nosso.tick() + " contrato " + tick);
		placar.conferir("v3 liquidez", liquidez.equals(nosso.liquidez()),
				onde + " node " + nosso.liquidez() + " contrato " + liquidez);

		String[] tokens = { Abi.endereco(r.get(2).get()), Abi.endereco(r.get(3).get()) };

		catalogo.par(pool).ifPresent(par -> {
			conferirTokens("v3 tokens", pool, par, tokens, placar);
			r.get(6).ifPresent(factory -> conferirIdentidade("v3 factory pela identidade", pool, par, fabricas,
					Abi.endereco(factory), placar));
		});

		conferirTarifarioV3(pool, tarifario, r.get(4), r.get(5), placar);
	}

	
	
	
	private void conferirTarifarioV3(byte[] pool, Tarifario tarifario, Optional<String> fee,
			Optional<String> espacamento, Placar placar) {

		Optional<Integer> taxa = tarifario.taxa(pool);
		OptionalInt espacamentoUsado = tarifario.espacamento(pool);

		if (taxa.isEmpty()) {
			return;
		}

		if (fee.isEmpty() || espacamento.isEmpty()) {
			placar.semResposta("v3 taxa");
			return;
		}

		int doContrato = Abi.palavra(fee.get(), 0).intValueExact();
		int espacamentoDoContrato = Abi.comSinal(espacamento.get(), 0);

		placar.conferir("v3 taxa", taxa.get() == doContrato,
				Abi.hex(pool) + " tarifário " + taxa.get() + " contrato " + doContrato);
		placar.conferir("v3 espaçamento",
				espacamentoUsado.isPresent() && espacamentoUsado.getAsInt() == espacamentoDoContrato,
				Abi.hex(pool) + " tarifário " + espacamentoUsado + " contrato " + espacamentoDoContrato);
	}

	private static void conferirTokens(String categoria, byte[] pool, Par par, String[] tokens, Placar placar) {

		String nosso0 = HexFormat.of().formatHex(par.token0());
		String nosso1 = HexFormat.of().formatHex(par.token1());

		placar.conferir(categoria, nosso0.equals(tokens[0]) && nosso1.equals(tokens[1]),
				Abi.hex(pool) + " node " + nosso0 + "/" + nosso1 + " contrato " + tokens[0] + "/" + tokens[1]);
	}

	
	
	
	private static void conferirIdentidade(String categoria, byte[] pool, Par par, Fabricas fabricas, String factory,
			Placar placar) {

		fabricas.de(pool, par).ifPresent(origem -> {

			String deduzida = HexFormat.of().formatHex(origem.fabrica().factory());

			placar.conferir(categoria, deduzida.equals(factory),
					Abi.hex(pool) + " deduzida " + origem.fabrica().nome() + " (" + deduzida + ") contrato " + factory);
		});
	}

	
	
	
	
	
	private void conferirCotacoes(Catalogo catalogo, Tarifario tarifario, long altura, List<String[]> tokensDaCadeia,
			Placar placar) throws InterruptedException {

		List<byte[]> v2 = catalogo.pools().stream().filter(pool -> catalogo.reservas(pool).isPresent()).toList();

		List<byte[]> cotadas = new ArrayList<>();
		List<BigInteger[]> contas = new ArrayList<>();
		List<Chamada> chamadas = new ArrayList<>();

		for (int i = 0; i < v2.size(); i++) {

			byte[] pool = v2.get(i);
			String[] tokens = tokensDaCadeia.get(i);
			Optional<Integer> taxa = tarifario.taxa(pool);

			if (tokens == null || taxa.isEmpty() || !ROTEADOR_DA_FACTORY.containsKey(tokens[2])) {
				continue;
			}

			ReservasV2 reservas = catalogo.reservas(pool).orElseThrow();
			BigInteger entrada = reservas.reserva0().divide(BigInteger.valueOf(1000));

			if (entrada.signum() <= 0) {
				continue;
			}

			BigInteger nossa = new PoolV2(reservas.reserva0(), reservas.reserva1(), taxa.get())
					.aplicar(Direcao.ZERO_PARA_UM, entrada).saida();

			cotadas.add(pool);
			contas.add(new BigInteger[] { entrada, nossa, BigInteger.valueOf(taxa.get()) });
			chamadas.add(new Chamada("0x" + ROTEADOR_DA_FACTORY.get(tokens[2]),
					getAmountsOut(entrada, tokens[0], tokens[1])));
		}

		List<Optional<String>> respostas = rpc.chamarAgregado(chamadas, altura);

		for (int i = 0; i < cotadas.size(); i++) {

			if (respostas.get(i).isEmpty()) {
				placar.semResposta("v2 cotação (taxa do tarifário)");
				continue;
			}

			BigInteger doRoteador = Abi.palavra(respostas.get(i).get(), 3);

			placar.conferir("v2 cotação (taxa do tarifário)", doRoteador.equals(contas.get(i)[1]),
					Abi.hex(cotadas.get(i)) + " taxa " + contas.get(i)[2] + " entrada " + contas.get(i)[0] + " node "
							+ contas.get(i)[1] + " roteador " + doRoteador);
		}
	}

	private static void resumirIdentidade(Catalogo catalogo, Tarifario tarifario, Fabricas fabricas) {

		Map<String, Integer> porFabrica = new TreeMap<>();
		int semParV2 = 0, semParV3 = 0, v3Precificaveis = 0, v3Fora = 0;

		for (byte[] pool : catalogo.pools()) {

			boolean v3 = catalogo.estado(pool).isPresent();
			Optional<Par> par = catalogo.par(pool);

			if (par.isEmpty()) {
				if (v3) {
					semParV3++;
				} else {
					semParV2++;
				}
				continue;
			}

			String nome = fabricas.de(pool, par.get()).map(Origem::fabrica).map(f -> f.nome())
					.orElse(v3 ? "V3 de factory desconhecida" : "V2 de factory desconhecida");

			porFabrica.merge(nome, 1, Integer::sum);

			if (v3) {
				if (tarifario.taxa(pool).isPresent()) {
					v3Precificaveis++;
				} else {
					v3Fora++;
				}
			}
		}

		System.out.println();
		System.out
				.println("identidade das pools pelo endereço (" + fabricas.quantasIdentificadas() + " identificadas):");
		porFabrica.forEach((nome, quantas) -> System.out.printf("   %-30s %5d%n", nome, quantas));
		System.out.println("   sem par aprendido: " + semParV2 + " V2, " + semParV3 + " V3");
		System.out.println(
				"   V3 precificáveis: " + v3Precificaveis + " · V3 fora (sem identidade ou em conflito): " + v3Fora);
		System.out.println("   pools em conflito identidade × prova por troca: " + tarifario.quantasEmConflito());
	}

	private static String getAmountsOut(BigInteger entrada, String token0, String token1) {
		return GET_AMOUNTS_OUT + Abi.palavra(entrada) + Abi.palavra(BigInteger.valueOf(0x40))
				+ Abi.palavra(BigInteger.TWO) + "000000000000000000000000" + token0 + "000000000000000000000000"
				+ token1;
	}
}