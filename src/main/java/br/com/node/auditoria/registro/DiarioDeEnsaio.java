package br.com.node.auditoria.registro;
import br.com.node.execucao.disparo.Desfechos;

import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.execucao.bundle.Salto;
import br.com.node.execucao.bundle.RotaDoModelo;


import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class DiarioDeEnsaio implements Closeable {

	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private static final class Registro {
		private final String hash;
		private final Map<String, Object> envio = new LinkedHashMap<>();
		private boolean envioRegistrado;
		private Map<String, Object> autopsia;
		private Map<String, Object> desfecho;
		private boolean bidEncontrado;

		private Registro(String hash) {
			this.hash = hash;
		}
	}

	private final BufferedWriter saida;
	private final ExecutorService escritor = Executors.newSingleThreadExecutor(t -> new Thread(t, "diario-ensaio"));
	private final String execucao = Instant.now().toString();
	private final Map<String, Registro> pendentes = new ConcurrentHashMap<>();
	private final java.util.Set<String> gravados = ConcurrentHashMap.newKeySet();
	private volatile boolean fechado;

	public DiarioDeEnsaio(Path arquivo) {
		try {
			Files.createDirectories(arquivo.toAbsolutePath().getParent());
			saida = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException("diário de ensaio: não abri " + arquivo, e);
		}
	}

	public void envio(Desfechos.Envio envio) {
		Registro registro = obter(envio.hash());
		if (registro == null) {
			return;
		}
		synchronized (registro) {
			registro.envio.put("altura", envio.altura());
			registro.envio.put("caminho", envio.caminho());
			registro.envio.put("rota_id", envio.rota());
			registro.envio.put("pools", envio.pools());
			registro.envio.put("confirmacao_relay_epoch_ms", envio.chegadaMs());
			registro.envio.put("lance_fracao_milha", envio.fracaoMilha());
			registro.envio.put("lance_ofertado_wei_por_gas", envio.ofertadoWeiPorGas().toString());
			registro.envio.put("lucro_previsto_bnb_wei", envio.lucroBnb().toString());
			registro.envio.putAll(envio.detalhes());
			registro.envioRegistrado = true;
			gravarSeCompleto(registro);
		}
	}

	public void recusa(String hash, Map<String, Object> detalhes) {
		Registro registro = obter(hash);
		if (registro == null) {
			return;
		}
		synchronized (registro) {
			registro.envio.putAll(detalhes);
			registro.envioRegistrado = true;
			if (detalhes.containsKey("motivo_nao_enviado")) {
				registro.autopsia = Map.of("status", "nao_aplicavel_nao_enviada");
				registro.desfecho = Map.of("classe", "nao_enviada");
			} else {

				registro.desfecho = Map.of("classe", "recusada_pelo_relay");
			}
			gravarSeCompleto(registro);
		}
	}

	public void autopsia(String hash, Map<String, Object> resultado) {
		if (hash == null) {
			return;
		}
		Registro registro = obter(hash);
		if (registro == null) {
			return;
		}
		synchronized (registro) {
			registro.autopsia = new LinkedHashMap<>(resultado);
			gravarSeCompleto(registro);
		}
	}

	public void desfecho(Desfechos.Desfecho desfecho, int fracaoDepoisDoAprendizado) {
		desfecho(desfecho, fracaoDepoisDoAprendizado, false);
	}

	public void desfecho(Desfechos.Desfecho desfecho, int fracaoDepoisDoAprendizado, boolean bidEncontrado) {
		Registro registro = obter(desfecho.envio().hash());
		if (registro == null) {
			return;
		}
		synchronized (registro) {
			Map<String, Object> resultado = new LinkedHashMap<>();
			resultado.put("classe", desfecho.classe());
			resultado.put("folga_ms", desfecho.folgaMs());
			resultado.put("posicao", desfecho.posicao());
			resultado.put("quem", desfecho.quem());
			resultado.put("preco_gas_tx_na_frente_wei_por_gas",
					desfecho.naFrente() == null ? null : desfecho.naFrente().toString());
			resultado.put("mediana_gas_tx_top5_n1_wei_por_gas",
					desfecho.topo() == null ? null : desfecho.topo().toString());
			resultado.put("veredito_executor", desfecho.veredito());
			resultado.put("proxima_lance_fracao_milha", fracaoDepoisDoAprendizado);
			resultado.put("regra_aprendizado", regraAprendizado(desfecho, bidEncontrado));
			registro.desfecho = resultado;
			registro.bidEncontrado = bidEncontrado;
			gravarSeCompleto(registro);
		}
	}

	private Registro obter(String hash) {
		if (fechado || gravados.contains(hash)) {
			return null;
		}
		return pendentes.computeIfAbsent(hash, Registro::new);
	}

	private void gravarSeCompleto(Registro registro) {
		if (!fechado && registro.envioRegistrado && registro.autopsia != null && registro.desfecho != null
				&& !gravados.contains(registro.hash)) {
			gravar(registro, false);
		}
	}

	private synchronized void gravar(Registro registro, boolean incompleto) {
		if (!gravados.add(registro.hash)) {
			return;
		}
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("schema", "ensaio.v1");
		linha.put("execucao", execucao);
		linha.put("id", registro.hash);
		linha.put("concluido_em", Instant.now().toString());
		linha.put("envio", registro.envio);
		linha.put("autopsia", registro.autopsia);
		linha.put("desfecho", registro.desfecho);
		linha.put("diagnostico", diagnostico(registro, incompleto));
		String json = GSON.toJson(linha);
		try {
			escritor.execute(() -> gravarLinha(registro.hash, json));
			pendentes.remove(registro.hash);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			gravados.remove(registro.hash);
			throw new IllegalStateException("diário de ensaio: escritor recusou a tentativa " + registro.hash, e);
		}
	}

	private void gravarLinha(String hash, String json) {
		try {
			saida.write(json);
			saida.newLine();
			saida.flush();
		} catch (IOException e) {
			br.com.node.util.Falhas.anotar("diário de ensaio: gravar tentativa " + hash, e);
		}
	}

	private static Map<String, Object> diagnostico(Registro registro, boolean incompleto) {
		Map<String, Object> diagnostico = new LinkedHashMap<>();
		String causa;
		String proximaAcao;
		if (incompleto
				&& "recusada_pelo_relay".equals(registro.desfecho != null ? registro.desfecho.get("classe") : null)) {

			String[] relayDiag = diagnosticoDeRelayRecusado(registro.autopsia);
			causa = relayDiag[0];
			proximaAcao = relayDiag[1];
		} else if (incompleto) {
			causa = registro.autopsia == null ? "autopsia_pendente_ou_indisponivel" : "desfecho_pendente";
			proximaAcao = "verificar_integridade_dos_dados";
		} else if ("incluida".equals(registro.desfecho.get("classe"))) {
			causa = "incluida";
			proximaAcao = "manter_e_medir_custo_real";
		} else if (vereditoInvalido(registro.desfecho.get("veredito_executor"))) {
			causa = "execucao_nao_fechava_no_bloco_da_oportunidade";
			proximaAcao = "corrigir_modelo_ou_filtrar_a_rota";
		} else if ("recusada_pelo_relay".equals(registro.desfecho.get("classe"))) {
			String[] relayDiag = diagnosticoDeRelayRecusado(registro.autopsia);
			causa = relayDiag[0];
			proximaAcao = relayDiag[1];
		} else if ("nao_enviada".equals(registro.desfecho.get("classe"))) {
			causa = String.valueOf(registro.envio.get("motivo_nao_enviado"));
			proximaAcao = "corrigir_o_portao_que_impediu_a_submissao";
		} else if (numero(registro.desfecho.get("folga_ms")) != null
				&& numero(registro.desfecho.get("folga_ms")) <= 0) {
			causa = "resposta_do_relay_apos_inicio_do_bloco_seguinte";
			proximaAcao = "reduzir_latencia_antes_de_aumentar_lance";
		} else if (Boolean.FALSE.equals(registro.autopsia.get("fecharia_agora"))) {
			causa = "execucao_na_cadeia_abaixo_do_piso_de_lucro";
			proximaAcao = "corrigir_modelo_impostos_ou_piso";
		} else if ("rival_n1".equals(registro.desfecho.get("classe"))) {
			causa = "rival_no_prazo_sem_lance_total_do_rival";
			proximaAcao = "obter_detalhes_do_bundle_do_rival_antes_de_ajustar_lance";
		} else if ("pool_mexida_n1".equals(registro.desfecho.get("classe"))) {
			causa = "pool_mexida_sem_arbitragem_rival_confirmada";
			proximaAcao = "identificar_a_transacao_que_alterou_a_pool";
		} else if ("rival_n2".equals(registro.desfecho.get("classe"))) {
			causa = "rival_no_bloco_n_mais_2";
			proximaAcao = "avaliar_janela_de_validade_e_estado_da_oportunidade";
		} else if ("pool_mexida_n2".equals(registro.desfecho.get("classe"))) {
			causa = "pool_mexida_no_bloco_n_mais_2_sem_arbitragem_confirmada";
			proximaAcao = "identificar_a_transacao_que_alterou_a_pool";
		} else if ("ninguem".equals(registro.desfecho.get("classe"))) {
			causa = "sem_inclusao_sem_rival_e_sem_alteracao_observada";
			proximaAcao = "rever_a_validade_da_oportunidade_e_a_cobertura_do_detector";
		} else if (!registro.autopsia.containsKey("fecharia_agora")) {
			causa = "autopsia_sem_estado_historico_ou_detalhe_de_execucao";
			proximaAcao = "habilitar_rpc_historico_e_repetir_a_medicao";
		} else {
			causa = "sem_inclusao_e_sem_causa_de_lance_comprovada";
			proximaAcao = "coletar_mais_evidencia_sem_alterar_o_lance";
		}
		diagnostico.put("causa", causa);
		diagnostico.put("proxima_acao", proximaAcao);
		diagnostico.put("lance_do_rival_comparavel_disponivel", registro.bidEncontrado);
		if (!registro.bidEncontrado) {
			diagnostico.put("observacao",
					"preco_gas_tx_na_frente nao inclui necessariamente o pagamento direto do rival ao builder");
		}
		return diagnostico;
	}

	@SuppressWarnings("unchecked")
	private static String[] diagnosticoDeRelayRecusado(Map<String, Object> autopsia) {
		if (autopsia == null) {
			return new String[] { "relay_recusou_a_submissao", "corrigir_motivo_de_recusa_do_relay" };
		}

		if ("autopsia_gerente".equals(autopsia.get("tipo"))) {
			return new String[] { "relay_recusou_rota_com_gerente", "verificar_calculo_do_gerente_e_estado_da_pool" };
		}

		if (autopsia.containsKey("status")) {
			String status = String.valueOf(autopsia.get("status"));
			if ("sem_estado_do_bloco".equals(status) || "bloco_ainda_nao_chegou_ao_rpc".equals(status)
					|| "estado_historico_indisponivel".equals(status)) {
				return new String[] { "relay_recusou_estado_do_bloco_indisponivel",
						"verificar_disponibilidade_do_rpc_historico" };
			}
			return new String[] { "relay_recusou_a_submissao", "corrigir_motivo_de_recusa_do_relay" };
		}

		Boolean fecharia = (Boolean) autopsia.get("fecharia_agora");
		if (Boolean.FALSE.equals(fecharia)) {
			Map<String, Object> culpada = (Map<String, Object>) autopsia.get("culpada");
			String classe = culpada != null ? String.valueOf(culpada.get("classe")) : null;
			if ("IMPOSTO".equals(classe)) {
				return new String[] { "relay_recusou_imposto_nao_modelado",
						"gravar_imposto_da_pool_culpada_e_remover_do_grafo" };
			}
			if ("TAXA_SUBESTIMADA".equals(classe)) {
				return new String[] { "relay_recusou_taxa_subestimada",
						"corrigir_taxa_da_pool_por_biseccao_no_tarifario" };
			}
			if ("ESTADO".equals(classe)) {
				return new String[] { "relay_recusou_estado_divergente_pool_" + culpada.get("pool"),
						"aceitar_como_estado_e_monitorar_frequencia" };
			}
			if ("CURVA_OU_TAXA".equals(classe)) {
				return new String[] { "relay_recusou_erro_de_curva_ou_taxa",
						"corrigir_calculo_da_pool_culpada_no_modelo" };
			}
			if ("REVERTEU".equals(classe) || "TAXA_SUBESTIMADA".equals(classe)) {
				return new String[] { "relay_recusou_revert_na_execucao",
						"investigar_motivo_do_revert_na_pool_culpada" };
			}
			return new String[] { "relay_recusou_rota_nao_fechava_no_bloco", "corrigir_modelo_ou_filtrar_a_rota" };
		}
		if (Boolean.TRUE.equals(fecharia)) {
			return new String[] { "relay_recusou_apesar_de_rota_valida_possivel_estado_em_transicao",
					"aceitar_como_corrida_e_monitorar_taxa_de_rejeicao" };
		}
		return new String[] { "relay_recusou_a_submissao", "corrigir_motivo_de_recusa_do_relay" };
	}

	private static boolean vereditoInvalido(Object valor) {
		return valor != null && !"ok".equals(valor) && !"sem_veredito".equals(valor);
	}

	private static String regraAprendizado(Desfechos.Desfecho desfecho, boolean bidEncontrado) {
		if ("incluida".equals(desfecho.classe())) {
			return "alvo_reduzido_10pct_apos_inclusao";
		}
		boolean derrotaN1 = "rival_n1".equals(desfecho.classe()) || "pool_mexida_n1".equals(desfecho.classe());
		if (!derrotaN1) {
			return "sem_atualizacao_do_alvo";
		}
		if (desfecho.folgaMs() == null || desfecho.folgaMs() <= 0) {
			return "sem_atualizacao_do_alvo_resposta_apos_bloco";
		}
		if (bidEncontrado) {
			return "alvo_ajustado_pelo_bid_real_do_rival";
		}
		if ("pool_mexida_n1".equals(desfecho.classe())) {

			return "sem_atualizacao_pool_mexida_por_tx_sem_coinbase";
		}
		return "fracao_dobrada_rival_confirmado_sem_bid_real";
	}

	private static Long numero(Object valor) {
		if (valor instanceof Number n) {
			return n.longValue();
		}
		return null;
	}

	@Override
	public synchronized void close() {
		if (fechado) {
			return;
		}
		fechado = true;
		for (Registro registro : pendentes.values()) {
			synchronized (registro) {
				try {
					gravar(registro, true);
				} catch (RuntimeException e) {
					br.com.node.util.Falhas.anotar("diário de ensaio: gravar pendente " + registro.hash, e);
				}
			}
		}
		escritor.shutdown();
		try {
			if (!escritor.awaitTermination(10, TimeUnit.SECONDS)) {
				escritor.shutdownNow();
				br.com.node.util.Falhas.anotar("diário de ensaio: escritor não drenou em 10 s",
						new IllegalStateException("gravações pendentes descartadas"));
			}
		} catch (InterruptedException e) {
			escritor.shutdownNow();
			Thread.currentThread().interrupt();
			br.com.node.util.Falhas.anotar("diário de ensaio: encerramento interrompido", e);
		}
		try {
			saida.close();
		} catch (IOException e) {
			br.com.node.util.Falhas.anotar("diário de ensaio: fechar", e);
		}
	}

}