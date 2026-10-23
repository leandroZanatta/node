package br.com.node.auditoria;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import br.com.node.auditoria.api.HttpApi;
import br.com.node.auditoria.registro.Evento;
import br.com.node.baixa.OrquestradorDeBaixa;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.execucao.OrquestradorDeExecucao;
import br.com.node.transformacao.OrquestradorDeTransformacao;

public final class OrquestradorDeAuditoria implements AutoCloseable {

	private static final int SEGUNDOS_ENTRE_SNAPSHOTS = 60;

	private final OrquestradorDeBaixa baixa;
	private final OrquestradorDeTransformacao mercado;
	private final OrquestradorDeExecucao execucao;
	private final Auditoria auditoria;
	private final Consulta consulta;
	private final HttpApi api;
	private final ScheduledExecutorService agenda;

	public OrquestradorDeAuditoria(Postgres banco, OrquestradorDeBaixa baixa, OrquestradorDeTransformacao mercado,
			OrquestradorDeExecucao execucao, int portaApi) {

		this.baixa = baixa;
		this.mercado = mercado;
		this.execucao = execucao;

		Map<String, Object> configuracao = new HashMap<>();
		configuracao.put("porta_api", portaApi);
		configuracao.put("garimpo_por_tx", System.getenv("GARIMPO_POR_TX"));
		configuracao.put("cauda_uma_camada", System.getenv("CAUDA_UMA_CAMADA"));
		this.auditoria = new Auditoria(banco, execucao.versaoDoExecutor(), configuracao);
		this.consulta = new Consulta(banco);

		execucao.aoEnviar(envio -> auditoria.registrar(new Evento.EnvioRegistrado(envio)));
		execucao.aoRecusarTentativa((hash, detalhes) -> auditoria.registrar(new Evento.RecusaRegistrada(hash, detalhes)));
		execucao.aoConcluirAutopsia((hash, resultado) -> auditoria.registrar(new Evento.AutopsiaRegistrada(hash, resultado)));
		execucao.aoDecidir(desfecho -> auditoria.registrar(new Evento.DesfechoRegistrado(desfecho)));
		mercado.aoConfirmarArbitragem((arbitragem, altura, indice, gatilho) ->
				auditoria.registrar(new Evento.ArbitragemObservada(arbitragem, altura, indice, gatilho)));

		this.api = new HttpApi(portaApi, consulta, auditoria, this::estadoDoNode);
		this.agenda = Executors.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "auditoria-agenda"));
	}

	public void iniciar() {
		agenda.scheduleWithFixedDelay(this::registrarSnapshot, 0, SEGUNDOS_ENTRE_SNAPSHOTS, TimeUnit.SECONDS);
		api.iniciar();
	}

	@Override
	public void close() {
		agenda.shutdownNow();
		api.fechar();
		auditoria.close();
	}

	private void registrarSnapshot() {
		auditoria.registrar(new Evento.SnapshotRegistrado(estadoDoNode()));
	}

	private Map<String, Object> estadoDoNode() {
		Map<String, Object> estado = new HashMap<>();
		estado.put("rede", baixa.resumo());
		estado.put("mercado", mercado.resumoDoMercado());
		estado.put("execucao", execucao.resumo());
		return estado;
	}
}
