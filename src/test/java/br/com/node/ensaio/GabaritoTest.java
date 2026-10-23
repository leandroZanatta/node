package br.com.node.ensaio;
import br.com.node.execucao.disparo.*;
import br.com.node.execucao.bundle.*;
import br.com.node.auditoria.registro.*;
import br.com.node.baixa.rpc.*;


import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.node.transformacao.catalogo.Achado;
import br.com.node.transformacao.nucleo.Arbitragem;
import br.com.node.transformacao.nucleo.Ciclo;
import br.com.node.transformacao.nucleo.Oportunidade;
import br.com.node.transformacao.nucleo.Perna;

class GabaritoTest {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] T = HexFormat.of().parseHex("431a3bee82e2ca41e49895cbece5bb0f76a89b7a");
	private static final byte[] V2 = HexFormat.of().parseHex("550c36d2c777f8621d300f2aa9da4fda5aefffe5");
	private static final byte[] V3 = HexFormat.of().parseHex("5aca9563f8d14a6d79f12421d549a5606644a270");
	private static final byte[] OUTRA = HexFormat.of().parseHex("11".repeat(20));

	@Test
	@SuppressWarnings("unchecked")
	void acertoPeloConjuntoDePoolsESeparadoPorSensor() {

		Gabarito gabarito = new Gabarito();
		Achado nosso = new Achado(1, new Ciclo(List.of(new Perna(V2, WBNB, T), new Perna(V3, T, WBNB))),
				new Oportunidade(BigInteger.TEN, BigInteger.valueOf(12)), List.of());

		gabarito.anotar(nosso, true);

		
		gabarito.confirmar(new Arbitragem(List.of(V3, V2), WBNB, BigInteger.valueOf(70), 1, 1), 100);
		
		gabarito.confirmar(new Arbitragem(List.of(OUTRA, V2), WBNB, BigInteger.valueOf(5), 2, 0), 101);

		Map<String, Object> resumo = gabarito.resumo();
		assertEquals(2L, resumo.get("arbitragens_alheias"));
		assertEquals(Map.of("n+1", 1L), resumo.get("acertos"));
		assertEquals("70", ((Map<String, Map<String, String>>) resumo.get("valor_acertado_por_token")).get("n+1")
				.get(HexFormat.of().formatHex(WBNB)));
		assertEquals("75",
				((Map<String, String>) resumo.get("valor_alheio_por_token")).get(HexFormat.of().formatHex(WBNB)));
	}
}