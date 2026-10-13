package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Catalogo;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Perna;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.Travessia;

// O PORTÃO DE PISO é a única trava de VALOR do sistema, e já foi desligado uma vez sem barulho: o
// `abaixoPiso` ficava em zero para sempre, e o despacho virou "a maior poeira do bloco" — tudo
// 600× a 3.500× abaixo do gás, revertendo na simulação do relay. Estes testes existem para que
// desligá-lo deixe de ser silencioso: o portão morde dos DOIS lados, barra a fenda que não paga o
// gás e deixa passar a que paga. Um portão que só barra é indistinguível de um node parado.
//
// O `Disparo` entra NULO de propósito. O portão decide ANTES de tocar em quem gasta dinheiro, e é
// isso que se prova aqui: nada de rede, nada de chave, nada de assinatura — se o portão abrir, o
// contador `despachados` denuncia e a thread de envio morre em NPE sozinha, sem enviar.
class PortaoDePisoTest {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");

	// Token que não é numerário nenhum: sem piso, o portão tem de falhar FECHADO.
	private static final byte[] MOEDA_QUALQUER = HexFormat.of().parseHex("cc".repeat(20));
	private static final byte[] POOL = HexFormat.of().parseHex("11".repeat(20));

	// A entrada da fenda que a fixture monta. É DELA que sai a taxa do empréstimo relâmpago (26 bps),
	// e é por isso que o piso não é mais só o gás com margem.
	private static final BigInteger ENTRADA = BigInteger.TEN.pow(18);

	@Test
	@DisplayName("fenda que não paga o gás não é despachada, e o contador do piso denuncia")
	void abaixoDoPisoNaoDespacha() {

		Backrun backrun = backrun();

		// 100 wei de lucro contra um piso de ~0,00036 BNB (1,5× o gás da rota de uma perna V2, com a
		// base inicial de 1 gwei — alta de propósito para falhar fechado): é a poeira que enchia o log.
		backrun.considerar(List.of(fenda(WBNB, BigInteger.valueOf(100))), null);

		assertEquals(1L, backrun.resumo().get("recebidos"));
		assertEquals(0L, backrun.resumo().get("despachados"), "poeira não pode virar bundle no relay");
		assertEquals(1L, backrun.resumo().get("abaixo_piso"), "o piso tem de CONTAR o que barrou");

		backrun.close();
	}

	@Test
	@DisplayName("a mesma fenda com lucro acima do piso passa — o portão não barra tudo")
	void acimaDoPisoDespacha() {

		Backrun backrun = backrun();

		// 1 BNB de lucro contra o piso de ~0,00036 BNB: ~2.800× de folga, então nem mexer nas
		// constantes do `CustoDeGas` derruba este teste.
		backrun.considerar(List.of(fenda(WBNB, BigInteger.TEN.pow(18))), null);

		assertEquals(1L, backrun.resumo().get("despachados"));
		assertEquals(0L, backrun.resumo().get("abaixo_piso"));

		backrun.close();
	}

	@Test
	@DisplayName("raiz que não é numerário não tem piso: falha fechada, como no disparo")
	void semPisoNaoDespacha() {

		Backrun backrun = backrun();

		backrun.considerar(List.of(fenda(MOEDA_QUALQUER, BigInteger.TEN.pow(18))), null);

		assertEquals(0L, backrun.resumo().get("despachados"), "sem piso não se entra");
		assertEquals(1L, backrun.resumo().get("abaixo_piso"));

		backrun.close();
	}

