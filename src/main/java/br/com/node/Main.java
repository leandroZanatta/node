package br.com.node;
import br.com.node.auditoria.relatorio.RelatorioExecucao;


import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.auditoria.api.HttpApi;
import br.com.node.baixa.sync.Crivo;
import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.execucao.garimpo.Cauda;
import br.com.node.transformacao.catalogo.Cotador;
import br.com.node.transformacao.catalogo.EspacamentoPorChamada;
import br.com.node.transformacao.catalogo.Fabricas;
import br.com.node.transformacao.catalogo.Impostos;
import br.com.node.transformacao.catalogo.ObservatorioArbitragem;
import br.com.node.transformacao.catalogo.Quarentena;
import br.com.node.transformacao.catalogo.Quentes;
import br.com.node.transformacao.catalogo.Tarifario;
import br.com.node.transformacao.catalogo.Taxas;
import br.com.node.transformacao.catalogo.Ticks;
import br.com.node.baixa.p2p.Descoberta;
import br.com.node.baixa.p2p.Sementes;
import br.com.node.auditoria.registro.Autopsia;
import br.com.node.auditoria.mercado.AutopsiaDoMercado;
import br.com.node.execucao.disparo.Backrun;
import br.com.node.execucao.disparo.CustoDeGas;
import br.com.node.execucao.disparo.Desfechos;
import br.com.node.auditoria.registro.DiarioDeEnsaio;
import br.com.node.execucao.disparo.Disparo;
import br.com.node.execucao.disparo.Gabarito;
import br.com.node.execucao.disparo.Gatilhos;
import br.com.node.execucao.bundle.Lance48Club;
import br.com.node.execucao.disparo.Lances;
import br.com.node.auditoria.pagamentos.PagamentosDeBloco;
import br.com.node.execucao.disparo.RelayPrivado;
import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.auditoria.pagamentos.TransferenciasInternas;
import br.com.node.auditoria.pagamentos.VencedorDoLeilao;
import br.com.node.baixa.protocolo.TabelaDeRotas;
import br.com.node.transformacao.mempool.ObservatorioMempool;
import br.com.node.transformacao.mempool.Simulador;
import br.com.node.baixa.protocolo.Discv4;
import br.com.node.baixa.protocolo.Envelope;
import br.com.node.baixa.protocolo.PortaUdp;
import br.com.node.baixa.persistencia.ImpostosRepository;
import br.com.node.baixa.persistencia.PeersRepository;
import br.com.node.baixa.persistencia.PoolsNoGerenteRepository;
import br.com.node.baixa.persistencia.ProvasDeTaxaRepository;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.baixa.p2p.Lotacao;
import br.com.node.baixa.p2p.Sessoes;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.baixa.sync.Cursor;
import br.com.node.baixa.sync.Frente;
import br.com.node.baixa.sync.Sincronizacao;
import br.com.node.baixa.sync.VarredorDeGerentes;
import br.com.node.baixa.rpc.Rpc;
import br.com.node.transformacao.sondagem.Sondagem;
import br.com.node.util.Diario;
import br.com.node.util.Falhas;
import br.com.node.util.Identidade;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.NoRemoto;

public final class Main {

	static {
		Diario.ligar();
	}

	private static final Logger LOG = Logger.getLogger(Main.class.getName());

	private static final int GRAVACAO_SEGUNDOS = Resources.inteiro("persistencia.taxas.intervalo.segundos");

