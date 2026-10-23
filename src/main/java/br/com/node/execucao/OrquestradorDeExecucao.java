package br.com.node.execucao;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.execucao.disparo.Autopsia;
import br.com.node.execucao.disparo.Backrun;
import br.com.node.execucao.disparo.CustoDeGas;
import br.com.node.execucao.disparo.Desfechos;
import br.com.node.execucao.disparo.Disparo;
import br.com.node.execucao.disparo.Gabarito;
import br.com.node.execucao.disparo.Gatilhos;
import br.com.node.execucao.disparo.Lances;
import br.com.node.execucao.disparo.RelayPrivado;
import br.com.node.execucao.garimpo.Cauda;
import br.com.node.transformacao.OrquestradorDeTransformacao;
import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Cotador;
import br.com.node.transformacao.catalogo.Quarentena;
import br.com.node.transformacao.catalogo.Quentes;
import br.com.node.transformacao.catalogo.Tarifario;
import br.com.node.transformacao.mempool.ObservatorioMempool;
import br.com.node.util.Falhas;
import br.com.node.vo.Cabecalho;
import br.com.node.vo.Log;

public final class OrquestradorDeExecucao {

	private static final boolean GARIMPO_POR_TX = "1".equals(System.getenv("GARIMPO_POR_TX"));

	private final List<Consumer<Desfechos.Envio>> ouvintesDeEnvio = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<String, Map<String, Object>>> ouvintesDeRecusa = new CopyOnWriteArrayList<>();
	private final List<Consumer<Desfechos.Desfecho>> ouvintesDeDecisao = new CopyOnWriteArrayList<>();

	private final Cabeca cabeca;
	private final RelayPrivado relay;
	private final CustoDeGas custoDeGas;
	private final Autopsia autopsia;
	private final Disparo disparo;
	private final Desfechos desfechos;
	private final Gabarito gabarito;
	private final Backrun backrun;
	private final Gatilhos gatilhos;
	private final Cauda cauda;
	private final Lances lances;
	private final ScheduledExecutorService agenda;
	private final ScheduledExecutorService aquecimento;

	public OrquestradorDeExecucao(RpcDoEnsaio rpc, RpcDoEnsaio rpcDaAutopsia, Cabeca cabeca,
			OrquestradorDeTransformacao mercado) {

		this.cabeca = cabeca;

		Catalogo catalogo = mercado.catalogo();
		Cotador cotador = mercado.cotador();
		Tarifario tarifario = mercado.tarifario();
		Quarentena quarentena = mercado.quarentena();
		Quentes quentes = mercado.quentes();
		ObservatorioMempool mempool = mercado.mempool();

		this.relay = new RelayPrivado();
		this.custoDeGas = new CustoDeGas(rpc, catalogo);
		this.autopsia = new Autopsia(rpcDaAutopsia, quarentena, mercado.impostos(), null);
		this.disparo = new Disparo(rpc, relay, catalogo, tarifario, custoDeGas, mercado.impostos(), autopsia, quarentena);
		this.desfechos = new Desfechos(Disparo.executor(), cabeca::momentos, null);
		this.lances = new Lances(Path.of("lances.json"), Lances.inicialDoAmbiente());
		this.gabarito = new Gabarito(cotador::motivoDeFora);
		this.backrun = new Backrun(custoDeGas, disparo, gabarito);
		this.gatilhos = new Gatilhos(rpc, mempool::etapas, mempool::primeiraVisaoMs,
				arbitragem -> custoDeGas.paraBnbSeDisponivel(arbitragem.token(), arbitragem.sobra()));
		this.cauda = new Cauda(catalogo, cotador, tarifario, achado -> backrun.considerar(List.of(achado), null));

		disparo.lancesDe(lances);
		disparo.aoEnviar(envio -> {
			desfechos.registrar(envio);
			ouvintesDeEnvio.forEach(ouvinte -> ouvinte.accept(envio));
		});
		disparo.aoRecusarTentativa((hash, detalhes) -> ouvintesDeRecusa.forEach(ouvinte -> ouvinte.accept(hash, detalhes)));
		desfechos.aoDecidir(desfecho -> {
			synchronized (lances) {
				lances.aprender(desfecho);
			}
			ouvintesDeDecisao.forEach(ouvinte -> ouvinte.accept(desfecho));
		});
		disparo.aoVeredito(desfechos::veredito);
		disparo.vereditoPor(rpcDaAutopsia);
		disparo.medirCom(cabeca::momentos);
		backrun.medirCom(cabeca::momentos);

		gatilhos.gravarFendasGrandesEm(arquivoDeFendasGrandes());

		cauda.quarentena(quarentena);
		cauda.pontaLivre(disparo.versaoDoExecutor() >= 3);
		cauda.aoSemFlashAchar(achado -> gabarito.anotar(achado, false));
		if (!"1".equals(System.getenv("CAUDA_UMA_CAMADA"))) {
			cauda.quentesDe(quentes);
		}
		cauda.valorarCom(
				achado -> custoDeGas.paraBnb(achado.ciclo().pernas().get(0).entra(), achado.oportunidade().lucro())
						.orElseThrow(() -> new IllegalStateException("fenda da cauda sem câmbio BNB para a raiz "
								+ HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()))));