	// A recusa tem de ser MEDIDA, não apenas contada. "16 abaixo do piso" não distingue duas coisas
	// OPOSTAS: 16 poeiras que não pagam nem o próprio gás (o piso está certo e o problema é a fenda)
	// e 16 fendas que pagam o gás e morrem só na margem de 1,5× (o piso é que aperta). Este teste
	// prova que o placar separa as duas — e é ele que responde, com número, "piso conservador demais
	// ou conta errada?".
	@Test
	@DisplayName("a recusa publica a FRAÇÃO do piso e separa a que pagaria o gás da poeira")
	void aRecusaDizQuantoFaltou() {

		Backrun backrun = backrun();
		CustoDeGas custo = new CustoDeGas(null, new Catalogo()).comMercadoMedido(new BigInteger("1000000000"));

		// O gás da MESMA rota que a fixture monta (uma perna V2), pelas mesmas portas públicas que o
		// portão usa: assim o teste não recria a conta, ele a confronta.
		BigInteger gas = custo.estimarGasDasTravessias(
				List.of(new Travessia(new PoolV2(BigInteger.TEN.pow(18), BigInteger.TEN.pow(18), 2_500), Direcao.ZERO_PARA_UM)));

		BigInteger custoDoGas = custo.custoDoGas(WBNB, gas);
		BigInteger piso = custo.piso(WBNB, ENTRADA, gas).orElseThrow();

		assertTrue(piso.compareTo(custoDoGas) > 0, "o piso é o custo COM margem — se for igual, a margem sumiu");

		// (1) A poeira: 2% do piso. Não paga nem o gás, e mexer na margem não a salva.
		BigInteger poeira = piso.divide(BigInteger.valueOf(50));
		backrun.considerar(List.of(fenda(WBNB, poeira)), null);

		long bpsDaPoeira = ((Number) backrun.resumo().get("melhor_fracao_do_piso_bps")).longValue();

		assertEquals(1L, backrun.resumo().get("abaixo_piso"));
		assertEquals(0L, backrun.resumo().get("pagaria_o_gas"), "poeira não paga o gás: a margem não decide nada aqui");
		assertTrue(bpsDaPoeira >= 190 && bpsDaPoeira <= 200,
				"a poeira de 2% do piso tem de ser publicada como ~200 bps, veio " + bpsDaPoeira);
		assertEquals(poeira.toString(), backrun.resumo().get("melhor_lucro"));

		// (2) A fenda que PAGA o gás e morre na margem: lucro entre 1× o custo e o piso (1,5×).
		BigInteger quase = custoDoGas.add(piso).divide(BigInteger.TWO);
		backrun.considerar(List.of(fenda(WBNB, quase)), null);

		long bpsDaQuase = ((Number) backrun.resumo().get("melhor_fracao_do_piso_bps")).longValue();

		assertEquals(2L, backrun.resumo().get("abaixo_piso"));
		assertEquals(1L, backrun.resumo().get("pagaria_o_gas"),
				"esta paga o gás: é a ÚNICA classe em que a margem decide, e ela tem de aparecer separada");
		// Com a taxa do empréstimo dentro do piso, a faixa "paga o gás e morre na margem" ficou mais
		// LARGA: 1,5× o gás já não é 10.000 bps do piso (neste fixture é ~750), e o meio da faixa anda
		// para baixo junto. A fronteira continua 10.000 bps — passou a haver mais chão antes dela.
		long esperadoBps = quase.multiply(BigInteger.valueOf(10_000)).divide(piso).longValue();

		assertTrue(bpsDaQuase > 4_000 && bpsDaQuase < 10_000,
				"entre o custo (agora ~750 bps do piso) e a fronteira aberta, veio " + bpsDaQuase);
		assertEquals(esperadoBps, bpsDaQuase);
		assertEquals(quase.toString(), backrun.resumo().get("melhor_lucro"), "o placar guarda a MELHOR recusa");
		assertEquals(piso.toString(), backrun.resumo().get("melhor_piso"));

		backrun.close();
	}

	// 26/09: replay bateu ao wei com o modelo sem taxa extra — o empréstimo Pancake V2 não cobra à parte
	// (a taxa da perna 0 já é a taxa do flash loan). TAXA_FLASH_BPS=0 por padrão; o botão existe para
	// fontes externas. Com taxa zero, piso(WBNB, montante) == piso(WBNB, null) — só gás com margem.
	@Test
	@DisplayName("flash loan Pancake V2 é gratuito — piso idêntico com e sem montante quando TAXA_FLASH_BPS=0")
	void aTaxaDoEmprestimoEntraNoPiso() {

		Backrun backrun = backrun();
		CustoDeGas custo = new CustoDeGas(null, new Catalogo()).comMercadoMedido(new BigInteger("1000000000"));

		BigInteger gas = custo.estimarGasDasTravessias(
				List.of(new Travessia(new PoolV2(BigInteger.TEN.pow(18), BigInteger.TEN.pow(18), 2_500),
						Direcao.ZERO_PARA_UM)));

		BigInteger pisoSemMontante = custo.piso(WBNB, null, gas).orElseThrow();
		BigInteger pisoComMontante = custo.piso(WBNB, ENTRADA, gas).orElseThrow();

		assertEquals(0, pisoComMontante.compareTo(pisoSemMontante),
				"TAXA_FLASH_BPS=0: piso com e sem montante devem ser iguais — taxa absorvida pela perna 0");

		// Fenda com lucro um wei abaixo do piso é barrada de qualquer jeito.
		BigInteger lucro = pisoComMontante.subtract(BigInteger.ONE);

		backrun.considerar(List.of(fenda(WBNB, lucro)), null);

		assertEquals(0L, backrun.resumo().get("despachados"), "lucro abaixo do piso não despacha");
		assertEquals(1L, backrun.resumo().get("abaixo_piso"));

		backrun.close();
	}

	// Sem `atualizar()`: a base fica no valor inicial, e o piso sai do mesmo preço que a taxa.
	private static Backrun backrun() {
		return new Backrun(new CustoDeGas(null, new Catalogo()).comMercadoMedido(new BigInteger("1000000000")), null, new Gabarito());
	}

	// A perna começa na raiz porque é `pernas().get(0).entra()` que o portão pergunta ao `CustoDeGas`
	// — o numerário é onde o ciclo abre, foi rotacionado para isso, e é nele que o gás se compara.
	private static Achado fenda(byte[] raiz, BigInteger lucro) {

		return new Achado(123L, new Ciclo(List.of(new Perna(POOL, raiz, USDT))),
				new Oportunidade(ENTRADA, ENTRADA.add(lucro)),
				List.of(new Travessia(new PoolV2(ENTRADA, ENTRADA, 2_500), Direcao.ZERO_PARA_UM)));
	}
}
