package br.com.node;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import br.com.node.auditoria.OrquestradorDeAuditoria;
import br.com.node.baixa.OrquestradorDeBaixa;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.execucao.OrquestradorDeExecucao;
import br.com.node.transformacao.OrquestradorDeTransformacao;
import br.com.node.util.Diario;
import br.com.node.util.Identidade;
import br.com.node.util.NodeKey;

public final class Main {

	static {
		Diario.ligar();
	}

	private static final Logger LOG = Logger.getLogger(Main.class.getName());

	public static void main(String[] argumentos) throws InterruptedException {

		Postgres banco = new Postgres();
		banco.migrar();

		NodeKey chave = Identidade.carregar();
		Cabeca cabeca = new Cabeca();

		RpcDoEnsaio rpcEnsaio = new RpcDoEnsaio();
		RpcDoEnsaio rpcDaAutopsia = Optional.ofNullable(System.getenv("RPC_LOGS")).map(String::trim)
				.filter(u -> !u.isBlank()).map(u -> new RpcDoEnsaio(List.of(u))).orElse(rpcEnsaio);

		OrquestradorDeTransformacao mercado = new OrquestradorDeTransformacao(banco, rpcEnsaio);
		OrquestradorDeBaixa baixa = new OrquestradorDeBaixa(chave, banco, cabeca, mercado.aplicador());
		OrquestradorDeExecucao execucao = new OrquestradorDeExecucao(rpcEnsaio, rpcDaAutopsia, cabeca, mercado);
		OrquestradorDeAuditoria auditoria = new OrquestradorDeAuditoria(baixa, mercado, execucao, rpcEnsaio);

		baixa.aoReceberBloco((sessao, bloco) -> {
			execucao.aoAplicarCabecalho(bloco.cabecalho());
			mercado.blocoAnunciado(bloco);
		});
		baixa.aoFecharBloco(altura -> {
			execucao.aoFecharBloco(altura);
			auditoria.aoFecharBloco(altura);
		});

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			auditoria.fechar();
			execucao.fechar();
			mercado.fechar();
			baixa.fechar();
			banco.close();
		}, "encerramento"));

		execucao.iniciar();
		baixa.iniciar(mercado::observarHashes, mercado::observarTransacoes);
		mercado.iniciar();
		auditoria.iniciar();

		LOG.info(() -> "node " + chave.idHex().substring(0, 16) + "… no ar; relatório em " + auditoria.arquivoDoRelatorio());

		Thread.currentThread().join();
	}
}