		mercado.aoProjetar((overlay, crua) -> cauda.projetar(overlay, cabeca.alvo())
				.ifPresent(achado -> backrun.considerar(List.of(achado), crua)));
		mercado.aoConfirmarArbitragem((arbitragem, altura, indice, gatilho) -> {
			gabarito.confirmar(arbitragem, altura);
			gatilhos.investigar(arbitragem, altura, indice, gatilho);
			desfechos.arbitragem(arbitragem, altura, indice);
		});
		mercado.aoAplicarTransacao((logs, altura) -> {
			naBorda("desfechos: transação", () -> desfechos.transacao(logs, altura));
			cauda.tocou(logs);
			if (GARIMPO_POR_TX) {
				cauda.aoBloco(altura);
			}
		});
		mercado.aoBlocoComPrecos(desfechos::precos);

		this.agenda = Executors.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "execucao-agenda"));
		this.aquecimento = Executors.newSingleThreadScheduledExecutor(tarefa -> {
			Thread thread = new Thread(tarefa, "relay-aquecimento");
			thread.setDaemon(true);
			return thread;
		});
	}

	public static Path arquivoDeFendasGrandes() {
		return Path.of(System.getenv().getOrDefault("FENDAS_GRANDES_LOG", "fendas_grandes.jsonl"));
	}

	public void iniciar() {

		custoDeGas.atualizar();
		disparo.atualizarNonce();

		agenda.scheduleWithFixedDelay(cauda::atualizarPools, 20, 90, TimeUnit.SECONDS);
		agenda.scheduleWithFixedDelay(custoDeGas::atualizar, 10, 10, TimeUnit.SECONDS);
		agenda.scheduleWithFixedDelay(disparo::atualizarNonce, 5, 5, TimeUnit.SECONDS);
		aquecimento.scheduleWithFixedDelay(relay::aquecer, 1, 5, TimeUnit.SECONDS);
	}

	public void fechar() {

		agenda.shutdownNow();
		aquecimento.shutdownNow();
		gatilhos.close();
		cauda.close();
		backrun.close();
		disparo.close();
		desfechos.close();
	}

	public void aoAplicarCabecalho(Cabecalho cabecalho) {
		disparo.aoAplicarCabecalho(cabecalho);
	}

	public void aoFecharBloco(long altura) {
		if (!GARIMPO_POR_TX) {
			cauda.aoBloco(altura);
		}
		naBorda("desfechos: bloco fechado", () -> desfechos.blocoFechado(altura));
	}

	public void aoEnviar(Consumer<Desfechos.Envio> ouvinte) {
		ouvintesDeEnvio.add(ouvinte);
	}

	public void aoRecusarTentativa(BiConsumer<String, Map<String, Object>> ouvinte) {
		ouvintesDeRecusa.add(ouvinte);
	}

	public void aoDecidir(Consumer<Desfechos.Desfecho> ouvinte) {
		ouvintesDeDecisao.add(ouvinte);
	}

	public void aoConcluirAutopsia(BiConsumer<String, Map<String, Object>> ouvinte) {
		autopsia.aoConcluir(ouvinte);
	}

	public Lances lances() {
		return lances;
	}

	public CustoDeGas custoDeGas() {
		return custoDeGas;
	}

	public Gabarito gabarito() {
		return gabarito;
	}

	public int versaoDoExecutor() {
		return disparo.versaoDoExecutor();
	}

	public Desfechos desfechos() {
		return desfechos;
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("garimpo_disparado", GARIMPO_POR_TX ? "por_tx (antigo)" : "por_bloco");
		resumo.put("economia", Map.of("piso_margem_centimos", custoDeGas.margemDoPisoCentimos(),
				"gas_tx_pagamento_builder", CustoDeGas.GAS_TX_PAGAMENTO_BUILDER, "sobra_liquida_minima_wei", "1"));
		resumo.put("falhas", Falhas.resumo());
		resumo.put("desfechos", desfechos.resumo());
		resumo.put("lances", lances.resumo());
		resumo.put("cauda", cauda.resumo());
		resumo.put("backrun", backrun.resumo());
		resumo.put("gabarito", gabarito.resumo());
		resumo.put("gatilhos", gatilhos.resumo());
		resumo.put("disparo", Map.of("enviadas", disparo.enviadas(), "recusadas", disparo.recusadas(), "ultimo_envio",
				disparo.ultimoEnvio(), "motivos_do_contrato", disparo.motivos(), "gas_medido", disparo.gasMedido(),
				"autopsia", disparo.autopsias(), "recusas_do_relay", disparo.recusasDoRelay(), "milis", disparo.milis(),
				"executor_versao", disparo.versaoDoExecutor()));
		return resumo;
	}

	private static void naBorda(String origem, Runnable observador) {
		try {
			observador.run();
		} catch (RuntimeException e) {
			Falhas.anotar(origem, e);
		}
	}
}