	public static void main(String[] argumentos) throws InterruptedException {

		Postgres banco = new Postgres();

		banco.migrar();

		PeersRepository peers = new PeersRepository(banco);

		NodeKey chave = Identidade.carregar();

		PortaUdp porta = new PortaUdp(Resources.inteiro("node.porta.udp"));
		Discv4 discv4 = new Discv4(new Envelope(chave));
		TabelaDeRotas tabela = new TabelaDeRotas(chave.id(), Resources.inteiro("descoberta.nos.por.balde"));

		Sessoes sessoes = new Sessoes(porta, discv4, tabela, chave);

		Descoberta descoberta = new Descoberta(discv4, sessoes, tabela, sementes(peers), peers::salvar);
		Lotacao lotacao = new Lotacao(sessoes, Crivo.corteDeHoje()::aceita);

		Catalogo catalogo = new Catalogo();

		PoolsNoGerenteRepository poolsNoGerente = new PoolsNoGerenteRepository(banco);
		ExecutorService chavesDeGerente = Executors.newSingleThreadExecutor(tarefa -> {
			Thread thread = new Thread(tarefa, "chaves-de-gerente");
			thread.setDaemon(true);
			return thread;
		});
		catalogo.aoFaltarChave((gerente, poolId) -> chavesDeGerente.execute(() -> {
			try {
				poolsNoGerente.buscar(gerente, poolId).ifPresent(catalogo::aprenderChave);
			} catch (RuntimeException falhou) {
				LOG.warning(() -> "chave de pool de gerente não veio do banco: " + falhou.getMessage());
			}
		}));
		catalogo.aoNascer(chaveNova -> chavesDeGerente.execute(() -> {
			try {
				poolsNoGerente.gravar(List.of(chaveNova));
			} catch (RuntimeException falhou) {
				LOG.warning(() -> "pool de gerente nascida ao vivo não gravou: " + falhou.getMessage());
			}
		}));
		Optional<VarredorDeGerentes> varredor = VarredorDeGerentes.urlDoAmbiente()
				.map(url -> new VarredorDeGerentes(url, poolsNoGerente, catalogo::liberarPedidas));
		LOG.info(() -> varredor.isPresent() ? "varredura de gerentes: ativa (RPC_LOGS)"
				: "varredura de gerentes: DESLIGADA (sem RPC_LOGS) — só pools que nascerem ao vivo terão chave");

		Impostos impostos = new Impostos();

		ImpostosRepository impostosProvados = new ImpostosRepository(banco);
		impostos.semear(impostosProvados.carregar());
		impostos.aoProvar(impostosProvados::anotar);

		ProvasDeTaxaRepository provas = new ProvasDeTaxaRepository(banco);
		Taxas taxas = new Taxas();

		taxas.semear(provas.carregar());
		taxas.aoProvar(provas::anotar);

		ScheduledExecutorService gravacao = Executors
				.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "gravacao-de-taxas"));

		Cabeca cabeca = new Cabeca();
		Fabricas fabricas = new Fabricas();
		Quarentena quarentena = new Quarentena(Path.of("quarentena.json"));
		Tarifario tarifario = new Tarifario(catalogo, taxas, fabricas, quarentena);
		Cotador cotador = new Cotador(catalogo, impostos, tarifario, quarentena);
		String carimbo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		DiarioDeEnsaio diarioDeEnsaio = new DiarioDeEnsaio(Path.of("target", "ensaio.jsonl"));

		RpcDoEnsaio rpcEnsaio = new RpcDoEnsaio();
		RpcDoEnsaio rpcTrace = Optional.ofNullable(System.getenv("RPC_TRACE")).map(String::trim)
				.filter(u -> !u.isBlank()).map(urls -> java.util.Arrays.stream(urls.split(",")).map(String::trim)
						.filter(u -> !u.isBlank()).toList())
				.filter(urls -> !urls.isEmpty()).map(RpcDoEnsaio::new).orElse(rpcEnsaio);
		LOG.info(() -> rpcTrace == rpcEnsaio
				? "trace de pagamentos internos: usando RPC de execução (RPC_TRACE não configurado)"
				: "trace de pagamentos internos: usando RPC_TRACE dedicado");
		tarifario.porChamada(new EspacamentoPorChamada(pool -> tickSpacing(rpcEnsaio, pool)));
		Ticks ticks = new Ticks((para, dados, bloco) -> ethCall(rpcEnsaio, para, dados, bloco));
		catalogo.aoMudarLiquidez(ticks::aoMudarLiquidez);
		cotador.ticks(ticks);
		RelayPrivado relay = new RelayPrivado();
		CustoDeGas custoDeGas = new CustoDeGas(rpcEnsaio, catalogo);
		custoDeGas.atualizar();
		RpcDoEnsaio rpcDaAutopsia = Optional.ofNullable(System.getenv("RPC_LOGS")).map(String::trim)
				.filter(u -> !u.isBlank()).map(u -> new RpcDoEnsaio(List.of(u))).orElse(rpcEnsaio);
		Autopsia autopsia = new Autopsia(rpcDaAutopsia, quarentena, impostos, null);
		autopsia.aoConcluir(diarioDeEnsaio::autopsia);
		Disparo disparo = new Disparo(rpcEnsaio, relay, catalogo, tarifario, custoDeGas, impostos, autopsia,
				quarentena);
		disparo.atualizarNonce();
		Desfechos desfechos = new Desfechos(Disparo.executor(), cabeca::momentos, null);
		Path arquivoPagamentos = Path
				.of(Optional.ofNullable(System.getenv("PAGAMENTOS_LOG")).orElse("pagamentos.jsonl"));
		Path arquivoVencedores = Path
				.of(Optional.ofNullable(System.getenv("VENCEDORES_LOG")).orElse("vencedores.jsonl"));
		PagamentosDeBloco pagamentos = new PagamentosDeBloco(rpcEnsaio, arquivoPagamentos,
				PagamentosDeBloco.enderecosPadrao());
		Lances lances = new Lances(Path.of("lances.json"), Lances.inicialDoAmbiente());
		disparo.lancesDe(lances);
		disparo.aoEnviar(envio -> {
			desfechos.registrar(envio);
			diarioDeEnsaio.envio(envio);
		});
		disparo.aoRecusarTentativa(diarioDeEnsaio::recusa);
		disparo.aoVeredito(desfechos::veredito);
		disparo.vereditoPor(rpcDaAutopsia);
		ExecutorService bidLookup = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "bid-lookup");
			t.setDaemon(true);
			return t;
		});
		desfechos.aoDecidir(desfecho -> {
			if (ganhouOutro(desfecho) && desfecho.posicao() != null) {
				bidLookup.submit(() -> registrarVencedor(desfecho, rpcEnsaio, rpcTrace, arquivoVencedores));
			}
			boolean deveEnriquecer = (desfecho.classe().equals("pool_mexida_n1")
					|| desfecho.classe().equals("rival_n1")) && desfecho.folgaMs() != null && desfecho.folgaMs() > 0
					&& desfecho.posicao() != null;
			if (deveEnriquecer) {
				bidLookup.submit(() -> {
					java.util.Optional<BigInteger> bidOpt = bidRealDoRival(desfecho, rpcEnsaio);
					synchronized (lances) {
						lances.aprender(desfecho, bidOpt.orElse(null));
					}
					diarioDeEnsaio.desfecho(desfecho, lances.fracaoPara(desfecho.envio().rota()), bidOpt.isPresent());
				});
			} else {
				synchronized (lances) {
					lances.aprender(desfecho);
				}
				diarioDeEnsaio.desfecho(desfecho, lances.fracaoPara(desfecho.envio().rota()));
			}
		});
		Gabarito gabarito = new Gabarito(cotador::motivoDeFora);
		Backrun backrun = new Backrun(custoDeGas, disparo, gabarito);
		backrun.medirCom(cabeca::momentos);
		disparo.medirCom(cabeca::momentos);
		Cauda cauda = new Cauda(catalogo, cotador, tarifario, achado -> backrun.considerar(List.of(achado), null));
		cauda.quarentena(quarentena);
		cauda.pontaLivre(disparo.versaoDoExecutor() >= 3);
		cauda.aoSemFlashAchar(achado -> gabarito.anotar(achado, false));
		Quentes quentes = new Quentes(Path.of("quentes.json"));
		if (!"1".equals(System.getenv("CAUDA_UMA_CAMADA"))) {
			cauda.quentesDe(quentes);
		}
		cauda.valorarCom(
				achado -> custoDeGas.paraBnb(achado.ciclo().pernas().get(0).entra(), achado.oportunidade().lucro())
						.orElseThrow(() -> new IllegalStateException("fenda da cauda sem câmbio BNB para a raiz "
								+ HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()))));

		ObservatorioMempool mempool = new ObservatorioMempool(catalogo, tarifario::taxa, tarifario::espacamento,
				tarifario::solidlyStable, (overlay, crua) -> cauda.projetar(overlay, cabeca.alvo())
						.ifPresent(achado -> backrun.considerar(List.of(achado), crua)));
		Gatilhos gatilhos = new Gatilhos(rpcEnsaio, mempool::etapas, mempool::primeiraVisaoMs,
				arbitragem -> custoDeGas.paraBnbSeDisponivel(arbitragem.token(), arbitragem.sobra()));
		gatilhos.gravarFendasGrandesEm(
				Path.of(Optional.ofNullable(System.getenv("FENDAS_GRANDES_LOG")).orElse("fendas_grandes.jsonl")));
		AutopsiaDoMercado mercado = new AutopsiaDoMercado(catalogo, cotador, tarifario, custoDeGas,
				gabarito, Path.of("target", "mercado-" + carimbo + ".jsonl"));
		mercado.versaoDoExecutor(disparo.versaoDoExecutor());
		mercado.verificarCom(new AutopsiaDoMercado.Saldos() {
			@Override
			public Optional<BigInteger> bnb(byte[] conta, long bloco) {
				JsonArray parametros = new JsonArray();
				parametros.add("0x" + HexFormat.of().formatHex(conta));
				parametros.add("0x" + Long.toHexString(bloco));
				return rpcEnsaio.chamar("eth_getBalance", parametros).map(JsonParser::parseString)
						.map(JsonElement::getAsJsonObject).filter(o -> o.has("result"))
						.map(o -> new BigInteger(o.get("result").getAsString().substring(2), 16));
			}

			@Override
			public Optional<BigInteger> token(byte[] token, byte[] conta, long bloco) {
				String dados = "70a08231" + "0".repeat(24) + HexFormat.of().formatHex(conta);
				return ethCall(rpcEnsaio, HexFormat.of().formatHex(token), dados, "0x" + Long.toHexString(bloco))
						.filter(r -> r.length() >= 66).map(r -> new java.math.BigInteger(r.substring(2, 66), 16));
			}
		});
		Simulador simulador = new Simulador(rpcEnsaio::chamar, mempool::aprenderDe);
		mempool.aoNaoDecodificar(simulador::talvez);
		mempool.aoValidarDecodificado(simulador::validar);
		mempool.aoBlocoComPrecos(desfechos::precos);
		ObservatorioArbitragem observatorio = new ObservatorioArbitragem(catalogo,
				(ObservatorioArbitragem.AoConfirmar) (arbitragem, altura, indice, gatilho) -> {
					mempool.confirmar(arbitragem, altura);
					gabarito.confirmar(arbitragem, altura);
					mercado.confirmar(arbitragem, altura, indice, gatilho);
					gatilhos.investigar(arbitragem, altura, indice, gatilho);
					desfechos.arbitragem(arbitragem, altura, indice);
					quentes.vistaEmArbitragem(
							arbitragem.pools().stream().map(java.util.HexFormat.of()::formatHex).toList());
				});

		Frente frente = new Frente(sessoes, Resources.inteiro("sincronizacao.lote"));
		Cursor cursor = new Cursor(frente, catalogo, transicao -> {
			naBorda("taxas: observar", () -> taxas.observar(transicao));
			naBorda("impostos: observar", () -> impostos.observar(transicao));
		}, (logs, altura) -> {
			naBorda("observatório: observar", () -> observatorio.observar(logs, altura));
			naBorda("desfechos: transação", () -> desfechos.transacao(logs, altura));
			cauda.tocou(logs);
			if (GARIMPO_POR_TX) {
				cauda.aoBloco(altura);
			}
		});
		cursor.aoFecharBloco(altura -> {
			if (!GARIMPO_POR_TX) {
				cauda.aoBloco(altura);
			}
			naBorda("desfechos: bloco fechado", () -> desfechos.blocoFechado(altura));
			naBorda("pagamentos: bloco fechado", () -> pagamentos.aoFecharBloco(altura));
		});
		cursor.antesDaTransacao((logs, altura) -> naBorda("mercado: antes", () -> mercado.antes(logs, altura)));

		Sincronizacao sincronizacao = new Sincronizacao(cabeca, frente, cursor);
		Path arquivoDoRelatorio = Path.of("target", "relatorio-execucao-" + carimbo + ".jsonl");
		RelatorioExecucao relatorio = new RelatorioExecucao(arquivoDoRelatorio, descoberta, sessoes, tabela, lotacao,
				cabeca, cursor, catalogo, lotacao::quedas, lotacao::naoTratadas, observatorio::resumo, mempool::resumo,
				() -> {
					Map<String, Object> resumo = ensaioResumo(cauda, backrun, disparo, gabarito, gatilhos, simulador,
							impostos, desfechos, lances, custoDeGas, rpcTrace,
							rpcTrace == rpcEnsaio ? "RPC de execucao padrao" : "RPC_TRACE");
					resumo.put("frente", frente.resumo());
					resumo.put("peers", lotacao.resumoDePeers());
					varredor.ifPresent(v -> resumo.put("varredura_de_gerentes", v.resumo()));
					resumo.put("mercado", mercado.resumo());
					resumo.put("ticks", ticks.resumo());
					return resumo;
				});

		Sondagem sondagem = new Sondagem(catalogo, impostos, new Rpc());

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			varredor.ifPresent(VarredorDeGerentes::close);
			mercado.close();
			chavesDeGerente.shutdownNow();
			mempool.close();
			gatilhos.close();
			simulador.close();
			relatorio.registrar("final");
			relatorio.close();
			cauda.close();
			backrun.close();
			disparo.close();
			desfechos.close();
			pagamentos.close();
			diarioDeEnsaio.close();
			quentes.salvar();
			sondagem.close();
			sincronizacao.close();
			gravacao.shutdownNow();
			bidLookup.shutdownNow();
			descarregar(provas, impostosProvados);
			lotacao.close();
			descoberta.close();
			porta.close();
			banco.close();
		}, "encerramento"));

		porta.abrir(descoberta::receber);

		descoberta.iniciar();
		boolean atalhoDaCabeca = !"0".equals(System.getenv("ATALHO_CABECA"));
		lotacao.aoReceberBloco((sessao, bloco) -> {
			if (atalhoDaCabeca) {
				frente.adiantar(sessao, bloco.cabecalho());
			}
			disparo.aoAplicarCabecalho(bloco.cabecalho());
			mempool.blocoAnunciado(bloco);
		});
		if (atalhoDaCabeca) {
			lotacao.aoAnunciarDe(frente::anunciado);
		}
		lotacao.iniciar(cabeca::anunciar, mempool::observar, mempool::observar);
		sincronizacao.iniciar();
		sondagem.iniciar();
		varredor.ifPresent(VarredorDeGerentes::iniciar);
		gravacao.scheduleWithFixedDelay(() -> descarregar(provas, impostosProvados), GRAVACAO_SEGUNDOS,
				GRAVACAO_SEGUNDOS, TimeUnit.SECONDS);
		gravacao.scheduleWithFixedDelay(cauda::atualizarPools, 20, 90, TimeUnit.SECONDS);
		gravacao.scheduleWithFixedDelay(quentes::salvar, 60, 60, TimeUnit.SECONDS);
		gravacao.scheduleWithFixedDelay(custoDeGas::atualizar, 10, 10, TimeUnit.SECONDS);
		gravacao.scheduleWithFixedDelay(disparo::atualizarNonce, 5, 5, TimeUnit.SECONDS);
		ScheduledExecutorService aquecimento = Executors.newSingleThreadScheduledExecutor(tarefa -> {
			Thread thread = new Thread(tarefa, "relay-aquecimento");
			thread.setDaemon(true);
			return thread;
		});
		aquecimento.scheduleWithFixedDelay(relay::aquecer, 1, 5, TimeUnit.SECONDS);
		gravacao.scheduleWithFixedDelay(() -> relatorio.registrar("snapshot"), 0, 60, TimeUnit.SECONDS);

		int apiPorta = Integer.parseInt(System.getenv().getOrDefault("API_PORTA", "8080"));
		Path arquivoDespachos = Path.of("target", "ensaio.jsonl");
		HttpApi httpApi;
		try {
			httpApi = new HttpApi(apiPorta, relatorio, mercado, arquivoDespachos, arquivoPagamentos, arquivoVencedores,
					Path.of(java.util.Optional.ofNullable(System.getenv("FENDAS_GRANDES_LOG"))
							.orElse("fendas_grandes.jsonl")),
					() -> {
						Map<String, Object> p = new LinkedHashMap<>();
						p.put("v2", catalogo.poolsV2());
						p.put("v3", catalogo.poolsV3());
						p.put("pares_conhecidos", catalogo.paresConhecidos());
						p.put("descartadas", catalogo.poolsDescartadas());
						return p;
					}, quarentena);
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível iniciar HTTP API na porta " + apiPorta, e);
		}
		httpApi.iniciar();

		LOG.info(() -> "node " + chave.idHex().substring(0, 16) + "… no ar; relatório em " + relatorio.arquivo());

		Thread.currentThread().join();
	}

	private static Optional<String> ethCall(RpcDoEnsaio rpc, String para, String dados, String bloco) {

		try {

			JsonObject chamada = new JsonObject();
			chamada.addProperty("to", "0x" + para);
			chamada.addProperty("data", "0x" + dados);
			JsonArray parametros = new JsonArray();
			parametros.add(chamada);
			parametros.add(bloco == null ? "latest" : bloco);

			return rpc.chamar("eth_call", parametros).map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
					.filter(o -> o.has("result")).map(o -> o.get("result").getAsString());

		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static OptionalInt tickSpacing(RpcDoEnsaio rpc, byte[] pool) {

		try {

			JsonObject chamada = new JsonObject();
			chamada.addProperty("to", "0x" + HexFormat.of().formatHex(pool));
			chamada.addProperty("data", "0xd0c93a7c");
			JsonArray parametros = new JsonArray();
			parametros.add(chamada);
			parametros.add("latest");

			return rpc.chamar("eth_call", parametros).map(JsonParser::parseString).map(JsonElement::getAsJsonObject)
					.filter(o -> o.has("result")).map(o -> o.get("result").getAsString()).filter(r -> r.length() == 66)
					.map(r -> OptionalInt.of(new BigInteger(r.substring(2), 16).intValue()))
					.orElse(OptionalInt.empty());

		} catch (RuntimeException e) {
			return OptionalInt.empty();
		}
	}

	private static boolean ganhouOutro(Desfechos.Desfecho desfecho) {
		return switch (desfecho.classe()) {
		case "rival_n1", "rival_n2", "pool_mexida_n1", "pool_mexida_n2" -> true;
		default -> false;
		};
	}

	private static void registrarVencedor(Desfechos.Desfecho desfecho, RpcDoEnsaio rpc, RpcDoEnsaio rpcTrace,
			Path arquivo) {
		try {
			gravarVencedor(desfecho, rpc, rpcTrace, arquivo);
		} catch (RuntimeException e) {
			LOG.warning(() -> "vencedor do leilão não lido para " + desfecho.envio().hash() + ": " + e);
		}
	}

	private static void gravarVencedor(Desfechos.Desfecho desfecho, RpcDoEnsaio rpc, RpcDoEnsaio rpcTrace,
			Path arquivo) {

		int saltos = desfecho.classe().endsWith("n2") ? 2 : 1;
		long alturaDoVencedor = desfecho.envio().altura() + saltos;

		JsonObject linha;
		JsonObject bloco = rpc.bloco(alturaDoVencedor).orElse(null);
		if (bloco == null) {
			linha = new JsonObject();
			linha.addProperty("nosso_hash", desfecho.envio().hash());
			linha.addProperty("classe", desfecho.classe());
			linha.addProperty("erro", "bloco do vencedor indisponível no RPC");
		} else {
			linha = VencedorDoLeilao.extrair(desfecho.envio().hash(), desfecho.classe(), alturaDoVencedor,
					desfecho.posicao(), bloco, PagamentosDeBloco.enderecosPadrao());
			if (linha.has("hash_vencedor")) {
				TransferenciasInternas.anexar(rpcTrace, linha.get("hash_vencedor").getAsString(),
						System.getenv("BSCSCAN_API_KEY"), linha);
				rpc.recibo(linha.get("hash_vencedor").getAsString()).ifPresent(r -> {
					linha.addProperty("sucesso_vencedor", r.sucesso());
					linha.addProperty("gas_usado_vencedor", r.gasUsado().toString());
					if (r.precoEfetivoGas() != null) {
						linha.addProperty("gas_preco_efetivo_wei", r.precoEfetivoGas().toString());
					}
					BigInteger precoGas = r.precoEfetivoGas();
					if (precoGas == null && linha.has("gas_price_wei") && !linha.get("gas_price_wei").isJsonNull()) {
						precoGas = new BigInteger(linha.get("gas_price_wei").getAsString());
					}
					BigInteger taxaGas = Lance48Club.taxaGas(r.gasUsado(), precoGas);
					linha.addProperty("taxa_gas_vencedor_wei", taxaGas == null ? null : taxaGas.toString());
				});
				VencedorDoLeilao.medirPagamento48Club(linha);
			}
		}
		VencedorDoLeilao.gravar(arquivo, linha);
	}

	private static Optional<BigInteger> bidRealDoRival(Desfechos.Desfecho desfecho, RpcDoEnsaio rpc) {

		long alturaN1 = desfecho.envio().altura() + 1;
		int posArb = desfecho.posicao();

		JsonObject bloco = rpc.bloco(alturaN1).orElse(null);
		if (bloco == null || !bloco.has("transactions"))
			return Optional.empty();
		JsonArray txs = bloco.getAsJsonArray("transactions");
		if (posArb < 0 || posArb >= txs.size())
			return Optional.empty();

		JsonObject txArb = txs.get(posArb).getAsJsonObject();

		BigInteger bnbPagamento = PagamentosDeBloco
				.pagamentoAoBuilder(bloco, posArb, PagamentosDeBloco.enderecosPadrao()).orElse(BigInteger.ZERO);

		if (bnbPagamento.signum() <= 0)
			return java.util.Optional.empty();

		BigInteger bnbFinal = bnbPagamento;
		String hashArb = txArb.get("hash").getAsString();
		return rpc.recibo(hashArb).filter(r -> r.sucesso()).map(r -> r.sucesso() ? bnbFinal : null);
	}

	private static void descarregar(ProvasDeTaxaRepository provas, ImpostosRepository impostosProvados) {

		try {

			int taxa = provas.descarregar();
			int imposto = impostosProvados.descarregar();

			if (taxa + imposto > 0) {
				LOG.fine(() -> taxa + " provas de taxa e " + imposto + " de imposto gravadas");
			}

		} catch (RuntimeException e) {
			LOG.warning(() -> "gravação de provas falhou, fica para a próxima: " + e.getMessage());
		}
	}

	private static void naBorda(String origem, Runnable observador) {
		try {
			observador.run();
		} catch (RuntimeException e) {
			Falhas.anotar(origem, e);
		}
	}

	private static final boolean GARIMPO_POR_TX = "1".equals(System.getenv("GARIMPO_POR_TX"));

	private static Map<String, Object> ensaioResumo(Cauda cauda, Backrun backrun, Disparo disparo, Gabarito gabarito,
			Gatilhos gatilhos, Simulador simulador, Impostos impostos, Desfechos desfechos, Lances lances,
			CustoDeGas custoDeGas, RpcDoEnsaio rpcTrace, String origemRpcTrace) {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("garimpo_disparado", GARIMPO_POR_TX ? "por_tx (antigo)" : "por_bloco");
		resumo.put("economia", Map.of("piso_margem_centimos", custoDeGas.margemDoPisoCentimos(),
				"gas_tx_pagamento_builder", CustoDeGas.GAS_TX_PAGAMENTO_BUILDER, "sobra_liquida_minima_wei", "1"));
		resumo.put("trace_rpc", Map.of("origem", origemRpcTrace, "hosts", rpcTrace.hosts()));
		resumo.put("falhas", br.com.node.util.Falhas.resumo());
		resumo.put("impostos_assumidos", impostos.assumidos());
		resumo.put("desfechos", desfechos.resumo());
		resumo.put("lances", lances.resumo());
		resumo.put("cauda", cauda.resumo());
		resumo.put("backrun", backrun.resumo());
		resumo.put("gabarito", gabarito.resumo());
		resumo.put("gatilhos", gatilhos.resumo());
		resumo.put("simulador", simulador.resumo());
		resumo.put("disparo", Map.of("enviadas", disparo.enviadas(), "recusadas", disparo.recusadas(), "ultimo_envio",
				disparo.ultimoEnvio(), "motivos_do_contrato", disparo.motivos(), "gas_medido", disparo.gasMedido(),
				"autopsia", disparo.autopsias(), "recusas_do_relay", disparo.recusasDoRelay(), "milis", disparo.milis(),
				"executor_versao", disparo.versaoDoExecutor()));

		return resumo;
	}

	private static List<NoRemoto> sementes(PeersRepository peers) {

		List<NoRemoto> todas = new ArrayList<>(Sementes.carregar());

		todas.addAll(peers.carregar());

		return todas;
	}
}