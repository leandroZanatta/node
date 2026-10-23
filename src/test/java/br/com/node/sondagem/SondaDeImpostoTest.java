package br.com.node.sondagem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SondaDeImpostoTest {

	private static final byte[] BLACKSWAN = hex("44ac0579bab9b047240778052f31418566e4f79b");
	private static final byte[] PAR_BS_WBNB = hex("24bde2edb55a038f50929c871f14ebc49e79bfb4");
	private static final byte[] WBNB = hex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	private final Cadeia cadeia = new CadeiaDeConferencia();

	@Test
	@DisplayName("reproduz a cicatriz: BlackSwan cobra 1300 bps na venda, e o WBNB não cobra")
	void reproduzACicatriz() {

		long altura = cadeia.cabeca() - 5;
		Sonda sonda = new Sonda(cadeia);

		
		
		Medida venda = sonda.venda(BLACKSWAN, PAR_BS_WBNB, PAR_BS_WBNB, altura);

		System.out.println("BlackSwan venda -> " + venda.motivo() + " " + venda.bps() + " bps (altura " + altura + ")");

		assertTrue(venda.mediu(), "a sonda não conseguiu medir o BlackSwan na cadeia de hoje: " + venda.motivo());
		assertEquals(1300, venda.bps(), "o imposto de venda do BlackSwan não é os 1300 bps da cicatriz");

		
		
		
		Medida limpo = sonda.venda(WBNB, PAR_BS_WBNB, PAR_BS_WBNB, altura);

		System.out.println("WBNB venda -> " + limpo.motivo() + " " + limpo.bps() + " bps");

		assertTrue(limpo.mediu(), "a sonda não mediu o WBNB: " + limpo.motivo());
		assertEquals(0, limpo.bps(), "a sonda inventou imposto num token sabidamente limpo");
	}

	private static byte[] hex(String endereco) {
		return HexFormat.of().parseHex(endereco);
	}
}