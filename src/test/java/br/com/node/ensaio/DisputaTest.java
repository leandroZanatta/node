package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class DisputaTest {

	private static JsonObject tentativa(String classe, String quem, int posicao, String pago, String lucro) {
		return JsonParser.parseString("{\"envio\":{\"altura\":100,\"lucro_previsto_bnb_wei\":\"" + lucro
				+ "\",\"lance\":{\"pagamento_direto_builder_bnb_wei\":\"" + pago + "\"}},\"desfecho\":{\"classe\":\""
				+ classe + "\",\"posicao\":" + posicao + ",\"quem\":\"" + quem + "\"}}").getAsJsonObject();
	}

	private static JsonObject pagamento(long altura, int posicao, String de, String valor) {
		return JsonParser.parseString("{\"altura\":" + altura + ",\"posicao\":" + posicao + ",\"de\":\"" + de
				+ "\",\"para\":\"4848489f0b2bedd788c696e2d79b6b69d7484848\",\"valor_wei\":\"" + valor + "\"}")
				.getAsJsonObject();
	}

	@Test
	void perdaPorLanceMostraOQueFaltou() {
		JsonObject t = tentativa("rival_n1", "aaaa", 2, "2000", "100000");
		var r = Disputa.analisar(t, List.of(pagamento(101, 2, "aaaa", "5000")));
		assertEquals("5000", r.get("vencedor_pagou_bnb_wei"));
		assertEquals("3000", r.get("faltou_bnb_wei"));
		assertEquals("50", r.get("fracao_do_vencedor_milha"));
		assertEquals("20", r.get("nossa_fracao_milha"));
		assertEquals("remetente", r.get("casamento_do_pagamento"));
		assertTrue(((String) r.get("veredito")).startsWith("perdemos por lance"));
	}

	@Test
	void semPagamentoVisivelNaoInventaValor() {
		JsonObject t = tentativa("rival_n1", "aaaa", 2, "2000", "100000");
		var r = Disputa.analisar(t, List.of());
		assertNull(r.get("vencedor_pagou_bnb_wei"));
		assertTrue(((String) r.get("veredito")).contains("não visível"));
	}

	@Test
	void pagamentoDeOutroRemetenteCaiPorPosicaoEMarcaisso() {
		JsonObject t = tentativa("rival_n2", "aaaa", 5, "2000", "100000");
		// rival_n2: o vencedor está no bloco 102, não no 101.
		var r = Disputa.analisar(t, List.of(pagamento(102, 4, "bbbb", "7000")));
		assertEquals("posicao", r.get("casamento_do_pagamento"));
		assertTrue(((String) r.get("veredito")).contains("casado por posição"));
	}
}
