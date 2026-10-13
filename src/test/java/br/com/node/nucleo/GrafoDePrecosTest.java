package br.com.node.nucleo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// O detector de ciclo negativo, determinístico e sem rede.
//
// Caso canônico da arb caseira: o MESMO par WBNB/A em dois DEXes com preços diferentes — compra A
// barato num, vende caro no outro. É um 2-leg cross-DEX, o tipo que sobra nas pools esquecidas. O
// detector tem de achá-lo, começando no numerário; e, quando os preços batem (sem fenda) ou o ciclo
// não toca numerário, tem de devolver vazio — candidato falso é pior que nenhum.
class GrafoDePrecosTest {

	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = hex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] A = hex("aa".repeat(20));
	private static final byte[] B = hex("bb".repeat(20));
	private static final byte[] POOL_BARATO = hex("11".repeat(20));
	private static final byte[] POOL_CARO = hex("22".repeat(20));
	private static final byte[] POOL_CARO_B = hex("66".repeat(20));
	private static final byte[] POOL_V3 = hex("33".repeat(20));
	private static final byte[] POOL_A_USDT = hex("44".repeat(20));
	private static final byte[] POOL_USDT_WBNB = hex("55".repeat(20));
	private static final int TAXA = 2500; // 0,25% em milionésimos

	/** 2^96 — preço 1:1 no formato do V3. */
	private static final BigInteger Q96 = BigInteger.TWO.pow(96);

	private static final Set<String> NUMERARIOS = Set.of(HexFormat.of().formatHex(WBNB));
	private static final Set<String> DOIS_NUMERARIOS = Set.of(HexFormat.of().formatHex(WBNB),
			HexFormat.of().formatHex(USDT));

	@Test
	@DisplayName("copiar o grafo sem uma pool e religá-la dá os MESMOS ciclos que remontar do zero")
	void copiaComPoolReligadaEquivaleARemontar() {

		// Três pools num triângulo WBNB→A→USDT→WBNB; a do meio muda de estado (o swap pendente).
		GrafoDePrecos base = new GrafoDePrecos();
		base.ligarV2(POOL_BARATO, new Par(WBNB, A), BigInteger.valueOf(1_000), BigInteger.valueOf(1_000), TAXA);
		base.ligarV2(POOL_A_USDT, new Par(A, USDT), BigInteger.valueOf(1_000), BigInteger.valueOf(1_000), TAXA);
		base.ligarV2(POOL_USDT_WBNB, new Par(USDT, WBNB), BigInteger.valueOf(1_000), BigInteger.valueOf(1_000), TAXA);

		String meio = HexFormat.of().formatHex(POOL_A_USDT);
		BigInteger novaA = BigInteger.valueOf(1_000);
		BigInteger novaUsdt = BigInteger.valueOf(1_300); // o swap pendente deixou USDT barato na do meio

		GrafoDePrecos copia = base.semAsPools(Set.of(meio));
		copia.ligarV2(POOL_A_USDT, new Par(A, USDT), novaA, novaUsdt, TAXA);

		GrafoDePrecos doZero = new GrafoDePrecos();
		doZero.ligarV2(POOL_BARATO, new Par(WBNB, A), BigInteger.valueOf(1_000), BigInteger.valueOf(1_000), TAXA);
		doZero.ligarV2(POOL_A_USDT, new Par(A, USDT), novaA, novaUsdt, TAXA);
		doZero.ligarV2(POOL_USDT_WBNB, new Par(USDT, WBNB), BigInteger.valueOf(1_000), BigInteger.valueOf(1_000), TAXA);

		List<Ciclo> daCopia = copia.ciclosPela(Set.of(meio), NUMERARIOS, 4, 5);
		List<Ciclo> doRemonte = doZero.ciclosPela(Set.of(meio), NUMERARIOS, 4, 5);

		assertEquals(1, doRemonte.size(), "o remonte acha o triângulo lucrativo");
		assertEquals(doRemonte.size(), daCopia.size());
		for (int i = 0; i < doRemonte.size(); i++) {
			assertEquals(doRemonte.get(i).pernas().size(), daCopia.get(i).pernas().size());
			for (int j = 0; j < doRemonte.get(i).pernas().size(); j++) {
				assertTrue(java.util.Arrays.equals(doRemonte.get(i).pernas().get(j).pool(), daCopia.get(i).pernas().get(j).pool()));
				assertTrue(java.util.Arrays.equals(doRemonte.get(i).pernas().get(j).entra(), daCopia.get(i).pernas().get(j).entra()));
			}
		}

		// E a base não foi tocada: continua sem ciclo lucrativo pela pool do meio.
		assertTrue(base.ciclosPela(Set.of(meio), NUMERARIOS, 4, 5).isEmpty(), "a cópia não pode alterar a base");
	}

	@Test
	@DisplayName("acha o 2-leg cross-DEX lucrativo e o começa no numerário")
	void achaCrossDex() {

		GrafoDePrecos grafo = new GrafoDePrecos();
		Par par = new Par(WBNB, A);

		// POOL_BARATO: 100 WBNB : 200 A → A barato (compra A com WBNB aqui).
		grafo.ligarV2(POOL_BARATO, par, BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);
		// POOL_CARO: 200 WBNB : 100 A → A caro (vende A por WBNB aqui). Produto ≈ 3,98 > 1.
		grafo.ligarV2(POOL_CARO, par, BigInteger.valueOf(200), BigInteger.valueOf(100), TAXA);

		Optional<Ciclo> ciclo = grafo.cicloNegativo(NUMERARIOS);

		assertTrue(ciclo.isPresent(), "havia arb (produto das taxas > 1) e não achou");

		List<Perna> pernas = ciclo.get().pernas();
		assertEquals(2, pernas.size(), "o ciclo cross-DEX tem duas pernas");
		assertEquals(hex(WBNB), hex(pernas.get(0).entra()), "tem de começar entrando no numerário (WBNB)");
		assertEquals(hex(WBNB), hex(pernas.get(1).sai()), "e voltar ao numerário no fim");
		assertEquals(hex(A), hex(pernas.get(0).sai()), "primeira perna compra A");
	}

	// O N+1 procura a fenda que o SWAP abriu. Com uma fenda-poeira enorme em outro canto do grafo
	// (WBNB/B, produto ~4), a busca ancorada nas pools tocadas tem de devolver o ciclo WBNB/A que passa
	// por elas — e nada quando o swap não abriu fenda nenhuma ali.
	@Test
	@DisplayName("N+1: acha o ciclo pela pool tocada, não a fenda alheia")
	void cicloPelaPoolTocada() {

		GrafoDePrecos grafo = new GrafoDePrecos();

		// Fenda alheia, maior, sem relação com o swap.
		grafo.ligarV2(POOL_CARO_B, new Par(WBNB, B), BigInteger.valueOf(100), BigInteger.valueOf(400), TAXA);
		grafo.ligarV2(POOL_V3, new Par(WBNB, B), BigInteger.valueOf(400), BigInteger.valueOf(100), TAXA);

		// A fenda que o swap abriu: POOL_BARATO (tocada) ficou 10% fora da POOL_CARO.
		grafo.ligarV2(POOL_BARATO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(110), TAXA);
		grafo.ligarV2(POOL_CARO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(100), TAXA);

		Optional<Ciclo> ciclo = grafo.cicloPela(Set.of(HexFormat.of().formatHex(POOL_BARATO)), NUMERARIOS, 4);

		assertTrue(ciclo.isPresent(), "o swap abriu fenda na pool tocada e a busca não achou");
		Set<String> pools = new java.util.HashSet<>();
		ciclo.get().pernas().forEach(p -> pools.add(hex(p.pool())));
		assertEquals(Set.of(hex(POOL_BARATO), hex(POOL_CARO)), pools, "devolveu a fenda alheia em vez da do swap");
		assertEquals(hex(WBNB), hex(ciclo.get().pernas().get(0).entra()));

		assertFalse(grafo.cicloPela(Set.of(HexFormat.of().formatHex(POOL_CARO_B).replace('6', '7')), NUMERARIOS, 4)
				.isPresent(), "pool fora do grafo não pode produzir ciclo");
	}

	// Um bloco abre várias fendas: o garimpo tem de ver TODAS as que passam pelas pools mexidas, em ordem
	// de lucro marginal, uma por conjunto de pools — não só a melhor.
	@Test
	@DisplayName("devolve as fendas distintas pelas pools tocadas, da maior para a menor")
	void variasFendasPelasPoolsTocadas() {

		GrafoDePrecos grafo = new GrafoDePrecos();

		// Fenda grande em WBNB/B e pequena em WBNB/A.
		grafo.ligarV2(POOL_CARO_B, new Par(WBNB, B), BigInteger.valueOf(100), BigInteger.valueOf(130), TAXA);
		grafo.ligarV2(POOL_V3, new Par(WBNB, B), BigInteger.valueOf(100), BigInteger.valueOf(100), TAXA);
		grafo.ligarV2(POOL_BARATO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(105), TAXA);
		grafo.ligarV2(POOL_CARO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(100), TAXA);

		List<Ciclo> ciclos = grafo.ciclosPela(Set.of(hex(POOL_CARO_B), hex(POOL_BARATO)), NUMERARIOS, 4, 5);

		assertEquals(2, ciclos.size(), "eram duas fendas distintas");
		Set<String> maior = new java.util.HashSet<>();
		ciclos.get(0).pernas().forEach(p -> maior.add(hex(p.pool())));
		assertEquals(Set.of(hex(POOL_CARO_B), hex(POOL_V3)), maior, "a maior fenda tem de vir primeiro");
	}

	@Test
	@DisplayName("sem fenda (preços iguais) não inventa ciclo")
	void semFendaVazio() {

		GrafoDePrecos grafo = new GrafoDePrecos();
		Par par = new Par(WBNB, A);

		// Preços iguais nos dois: produto das taxas < 1 (a fee come). Não há arb.
		grafo.ligarV2(POOL_BARATO, par, BigInteger.valueOf(100), BigInteger.valueOf(100), TAXA);
		grafo.ligarV2(POOL_CARO, par, BigInteger.valueOf(100), BigInteger.valueOf(100), TAXA);

		assertFalse(grafo.cicloNegativo(NUMERARIOS).isPresent(), "sem fenda não devia achar ciclo");
	}

	@Test
	@DisplayName("ciclo lucrativo que não toca numerário é recusado (não temos como emprestar/precificar)")
	void semNumerarioVazio() {

		GrafoDePrecos grafo = new GrafoDePrecos();

		// Mesma fenda, mas entre A e B — sem WBNB/USDT no ciclo.
		grafo.ligarV2(POOL_BARATO, new Par(A, B), BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);
		grafo.ligarV2(POOL_CARO, new Par(A, B), BigInteger.valueOf(200), BigInteger.valueOf(100), TAXA);

		assertFalse(grafo.cicloNegativo(NUMERARIOS).isPresent(), "ciclo sem numerário não é executável, devia dar vazio");
	}

	@Test
	@DisplayName("ponta no numerário sendo V3 é recusada: sem perna V2 não há flash, só EmprestimoNaoV2")
	void pontaV3NaoEmpresta() {

		GrafoDePrecos grafo = new GrafoDePrecos();
		Par par = new Par(WBNB, A); // normaliza: token0 = A (0xaa…), token1 = WBNB (0xbb…)

		// A fenda existe: A sai da V3 a 1,0 WBNB (preço 1:1, taxa marginal 0,9975) e volta à V2 a
		// 1,995 (A→WBNB com 200 WBNB : 100 A). O ciclo lucrativo começa em WBNB na perna da V3.
		grafo.ligarV3(POOL_V3, par, Q96, BigInteger.ONE, TAXA);
		grafo.ligarV2(POOL_CARO, par, BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);

		// Mas o empréstimo relâmpago é o `swap` com dados, que só a família V2 oferece: começar na V3
		// é reverter com EmprestimoNaoV2 na certa. Antes de gastar a volta, o grafo recusa — e CONTA.
		assertFalse(grafo.cicloNegativo(NUMERARIOS).isPresent(),
				"a ponta no numerário era V3 e não pode emprestar; devia dar vazio");
		assertEquals(1, grafo.ciclosSemV2NaPonta(), "a recusa tem de ser contada, não engolida");
	}

	@Test
	@DisplayName("com V3 e V2 na ponta, o ciclo começa na V2 — e o V3 segue no miolo")
	void prefereV2NaPonta() {

		GrafoDePrecos grafo = new GrafoDePrecos();

		// Triângulo WBNB → A → USDT → WBNB. As reservas seguem a ordem NORMALIZADA do Par
		// (token0 = menor endereço), então: WBNB/A → (A, WBNB); A/USDT → (USDT, A); USDT/WBNB →
		// (USDT, WBNB).
		// A perna que entra em WBNB é a da V3; a que entra em USDT é a da V2.
		grafo.ligarV3(POOL_V3, new Par(WBNB, A), Q96, BigInteger.ONE, TAXA);
		grafo.ligarV2(POOL_A_USDT, new Par(A, USDT), BigInteger.valueOf(200), BigInteger.valueOf(100), TAXA);
		grafo.ligarV2(POOL_USDT_WBNB, new Par(USDT, WBNB), BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);

		Optional<Ciclo> ciclo = grafo.cicloNegativo(DOIS_NUMERARIOS);

		assertTrue(ciclo.isPresent(), "havia arb e uma ponta V2 no ciclo, devia achar");

		List<Perna> pernas = ciclo.get().pernas();

		assertEquals(3, pernas.size(), "o triângulo tem três pernas");
		assertEquals(hex(USDT), hex(pernas.get(0).entra()), "tem de começar entrando no numerário da ponta V2");
		assertEquals(hex(POOL_USDT_WBNB), hex(pernas.get(0).pool()),
				"e a primeira perna tem de ser a V2: V3 na ponta não empresta");
		assertEquals(1, pernas.stream().filter(p -> hex(p.pool()).equals(hex(POOL_V3))).count(),
				"o V3 continua no ciclo — no miolo ele soma");
		assertEquals(0, grafo.ciclosSemV2NaPonta(), "não houve recusa a contar — havia ponta V2");
	}

	@Test
	@DisplayName("ciclo que só entra por V3 é riscado, e a busca seguinte acha o ciclo V2 que estava ali")
	void repescaCicloQuandoAPontaEraV3() {

		GrafoDePrecos grafo = new GrafoDePrecos();

		// Bloco com DOIS ciclos negativos: um que só se entra por V3 (o flash recusaria com
		// EmprestimoNaoV2) e outro que se entra por V2. A SPFA devolve UM ciclo qualquer — e se for o
		// de V3, o bloco inteiro era jogado fora, sem saber se havia outro. A segunda busca risca só a
		// ponta que não empresta e acha o outro.
		grafo.ligarV3(POOL_V3, new Par(WBNB, A), Q96, BigInteger.ONE, TAXA);
		grafo.ligarV2(POOL_CARO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);

		// O ciclo bom, independente: cross-DEX WBNB/B em duas V2 (compra barato numa, vende caro na
		// outra). A ponta é WBNB entrando numa V2.
		grafo.ligarV2(POOL_BARATO, new Par(WBNB, B), BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);
		grafo.ligarV2(POOL_CARO_B, new Par(WBNB, B), BigInteger.valueOf(200), BigInteger.valueOf(100), TAXA);

		Optional<Ciclo> ciclo = grafo.cicloNegativo(NUMERARIOS);

		assertTrue(ciclo.isPresent(),
				"havia um ciclo V2 no mesmo bloco; desistir na primeira recusa de ponta jogava ele fora");

		List<Perna> pernas = ciclo.get().pernas();

		// A rota devolvida tem de ser a ENTRÁVEL: começa no numerário, numa V2.
		assertEquals(hex(WBNB), hex(pernas.get(0).entra()), "a rota tem de começar entrando no numerário");
		assertTrue(hex(pernas.get(0).pool()).equals(hex(POOL_BARATO)) || hex(pernas.get(0).pool()).equals(hex(POOL_CARO_B)),
				"a ponta devolvida tinha de ser V2, não a V3 que empresta nada");

		assertEquals(1, grafo.ciclosRepescados(),
				"a segunda busca achou o que a primeira recusou — e isso tem de ser MEDIDO, não suposto");
		assertEquals(0, grafo.ciclosSemV2NaPonta(), "não houve recusa definitiva: a alternativa existia");
	}

	private static byte[] hex(String s) {
		return HexFormat.of().parseHex(s);
	}

	private static String hex(byte[] b) {
		return HexFormat.of().formatHex(b);
	}

	@Test
	@DisplayName("ciclo sem V2 na ponta não some: fica em ciclosSemFlash, e com a ponta livre vira ciclo")
	void semV2NaPontaFicaVisivelEPontaLivreEntra() {

		Set<String> tocadas = Set.of(HexFormat.of().formatHex(POOL_V3));

		GrafoDePrecos presa = new GrafoDePrecos();
		presa.ligarV3(POOL_V3, new Par(WBNB, A), Q96, BigInteger.ONE, TAXA);
		presa.ligarV2(POOL_CARO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);

		assertTrue(presa.ciclosPela(tocadas, NUMERARIOS, 4, 5).isEmpty(), "sem V2 na ponta, o Executor 2 não entra");
		assertEquals(1, presa.ciclosSemFlash().size(), "mas o descartado tem de ficar à vista para ser contado");
		assertArrayEquals(WBNB, presa.ciclosSemFlash().get(0).pernas().get(0).entra(), "rodado para entrar no numerário");

		GrafoDePrecos livre = new GrafoDePrecos();
		livre.pontaLivre(true);
		livre.ligarV3(POOL_V3, new Par(WBNB, A), Q96, BigInteger.ONE, TAXA);
		livre.ligarV2(POOL_CARO, new Par(WBNB, A), BigInteger.valueOf(100), BigInteger.valueOf(200), TAXA);

		List<Ciclo> ciclos = livre.ciclosPela(tocadas, NUMERARIOS, 4, 5);
		assertEquals(1, ciclos.size(), "com o Executor 3, a ponta V3 empresta");
		assertArrayEquals(POOL_V3, ciclos.get(0).pernas().get(0).pool(), "entra pela V3, a perna que sai do WBNB");
		assertTrue(livre.ciclosSemFlash().isEmpty());
	}
}
