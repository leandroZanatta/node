package br.com.node.sondagem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// A `Sonda` de produção, validada contra a cicatriz — antes de servir de fonte para qualquer decisão.
//
// Foi assim que a conferência de estado ganhou confiança (o `Multicall3Test` provou o instrumento
// antes de ele julgar o node), e o imposto passa pelo mesmo. A cicatriz é conhecida e conferível: o
// BlackSwan (`0x44ac0579…`) cobra 1300 bps de quem transfere para o par, e o `Swap`-contra-`Transfer`
// do catálogo dizia ZERO para ele — o buraco que deixou o YFSX passar. Se a sonda reproduz 1300 na
// cadeia de hoje, ela mede o que o executor pagaria; se não, não serve.
//
// Dirige a `Sonda` de PRODUÇÃO por um adaptador de `Cadeia`, então o que se valida aqui é o mesmo
// código que roda no serviço.
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

		// O par detém BlackSwan: é a âncora com saldo para achar o slot, e o destino que caracteriza a venda.
		Medida venda = sonda.venda(BLACKSWAN, PAR_BS_WBNB, PAR_BS_WBNB, altura);

		System.out.println("BlackSwan venda -> " + venda.motivo() + " " + venda.bps() + " bps (altura " + altura + ")");

		assertTrue(venda.mediu(), "a sonda não conseguiu medir o BlackSwan na cadeia de hoje: " + venda.motivo());
		assertEquals(1300, venda.bps(), "o imposto de venda do BlackSwan não é os 1300 bps da cicatriz");

		// O WBNB é o token base da BSC e não cobra imposto. Achar imposto onde não há é tão ruim quanto
		// não achar onde há — o zero também tem de bater.
		Medida limpo = sonda.venda(WBNB, PAR_BS_WBNB, PAR_BS_WBNB, altura);

		System.out.println("WBNB venda -> " + limpo.motivo() + " " + limpo.bps() + " bps");

		assertTrue(limpo.mediu(), "a sonda não mediu o WBNB: " + limpo.motivo());
		assertEquals(0, limpo.bps(), "a sonda inventou imposto num token sabidamente limpo");
	}

	private static byte[] hex(String endereco) {
		return HexFormat.of().parseHex(endereco);
	}
}
