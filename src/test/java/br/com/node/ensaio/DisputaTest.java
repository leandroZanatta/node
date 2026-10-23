package br.com.node.ensaio;
import br.com.node.execucao.disparo.*;
import br.com.node.execucao.bundle.*;
import br.com.node.auditoria.registro.*;
import br.com.node.baixa.rpc.*;


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
		return JsonParser
				.parseString("{\"altura\":" + altura + ",\"posicao\":" + posicao + ",\"de\":\"" + de
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
		
		var r = Disputa.analisar(t, List.of(pagamento(102, 4, "bbbb", "7000")));
		assertEquals("posicao", r.get("casamento_do_pagamento"));
		assertTrue(((String) r.get("veredito")).contains("casado por posição"));
	}

	@Test
	void gasPriceZeroEValorMedidoMasPagamentoTotalSegueDesconhecido() {
		JsonObject t = tentativa("rival_n1", "aaaa", 2, "2000", "100000");
		JsonObject vencedor = JsonParser.parseString("""
				{
				  "hash_vencedor":"0x01",
				  "de":"aaaa",
				  "gas_price_wei":"0",
				  "gas_preco_efetivo_wei":"0",
				  "gas_usado_vencedor":"2603102",
				  "pagamento_do_vencedor_wei":"0",
				  "pagamento_direto_builder_status":"nao_detectado",
				  "pagamento_total_builder_status":"nao_verificado_sem_trace"
				}
				""").getAsJsonObject();

		var r = Disputa.analisar(t, List.of(), vencedor);

		assertEquals("0", r.get("vencedor_gas_preco_usado_wei"));
		assertEquals("0", r.get("vencedor_taxa_wei"));
		assertEquals("nao_detectado", r.get("pagamento_direto_builder_status"));
		assertEquals("desconhecido", r.get("lance_total_rival_status"));
		assertEquals("0", r.get("lance_48club_pontuacao_minima_wei"));
		assertNull(r.get("faltou_bnb_wei"));
		assertTrue(((String) r.get("veredito")).contains("pagamento total ao builder desconhecido"));
	}

	@Test
	void decompoeScore48ClubSemChamarLimiteInferiorDeLanceTotal() {
		JsonObject t = tentativa("rival_n1", "aaaa", 2, "2000", "100000");
		JsonObject vencedor = JsonParser.parseString("""
				{
				  "hash_vencedor":"0x02",
				  "de":"aaaa",
				  "gas_price_wei":"100",
				  "gas_usado_vencedor":"10",
				  "pagamento_do_vencedor_wei":"50",
				  "pagamento_48club_bnb_wei":"50",
				  "pagamento_48club_status":"limite_inferior_sem_trace",
				  "pagamento_direto_builder_status":"detectado"
				}
				""").getAsJsonObject();

		var r = Disputa.analisar(t, List.of(), vencedor);

		assertEquals("1000", r.get("lance_48club_taxa_gas_arb_wei"));
		assertEquals("900", r.get("lance_48club_contribuicao_gas_wei"));
		assertEquals("50", r.get("lance_48club_bnb_direto_wei"));
		assertEquals("950", r.get("lance_48club_pontuacao_minima_wei"));
		assertEquals("limite_inferior_unicidade_e_builder_nao_confirmados", r.get("lance_48club_status"));
		assertEquals("desconhecido", r.get("lance_total_rival_status"));
	}

	@Test
	void resumoContaPagamentoNaoDetectadoSemConfundirComLanceZero() {
		var analise = new java.util.LinkedHashMap<String, Object>();
		analise.put("veredito", "perdemos: pagamento direto ao builder nao detectado");
		analise.put("tipo_do_vencedor", "arbitragem_do_rival");
		analise.put("pagamento_direto_builder_status", "nao_detectado");
		analise.put("lance_total_rival_status", "desconhecido");

		var resumo = Disputa.resumir(List.of(analise));

		assertEquals(1L, resumo.get("perdas_sem_pagamento_visivel"));
		assertEquals(1L, resumo.get("perdas_com_lance_total_desconhecido"));
		assertEquals("0", resumo.get("faltou_total_bnb_wei"));
	}

	@Test
	void poolMexidaNaoContaComoDerrotaParaRival() {
		JsonObject t = tentativa("pool_mexida_n1", "aaaa", 2, "2000", "100000");
		JsonObject vencedor = JsonParser.parseString("""
				{
				  "hash_vencedor":"0x01",
				  "de":"aaaa",
				  "gas_price_wei":"50000000",
				  "gas_usado_vencedor":"100000",
				  "pagamento_do_vencedor_wei":"0"
				}
				""").getAsJsonObject();

		var r = Disputa.analisar(t, List.of(), vencedor);
		var resumo = Disputa.resumir(List.of(r));

		assertEquals("nao_aplicavel", r.get("pagamento_direto_builder_status"));
		assertEquals("nao_aplicavel", r.get("lance_total_rival_status"));
		assertEquals(0L, resumo.get("perdas_sem_pagamento_direto_detectado"));
		assertEquals(0L, resumo.get("perdas_com_lance_total_desconhecido"));
		assertTrue(((String) r.get("veredito")).contains("sem arbitragem rival confirmada"));
	}
}