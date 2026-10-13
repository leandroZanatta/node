package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.nucleo.Par;
import br.com.node.sondagem.CadeiaDeConferencia;
import br.com.node.sondagem.Medida;
import br.com.node.sondagem.Sonda;

// O fantasma que a hora emitiu: a sonda o condena no caso real, na cadeia.
//
// Uma hora seguindo a cabeça emitiu 40 "viáveis" que ninguém tomou. O maior persistiu 1.200 blocos:
// um ciclo de 473 bps pela pool `0ecf06da`, cujo token `0x001208f7…` cobra 500 bps de imposto na
// venda. Aqui a sonda mede esse token, o `Impostos` o marca taxado por (pool, token), e o portão do
// cotador o CONDENA — mesmo com a defesa principal sendo hoje a simulação na cadeia (que executaria a
// transferência e veria o imposto). Prova forte de imposto ainda barra na entrada, sem gastar
// simulação com o que já se sabe sujo.
//
// O que MUDOU: o token não-provado não é mais barrado no portão (a simulação é o filtro por-arb).
// Então o portão não é mais "nega o não-provado" — é "condena o provado sujo". O buraco do YFSX fica
// fechado pela simulação, não pelo portão.
class FantasmaDeImpostoTest {

	private static final byte[] TOKEN_TAXADO = hex("001208f7f53f78db2b32e1c68198d3e8f320aa23");
	private static final byte[] POOL_DO_FANTASMA = hex("0ecf06daae95a9f860551cfd9ca175cc81ad90ed");

	private final CadeiaDeConferencia cadeia = new CadeiaDeConferencia();

	@Test
	@DisplayName("token taxado provado pela sonda vira imposto na conta da perna")
	void oFantasmaEhCondenadoQuandoProvado() {

		long altura = cadeia.cabeca() - 5;
		Sonda sonda = new Sonda(cadeia);
		Impostos impostos = new Impostos();

		Medida venda = sonda.venda(TOKEN_TAXADO, POOL_DO_FANTASMA, POOL_DO_FANTASMA, altura);

		System.out.println("token 001208f7 no par do fantasma -> " + venda.motivo() + " " + venda.bps() + " bps");

		assertTrue(venda.mediu(), "a sonda precisava medir o token do fantasma: " + venda.motivo());
		assertTrue(venda.bps() >= 400, "o token do fantasma devia sair taxado (~500 bps), veio " + venda.bps());

		impostos.medidaVenda(POOL_DO_FANTASMA, TOKEN_TAXADO, venda.bps());

		// Desde 27/09 o imposto provado não tira a perna: entra na CONTA. O que se exige é o número certo.
		assertEquals(java.util.OptionalInt.of(venda.bps()), impostos.venda(POOL_DO_FANTASMA, TOKEN_TAXADO),
				"o imposto provado tinha de voltar como número para a conta da perna");
	}

	private static byte[] hex(String endereco) {
		return HexFormat.of().parseHex(endereco);
	}
}
