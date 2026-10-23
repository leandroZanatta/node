package br.com.node.sondagem;
import br.com.node.baixa.rpc.*;
import br.com.node.transformacao.sondagem.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImpostoNaCadeiaTest {

	private static final byte[] BLACKSWAN = hex("44ac0579bab9b047240778052f31418566e4f79b");
	private static final byte[] PAR_BS_WBNB = hex("24bde2edb55a038f50929c871f14ebc49e79bfb4");
	private static final byte[] PAR_CAKE_BS = hex("a5e5219bdfdcebcf54e4dc3c59489774c76678e9");

	private final Cadeia cadeia = new CadeiaDeConferencia();

	@Test
	@DisplayName("compra: o BlackSwan cobra 1300 bps também quando o par nos envia — o caminho do swap")
	void aCompraMedeOImpostoDaOutraDirecao() {

		long altura = cadeia.cabeca() - 5;

		Medida compra = new Sonda(cadeia).compra(PAR_BS_WBNB, BLACKSWAN, altura);

		System.out.println(
				"BlackSwan compra -> " + compra.motivo() + " " + compra.bps() + " bps (altura " + altura + ")");

		assertTrue(compra.mediu(),
				"a compra não mediu — o caminho do swap com estado forjado falhou: " + compra.motivo());

		
		
		
		
		
		
		
		
		assertTrue(compra.bps() >= 1290 && compra.bps() <= 1300,
				"a compra do BlackSwan devia medir ~1300 bps (truncando no swap), veio " + compra.bps());
	}

	@Test
	@DisplayName("por par: o mesmo BlackSwan cobra 1300 num par de destino e 0 noutro")
	void oImpostoEhDoParDeDestino() {

		long altura = cadeia.cabeca() - 5;
		Sonda sonda = new Sonda(cadeia);

		
		
		Medida noParQueTaxa = sonda.venda(BLACKSWAN, PAR_BS_WBNB, PAR_BS_WBNB, altura);
		Medida noOutroPar = sonda.venda(BLACKSWAN, PAR_CAKE_BS, PAR_BS_WBNB, altura);

		System.out.println("BlackSwan venda -> par BS/WBNB " + noParQueTaxa.bps() + " bps · par CAKE/BS "
				+ noOutroPar.bps() + " bps");

		assertTrue(noParQueTaxa.mediu() && noOutroPar.mediu(), "uma das medições por par não saiu");
		assertEquals(1300, noParQueTaxa.bps(), "o par BS/WBNB devia taxar 1300");
		assertEquals(0, noOutroPar.bps(),
				"o par CAKE/BS não taxa: chave por token daria o mesmo 1300 dos dois — o erro do old");
	}

	private static byte[] hex(String endereco) {
		return HexFormat.of().parseHex(endereco);
	}
}