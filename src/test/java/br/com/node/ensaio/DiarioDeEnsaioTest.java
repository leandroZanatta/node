package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class DiarioDeEnsaioTest {

	@TempDir
	Path pasta;

	@Test
	void gravaUmaLinhaConsolidadaMesmoComEventosForaDeOrdem() throws Exception {
		Path arquivo = pasta.resolve("ensaio.jsonl");
		Desfechos.Envio envio = envio();
		try (DiarioDeEnsaio diario = new DiarioDeEnsaio(arquivo)) {
			diario.autopsia(envio.hash(), Map.of("fecharia_agora", true, "retorno_cadeia_bps", 10_050));
			diario.desfecho(new Desfechos.Desfecho(envio, "rival_n1", 80L, 3, "rival",
					BigInteger.valueOf(50_000_000L), BigInteger.valueOf(52_000_000L), "ok"),
					600);
			diario.envio(envio);
		}

		var linhas = Files.readAllLines(arquivo);
		assertEquals(1, linhas.size());
		JsonObject linha = JsonParser.parseString(linhas.get(0)).getAsJsonObject();
		assertEquals("ensaio.v1", linha.get("schema").getAsString());
		assertEquals(envio.hash(), linha.get("id").getAsString());
		assertEquals("rival_n1", linha.getAsJsonObject("desfecho").get("classe").getAsString());
		assertEquals(600,
				linha.getAsJsonObject("desfecho").get("proxima_lance_fracao_milha").getAsInt());
		assertEquals("rival_no_prazo_sem_lance_total_do_rival",
				linha.getAsJsonObject("diagnostico").get("causa").getAsString());
		assertEquals("fracao_dobrada_rival_confirmado_sem_bid_real",
				linha.getAsJsonObject("desfecho").get("regra_aprendizado").getAsString());
		assertFalse(linha.getAsJsonObject("diagnostico").get("lance_do_rival_comparavel_disponivel").getAsBoolean());
		assertEquals("55000000", linha.getAsJsonObject("envio").getAsJsonObject("lance")
				.get("ofertado_total_wei_por_gas").getAsString());
	}

	@Test
	void distingueAtrasoDeUmaDerrotaPorLance() throws Exception {
		Path arquivo = pasta.resolve("atraso.jsonl");
		Desfechos.Envio envio = envio();
		try (DiarioDeEnsaio diario = new DiarioDeEnsaio(arquivo)) {
			diario.envio(envio);
			diario.autopsia(envio.hash(), Map.of("status", "nao_aplicavel_sem_transacao_alvo"));
			diario.desfecho(new Desfechos.Desfecho(envio, "rival_n1", -12L, 1, "rival", null, null, "ok"),
					300);
		}

		JsonObject linha = JsonParser.parseString(Files.readString(arquivo).trim()).getAsJsonObject();
		assertEquals("resposta_do_relay_apos_inicio_do_bloco_seguinte",
				linha.getAsJsonObject("diagnostico").get("causa").getAsString());
	}

	@Test
	void registraRecusaDoRelayEDeixaClaroQueNaoHouveDesfechoDeBloco() throws Exception {
		Path arquivo = pasta.resolve("recusa.jsonl");
		try (DiarioDeEnsaio diario = new DiarioDeEnsaio(arquivo)) {
			diario.recusa("0xrecusada", Map.of("relay_aceitou", false,
					"transporte", Map.of("respostas_por_builder", Map.of("48club", Map.of("motivo", "nonce too low")))));
		}

		JsonObject linha = JsonParser.parseString(Files.readString(arquivo).trim()).getAsJsonObject();
		assertEquals("recusada_pelo_relay", linha.getAsJsonObject("desfecho").get("classe").getAsString());
		assertEquals("relay_recusou_a_submissao", linha.getAsJsonObject("diagnostico").get("causa").getAsString());
	}

	@Test
	void gravaPendenteComoIncompletoAoEncerrar() throws Exception {
		Path arquivo = pasta.resolve("incompleto.jsonl");
		try (DiarioDeEnsaio diario = new DiarioDeEnsaio(arquivo)) {
			diario.envio(envio());
		}

		JsonObject linha = JsonParser.parseString(Files.readString(arquivo).trim()).getAsJsonObject();
		assertEquals("autopsia_pendente_ou_indisponivel",
				linha.getAsJsonObject("diagnostico").get("causa").getAsString());
		assertTrue(linha.get("execucao").getAsString().length() > 0);
	}

	@Test
	void rivalN1ComAutopsiaNaoAplicavelPedeBidTotalSemCulparRpcHistorico() throws Exception {
		Path arquivo = pasta.resolve("n1.jsonl");
		Desfechos.Envio envio = envio();
		try (DiarioDeEnsaio diario = new DiarioDeEnsaio(arquivo)) {
			diario.envio(envio);
			diario.autopsia(envio.hash(), Map.of("status", "nao_aplicavel_sem_transacao_alvo"));
			diario.desfecho(
					new Desfechos.Desfecho(envio, "rival_n1", 20L, 1, "rival", null, null, null),
					300);
		}

		JsonObject linha = JsonParser.parseString(Files.readString(arquivo).trim()).getAsJsonObject();
		assertEquals("rival_no_prazo_sem_lance_total_do_rival",
				linha.getAsJsonObject("diagnostico").get("causa").getAsString());
	}

	private static Desfechos.Envio envio() {
		return new Desfechos.Envio("0xabc", 100, Set.of("pool"), 1_000, "cauda", "rota",
				300, BigInteger.valueOf(55_000_000L), BigInteger.ONE, BigInteger.valueOf(460_000L),
				Map.of("lance", Map.of("ofertado_total_wei_por_gas", "55000000")));
	}
}
