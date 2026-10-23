package br.com.node.catalogo;
import br.com.node.transformacao.catalogo.*;
import br.com.node.execucao.bundle.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.sondagem.CadeiaDeConferencia;
import br.com.node.transformacao.sondagem.Medida;
import br.com.node.transformacao.sondagem.Sonda;

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

		
		
		assertEquals(java.util.OptionalInt.of(venda.bps()), impostos.venda(POOL_DO_FANTASMA, TOKEN_TAXADO),
				"o imposto provado tinha de voltar como número para a conta da perna");
	}

	private static byte[] hex(String endereco) {
		return HexFormat.of().parseHex(endereco);
	}
}