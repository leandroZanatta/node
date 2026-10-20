package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Catalogo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.PoolV3;
import br.com.node.nucleo.Travessia;
import br.com.node.vo.Log;

// A ESTIMATIVA DE GÁS e o PISO, provados sem rede.
//
// O que se prova aqui não é "o número bonito": é que a conta é ESTRUTURAL (cresce por perna e por
// tipo), que o piso sai do MESMO preço que a taxa, e que o gás exato da calldata (EIP-2028) entra
// byte a byte. Foi a falta disto que deixou a implementação pela metade: duas estimativas
// convivendo, constantes que não existiam, e um piso que não se sabia de onde vinha.
class CustoDeGasTest {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] POOL_CANONICA = HexFormat.of().parseHex("172fcd41e0913e95784454622d1c3724f546f849");
	private static final byte[] MOEDA = HexFormat.of().parseHex("cc".repeat(20));

	private static final String P1 = "11".repeat(20);
	private static final String P2 = "44".repeat(20);
	private static final String A = "22".repeat(20);
	private static final String B = "33".repeat(20);

	private static final BigInteger BASE = BigInteger.valueOf(1_000_000_000L); // 1 gwei (o inicial)
	// A gorjeta parte de ZERO: o piso usa o mercado puro, ate que o bloco peca mais.
	private static final BigInteger TIP = BigInteger.ZERO;

	// A entrada do ciclo (1 BNB) e a taxa do empréstimo relâmpago sobre ela: ZERO por padrão — o contrato
	// devolve exatamente o `montante`, e a taxa da perna 0 já está no lucro do modelo.
	private static final BigInteger MONTANTE = BigInteger.TEN.pow(18);
	private static final BigInteger TAXA_POR_ENTRADA = BigInteger.ZERO;

	// Os números estruturais, escritos à mão: base 21k + executor 60k + empréstimo 60k, mais a perna
	// e o teto de calldata dela (160 bytes × 16). Se alguém mexer nas constantes, o teste cai e a
	// calibração é revista em vez de derivar em silêncio.
	private static final BigInteger FIXO = BigInteger.valueOf(21_000L + 60_000L + 60_000L);
	private static final BigInteger POR_PERNA_V2 = BigInteger.valueOf(75_000L + 2_560L);
	private static final BigInteger POR_PERNA_V3 = BigInteger.valueOf(120_000L + 2_560L);

	@Test
	@DisplayName("o gás cresce por perna e por tipo: V2 < V3, e rota vazia é só o custo da tx")
	void aEstruturaSaiPorPernaETipo() {

		CustoDeGas custo = custo();

		assertEquals(BigInteger.valueOf(21_000L), custo.estimarGas(List.of()), "rota vazia devolve o 21k");

		BigInteger umaV2 = custo.estimarGas(List.of(salto(false)));
		BigInteger umaV3 = custo.estimarGas(List.of(salto(true)));
		BigInteger duas = custo.estimarGas(List.of(salto(false), salto(true)));

		assertEquals(FIXO.add(POR_PERNA_V2), umaV2);
		assertEquals(FIXO.add(POR_PERNA_V3), umaV3);
		assertEquals(umaV2.add(umaV3).subtract(FIXO), duas, "a segunda perna soma, não dobra");
		assertTrue(umaV3.compareTo(umaV2) > 0, "V3 é mais caro que V2 — é a curva concentrada");
	}

	@Test
	@DisplayName("a Travessia do modelo e o Salto do contrato dão o MESMO gás")
	void travessiaESaltoConcordam() {

		CustoDeGas custo = custo();

		Travessia v3 = new Travessia(new PoolV3(BigInteger.ONE.shiftLeft(96), BigInteger.TEN.pow(18), 0, 500, 10),
				Direcao.ZERO_PARA_UM);
		Travessia v2 = new Travessia(new PoolV2(BigInteger.TEN.pow(18), BigInteger.TEN.pow(18), 2_500),
				Direcao.ZERO_PARA_UM);

		assertEquals(custo.estimarGas(List.of(salto(true))), custo.estimarGasDasTravessias(List.of(v3)));
		assertEquals(custo.estimarGasDasTravessias(List.of(v2)), custo.estimarGasDasTravessias(List.of(v3, v2))
				.subtract(POR_PERNA_V3));
	}

	@Test
	@DisplayName("a calldata real entra byte a byte (EIP-2028), não pelo teto por perna")
	void calldataRealSubstituiOTeto() {

		CustoDeGas custo = custo();

		List<Salto> rota = List.of(salto(false), new Salto(P2, B, A, true, 30));
		byte[] calldata = AbiDoExecutor.executar(rota, BigInteger.TEN.pow(18), BigInteger.valueOf(5_000));

		BigInteger comBytes = custo.estimarGas(rota, calldata);
		BigInteger teto = custo.estimarGas(rota);

		BigInteger esperado = FIXO.add(BigInteger.valueOf(75_000L)).add(BigInteger.valueOf(120_000L))
				.add(CustoDeGas.gasDaCalldata(calldata));

		assertEquals(esperado, comBytes, "a parte estrutural é a mesma; só a calldata deixa de ser teto");
		assertTrue(!comBytes.equals(teto), "com os bytes reais a conta tem de divergir do teto");
	}

	@Test
	@DisplayName("sem_loan exclui os 60k do empréstimo relâmpago da estimativa")
	void semLoanExcluiGasDoEmprestimo() {

		CustoDeGas custo = custo();
		List<Salto> rota = List.of(salto(false), new Salto(P2, B, A, true, 30));
		byte[] calldata = AbiDoExecutor.executarV2(rota, BigInteger.TEN.pow(18), BigInteger.valueOf(5_000));

		BigInteger comLoan = custo.estimarGas(rota, calldata, false);
		BigInteger semLoan = custo.estimarGas(rota, calldata, true);

		assertEquals(BigInteger.valueOf(60_000L), comLoan.subtract(semLoan),
				"a diferença deve ser exatamente GAS_EMPRESTIMO");
	}

	@Test
	@DisplayName("calldata: zero custa 4 e não-zero custa 16")
	void gasDaCalldataEhEip2028() {

		assertEquals(BigInteger.valueOf(3 * 4 + 16), CustoDeGas.gasDaCalldata(new byte[] { 0, 0, 1, 0 }));
		assertEquals(BigInteger.ZERO, CustoDeGas.gasDaCalldata(new byte[0]));
		assertEquals(BigInteger.valueOf(16), CustoDeGas.gasDaCalldata(new byte[] { 1 }));
	}

	@Test
	@DisplayName("piso-base padrão cobre o gás da arbitragem e preserva 1 wei líquido")
	void pisoEmWbnbCobreCustosEDeixaSobraPositiva() {

		CustoDeGas custo = custo(); // sem `atualizar()`: o preço medido é informado (1 gwei), não herdado

		BigInteger gas = BigInteger.valueOf(300_000L);
		assertEquals(100, custo.margemDoPisoCentimos(), "o padrão é 1,00×");
		BigInteger preco = BASE.add(TIP);
		BigInteger gasComMargem = preco.multiply(gas)
				.multiply(BigInteger.valueOf(custo.margemDoPisoCentimos())).divide(BigInteger.valueOf(100));
		BigInteger esperado = gasComMargem.add(TAXA_POR_ENTRADA).add(BigInteger.ONE);

		assertEquals(esperado, custo.piso(WBNB, MONTANTE, gas).orElseThrow());
		assertEquals(BASE.multiply(gas), custo.custoDoGas(WBNB, gas));
		assertEquals(BASE.multiply(BigInteger.valueOf(21_000L)), custo.custoDaTransferenciaBuilder(WBNB));
		assertEquals(TAXA_POR_ENTRADA, custo.taxaDoEmprestimo(MONTANTE));
		assertEquals(BASE.multiply(BigInteger.TWO), custo.maxTaxa());
		assertEquals(BASE, custo.precoEfetivo());
		assertEquals(BASE, custo.prioridade(), "base zero na BSC: a prioridade é o preço inteiro, não 0");

		// Sem montante conhecido a taxa é zero — mas o gás continua exigido: o piso não desaparece.
		assertEquals(gasComMargem.add(BigInteger.ONE), custo.piso(WBNB, null, gas).orElseThrow());
		assertEquals(BigInteger.ZERO, custo.taxaDoEmprestimo(BigInteger.ZERO));
		assertEquals(BigInteger.ZERO, custo.taxaDoEmprestimo(null));
	}

	@Test
	@DisplayName("a gorjeta parte de zero e sobe sob demanda: piso e maxFee acompanham")
	void gorjetaSobeSobDemanda() {

		CustoDeGas custo = custo();
		BigInteger gas = BigInteger.valueOf(300_000L);

		custo.gorjeta(BigInteger.valueOf(100_000_000L));

		assertEquals(BASE.add(BigInteger.valueOf(100_000_000L)), custo.prioridade());
		assertEquals(BASE.add(BigInteger.valueOf(100_000_000L)), custo.precoEfetivo());
		assertEquals(BASE.add(BigInteger.valueOf(100_000_000L)).multiply(BigInteger.TWO), custo.maxTaxa());
		BigInteger preco = BASE.add(BigInteger.valueOf(100_000_000L));
		BigInteger gasComMargem = preco.multiply(gas)
				.multiply(BigInteger.valueOf(custo.margemDoPisoCentimos())).divide(BigInteger.valueOf(100));
		assertEquals(gasComMargem.add(TAXA_POR_ENTRADA).add(BigInteger.ONE),
				custo.piso(WBNB, MONTANTE, gas).orElseThrow());

		custo.gorjeta(null);
		custo.gorjeta(BigInteger.valueOf(-1));
		assertEquals(BASE, custo.prioridade(), "nulo e negativo voltam a zero, nunca abaixo");
	}

	@Test
	@DisplayName("USDT converte pela canônica; sem medição NENHUMA o valor frio é alto — falha fechado")
	void pisoEmUsdtConvertePelaPoolCanonica() {

		CustoDeGas comPool = new CustoDeGas(null, catalogoComPoolCanonica()).comMercadoMedido(new BigInteger("1000000000"));
		CustoDeGas semPool = custo();

		BigInteger gas = BigInteger.valueOf(300_000L);
		BigInteger pisoBnb = BASE.add(TIP).multiply(gas)
				.multiply(BigInteger.valueOf(comPool.margemDoPisoCentimos())).divide(BigInteger.valueOf(100));

		// 100 WBNB e 60.000 USDT na canônica: a razão é 600, e é ela que converte.
		assertEquals(pisoBnb.multiply(BigInteger.valueOf(600)).add(TAXA_POR_ENTRADA).add(BigInteger.ONE),
				comPool.piso(USDT, MONTANTE, gas).orElseThrow());

		// Sem a canônica no catálogo e sem NUNCA ter medido, ESTOURA. Até 27/09 saía um valor frio (2.000),
		// inventado — o mesmo padrão do 600 fixo que fez o piso em USDT aceitar fenda que não pagava o gás.
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> semPool.piso(USDT, MONTANTE, gas));
	}

	@Test
	@DisplayName("canônica V3: o preço sai do sqrtPrecoX96 (~777 USDT/BNB), e não do 600 fixo")
	void canonicav3DaOPrecoDoSqrtPreco() {

		// O `sqrtPrecoX96` REAL da canônica WBNB/USDT (V3, tier 100), lido em produção em 25/09
		// 23:41:09 — a autópsia do caso "LL: K". Era por ela ser V3 que o `emUsdt` (só reservas V2)
		// nunca respondia, e toda fenda USDT saía pelo fator fixo.
		BigInteger sqrtPreco = new BigInteger("2841859289178379177954425248");

		BigInteger cambio = CustoDeGas.cambioDoV3(sqrtPreco, true).orElseThrow();

		assertTrue(cambio.compareTo(BigInteger.valueOf(770)) > 0 && cambio.compareTo(BigInteger.valueOf(785)) < 0,
				"o câmbio medido tem de ser ~777 USDT/BNB, veio " + cambio);
		assertTrue(cambio.compareTo(BigInteger.valueOf(600)) > 0,
				"e MAIOR que o valor fixo antigo: o piso em USDT estava barato, não caro");

		// A conta é `1/preço`, com `preço = sqrtPrecoX96²/2^192`: dobrar o sqrt divide o câmbio por ~4.
		BigInteger dobrado = CustoDeGas.cambioDoV3(sqrtPreco.multiply(BigInteger.TWO), true).orElseThrow();

		assertTrue(dobrado.compareTo(cambio.divide(BigInteger.valueOf(3))) < 0,
				"dobrar o sqrtPreco tem de dividir o câmbio por ~4, veio " + dobrado);
		assertTrue(CustoDeGas.cambioDoV3(BigInteger.ZERO, true).isEmpty(), "sqrtPreco zero não vira preço");
	}

	@Test
	@DisplayName("lucro na raiz ↔ BNB, para pagar o builder: WBNB é identidade, USDT pela canônica, o resto vazio")
	void conversaoParaPagarOBuilder() {

		CustoDeGas comPool = new CustoDeGas(null, catalogoComPoolCanonica()).comMercadoMedido(new BigInteger("1000000000"));
		BigInteger umBnb = BigInteger.TEN.pow(18);

		assertEquals(umBnb, comPool.paraBnb(WBNB, umBnb).orElseThrow());
		assertEquals(umBnb, comPool.deBnb(WBNB, umBnb).orElseThrow());
		assertEquals(umBnb.multiply(BigInteger.valueOf(600)), comPool.deBnb(USDT, umBnb).orElseThrow());
		assertEquals(umBnb, comPool.paraBnb(USDT, umBnb.multiply(BigInteger.valueOf(600))).orElseThrow());
		assertTrue(comPool.paraBnb(MOEDA, umBnb).isEmpty());
		assertTrue(comPool.deBnb(MOEDA, umBnb).isEmpty());
	}

	@Test
	@DisplayName("conversão observacional fica indisponível até haver preço, sem derrubar a observação")
	void conversaoObservacionalSemPrecoRetornaVazio() {

		CustoDeGas semPool = custo();
		BigInteger umBnb = BigInteger.TEN.pow(18);

		assertTrue(semPool.paraBnbSeDisponivel(USDT, umBnb).isEmpty());
		assertEquals(umBnb, semPool.paraBnbSeDisponivel(WBNB, umBnb).orElseThrow());
		assertTrue(semPool.paraBnbSeDisponivel(MOEDA, umBnb).isEmpty());
	}

	@Test
	@DisplayName("raiz que não é numerário não tem piso, e gás inválido também não — falha fechada")
	void semPisoNaoSeEntra() {

		CustoDeGas custo = custo();

		assertTrue(custo.piso(MOEDA, MONTANTE, BigInteger.TEN.pow(18)).isEmpty(), "raiz não-numerário");
		assertTrue(custo.piso(WBNB, MONTANTE, BigInteger.ZERO).isEmpty(), "gás zero não vira piso");
		assertTrue(custo.piso(WBNB, MONTANTE, null).isEmpty(), "gás ausente não vira piso");
		assertTrue(custo.piso(null, MONTANTE, BigInteger.ONE).isEmpty(), "raiz ausente não vira piso");
	}

	// Sem `atualizar()`: o preço medido (1 gwei) é informado — o teste prova a CONTA, não a leitura da rede.
	private static CustoDeGas custo() {
		return new CustoDeGas(null, new Catalogo()).comMercadoMedido(new BigInteger("1000000000"));
	}

	private static Salto salto(boolean v3) {
		return new Salto(P1, A, B, v3, 25);
	}

	// A canônica WBNB/USDT com 100 WBNB e 60.000 USDT. `Par` ordena por ENDEREÇO (token0 = menor),
	// e USDT (0x55d3…) é menor que WBNB (0xbb4c…): token0 = USDT, token1 = WBNB. As duas têm 18
	// casas, então é a razão das reservas que dá o preço.
	private static Catalogo catalogoComPoolCanonica() {

		Catalogo catalogo = new Catalogo();

		catalogo.aprenderPar(POOL_CANONICA, List.of(USDT, WBNB));
		catalogo.aplicarSync(new Log(POOL_CANONICA, List.of(),
				reservas(BigInteger.valueOf(60_000), BigInteger.valueOf(100))), 1L);

		return catalogo;
	}

	private static byte[] reservas(BigInteger r0, BigInteger r1) {

		byte[] dados = new byte[64];

		byte[] b0 = r0.multiply(BigInteger.TEN.pow(18)).toByteArray();
		byte[] b1 = r1.multiply(BigInteger.TEN.pow(18)).toByteArray();

		System.arraycopy(b0, 0, dados, 32 - b0.length, b0.length);
		System.arraycopy(b1, 0, dados, 64 - b1.length, b1.length);

		return dados;
	}

}
