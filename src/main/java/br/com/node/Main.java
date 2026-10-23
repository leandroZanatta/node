package br.com.node;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import br.com.node.cadeia.Crivo;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Cauda;
import br.com.node.catalogo.Cotador;
import br.com.node.catalogo.Fabricas;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.ObservatorioArbitragem;
import br.com.node.catalogo.Quarentena;
import br.com.node.catalogo.Tarifario;
import br.com.node.catalogo.Taxas;
import br.com.node.descoberta.Descoberta;
import br.com.node.descoberta.Sementes;
import br.com.node.ensaio.Autopsia;
import br.com.node.ensaio.Backrun;
import br.com.node.ensaio.CustoDeGas;
import br.com.node.ensaio.DiarioDeEnsaio;
import br.com.node.ensaio.Disparo;
import br.com.node.ensaio.Gabarito;
import br.com.node.ensaio.Gatilhos;
import br.com.node.ensaio.RelayPrivado;
import br.com.node.ensaio.RpcDoEnsaio;
import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.mempool.ObservatorioMempool;
import br.com.node.mempool.Simulador;
import br.com.node.rede.Discv4;
import br.com.node.rede.Envelope;
import br.com.node.rede.PortaUdp;
import br.com.node.repository.ImpostosRepository;
import br.com.node.repository.PeersRepository;
import br.com.node.repository.PoolsNoGerenteRepository;
import br.com.node.repository.ProvasDeTaxaRepository;
import br.com.node.repository.postgres.Postgres;
import br.com.node.sessao.Lotacao;
import br.com.node.sessao.Sessoes;
import br.com.node.sincronizacao.Cabeca;
import br.com.node.sincronizacao.Cursor;
import br.com.node.sincronizacao.Frente;
import br.com.node.sincronizacao.Sincronizacao;
import br.com.node.sincronizacao.VarredorDeGerentes;
import br.com.node.sondagem.Rpc;
import br.com.node.sondagem.Sondagem;
import br.com.node.util.Diario;
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
		java.util.concurrent.ExecutorService chavesDeGerente = Executors.newSingleThreadExecutor(tarefa -> {
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
		java.util.Optional<VarredorDeGerentes> varredor = VarredorDeGerentes.urlDoAmbiente()
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
		RpcDoEnsaio rpcTrace = java.util.Optional.ofNullable(System.getenv("RPC_TRACE")).map(String::trim)
				.filter(u -> !u.isBlank()).map(urls -> java.util.Arrays.stream(urls.split(",")).map(String::trim)
						.filter(u -> !u.isBlank()).toList())
				.filter(urls -> !urls.isEmpty()).map(RpcDoEnsaio::new).orElse(rpcEnsaio);
		LOG.info(() -> rpcTrace == rpcEnsaio
				? "trace de pagamentos internos: usando RPC de execução (RPC_TRACE não configurado)"
				: "trace de pagamentos internos: usando RPC_TRACE dedicado");
		tarifario.porChamada(new br.com.node.catalogo.EspacamentoPorChamada(pool -> tickSpacing(rpcEnsaio, pool)));
		br.com.node.catalogo.Ticks ticks = new br.com.node.catalogo.Ticks(
				(para, dados, bloco) -> ethCall(rpcEnsaio, para, dados, bloco));
		catalogo.aoMudarLiquidez(ticks::aoMudarLiquidez);
		cotador.ticks(ticks);
		RelayPrivado relay = new RelayPrivado();
		CustoDeGas custoDeGas = new CustoDeGas(rpcEnsaio, catalogo);
		custoDeGas.atualizar();
		RpcDoEnsaio rpcDaAutopsia = java.util.Optional.ofNullable(System.getenv("RPC_LOGS")).map(String::trim)
				.filter(u -> !u.isBlank()).map(u -> new RpcDoEnsaio(List.of(u))).orElse(rpcEnsaio);
		Autopsia autopsia = new Autopsia(rpcDaAutopsia, quarentena, impostos, null);
		autopsia.aoConcluir(diarioDeEnsaio::autopsia);
		Disparo disparo = new Disparo(rpcEnsaio, relay, catalogo, tarifario, custoDeGas, impostos, autopsia,
				quarentena);
		disparo.atualizarNonce();
		br.com.node.ensaio.Desfechos desfechos = new br.com.node.ensaio.Desfechos(br.com.node.ensaio.Disparo.executor(),
				cabeca::momentos, null);
		Path arquivoPagamentos = Path
				.of(java.util.Optional.ofNullable(System.getenv("PAGAMENTOS_LOG")).orElse("pagamentos.jsonl"));
		Path arquivoVencedores = Path
				.of(java.util.Optional.ofNullable(System.getenv("VENCEDORES_LOG")).orElse("vencedores.jsonl"));
		br.com.node.ensaio.PagamentosDeBloco pagamentos = new br.com.node.ensaio.PagamentosDeBloco(rpcEnsaio,
				arquivoPagamentos, br.com.node.ensaio.PagamentosDeBloco.enderecosPadrao());
		br.com.node.ensaio.Lances lances = new br.com.node.ensaio.Lances(Path.of("lances.json"),
				br.com.node.ensaio.Lances.inicialDoAmbiente());
		disparo.lancesDe(lances);
		disparo.aoEnviar(envio -> {
			desfechos.registrar(envio);
			diarioDeEnsaio.envio(envio);
		});
		disparo.aoRecusarTentativa(diarioDeEnsaio::recusa);
		disparo.aoVeredito(desfechos::veredito);
		disparo.vereditoPor(rpcDaAutopsia);
		java.util.concurrent.ExecutorService bidLookup = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
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
		br.com.node.catalogo.Quentes quentes = new br.com.node.catalogo.Quentes(Path.of("quentes.json"));
		if (!"1".equals(System.getenv("CAUDA_UMA_CAMADA"))) {
			cauda.quentesDe(quentes);
		}
		cauda.valorarCom(
				achado -> custoDeGas.paraBnb(achado.ciclo().pernas().get(0).entra(), achado.oportunidade().lucro())
						.orElseThrow(() -> new IllegalStateException("fenda da cauda sem câmbio BNB para a raiz "
								+ java.util.HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()))));

		ObservatorioMempool mempool = new ObservatorioMempool(catalogo, tarifario::taxa, tarifario::espacamento,
				tarifario::solidlyStable, (overlay, crua) -> cauda.projetar(overlay, cabeca.alvo())
						.ifPresent(achado -> backrun.considerar(List.of(achado), crua)));
		Gatilhos gatilhos = new Gatilhos(rpcEnsaio, mempool::etapas, mempool::primeiraVisaoMs,
				arbitragem -> custoDeGas.paraBnbSeDisponivel(arbitragem.token(), arbitragem.sobra()));
		gatilhos.gravarFendasGrandesEm(Path
				.of(java.util.Optional.ofNullable(System.getenv("FENDAS_GRANDES_LOG")).orElse("fendas_grandes.jsonl")));
		br.com.node.ensaio.AutopsiaDoMercado mercado = new br.com.node.ensaio.AutopsiaDoMercado(catalogo, cotador,
				tarifario, custoDeGas, gabarito, Path.of("target", "mercado-" + carimbo + ".jsonl"));
		mercado.versaoDoExecutor(disparo.versaoDoExecutor());
		mercado.verificarCom(new br.com.node.ensaio.AutopsiaDoMercado.Saldos() {
			@Override
			public java.util.Optional<java.math.BigInteger> bnb(byte[] conta, long bloco) {
				com.google.gson.JsonArray parametros = new com.google.gson.JsonArray();
				parametros.add("0x" + java.util.HexFormat.of().formatHex(conta));
				parametros.add("0x" + Long.toHexString(bloco));
				return rpcEnsaio.chamar("eth_getBalance", parametros).map(com.google.gson.JsonParser::parseString)
						.map(com.google.gson.JsonElement::getAsJsonObject).filter(o -> o.has("result"))
						.map(o -> new java.math.BigInteger(o.get("result").getAsString().substring(2), 16));
			}

			@Override
			public java.util.Optional<java.math.BigInteger> token(byte[] token, byte[] conta, long bloco) {
				String dados = "70a08231" + "0".repeat(24) + java.util.HexFormat.of().formatHex(conta);
				return ethCall(rpcEnsaio, java.util.HexFormat.of().formatHex(token), dados,
						"0x" + Long.toHexString(bloco)).filter(r -> r.length() >= 66)
						.map(r -> new java.math.BigInteger(r.substring(2, 66), 16));
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
		br.com.node.api.HttpApi httpApi;
		try {
			httpApi = new br.com.node.api.HttpApi(apiPorta, relatorio, mercado, arquivoDespachos, arquivoPagamentos,
					arquivoVencedores, Path.of(java.util.Optional.ofNullable(System.getenv("FENDAS_GRANDES_LOG"))
							.orElse("fendas_grandes.jsonl")),
					() -> {
						Map<String, Object> p = new LinkedHashMap<>();
						p.put("v2", catalogo.poolsV2());
						p.put("v3", catalogo.poolsV3());
						p.put("pares_conhecidos", catalogo.paresConhecidos());
						p.put("descartadas", catalogo.poolsDescartadas());
						return p;
					}, quarentena);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("não foi possível iniciar HTTP API na porta " + apiPorta, e);
		}
		httpApi.iniciar();

		LOG.info(() -> "node " + chave.idHex().substring(0, 16) + "… no ar; relatório em " + relatorio.arquivo());

		Thread.currentThread().join();
	}

	private static java.util.Optional<String> ethCall(RpcDoEnsaio rpc, String para, String dados, String bloco) {

		try {

			com.google.gson.JsonObject chamada = new com.google.gson.JsonObject();
			chamada.addProperty("to", "0x" + para);
			chamada.addProperty("data", "0x" + dados);
			com.google.gson.JsonArray parametros = new com.google.gson.JsonArray();
			parametros.add(chamada);
			parametros.add(bloco == null ? "latest" : bloco);

			return rpc.chamar("eth_call", parametros).map(com.google.gson.JsonParser::parseString)
					.map(com.google.gson.JsonElement::getAsJsonObject).filter(o -> o.has("result"))
					.map(o -> o.get("result").getAsString());

		} catch (RuntimeException e) {
			return java.util.Optional.empty();
		}
	}

	private static java.util.OptionalInt tickSpacing(RpcDoEnsaio rpc, byte[] pool) {

		try {

			com.google.gson.JsonObject chamada = new com.google.gson.JsonObject();
			chamada.addProperty("to", "0x" + java.util.HexFormat.of().formatHex(pool));
			chamada.addProperty("data", "0xd0c93a7c");
			com.google.gson.JsonArray parametros = new com.google.gson.JsonArray();
			parametros.add(chamada);
			parametros.add("latest");

			return rpc.chamar("eth_call", parametros).map(com.google.gson.JsonParser::parseString)
					.map(com.google.gson.JsonElement::getAsJsonObject).filter(o -> o.has("result"))
					.map(o -> o.get("result").getAsString()).filter(r -> r.length() == 66)
					.map(r -> java.util.OptionalInt.of(new java.math.BigInteger(r.substring(2), 16).intValue()))
					.orElse(java.util.OptionalInt.empty());

		} catch (RuntimeException e) {
			return java.util.OptionalInt.empty();
		}
	}

	private static boolean ganhouOutro(br.com.node.ensaio.Desfechos.Desfecho desfecho) {
		return switch (desfecho.classe()) {
		case "rival_n1", "rival_n2", "pool_mexida_n1", "pool_mexida_n2" -> true;
		default -> false;
		};
	}

	private static void registrarVencedor(br.com.node.ensaio.Desfechos.Desfecho desfecho, RpcDoEnsaio rpc,
			RpcDoEnsaio rpcTrace, Path arquivo) {
		try {
			gravarVencedor(desfecho, rpc, rpcTrace, arquivo);
		} catch (RuntimeException e) {
			LOG.warning(() -> "vencedor do leilão não lido para " + desfecho.envio().hash() + ": " + e);
		}
	}

	private static void gravarVencedor(br.com.node.ensaio.Desfechos.Desfecho desfecho, RpcDoEnsaio rpc,
			RpcDoEnsaio rpcTrace, Path arquivo) {

		int saltos = desfecho.classe().endsWith("n2") ? 2 : 1;
		long alturaDoVencedor = desfecho.envio().altura() + saltos;

		com.google.gson.JsonObject linha;
		com.google.gson.JsonObject bloco = rpc.bloco(alturaDoVencedor).orElse(null);
		if (bloco == null) {
			linha = new com.google.gson.JsonObject();
			linha.addProperty("nosso_hash", desfecho.envio().hash());
			linha.addProperty("classe", desfecho.classe());
			linha.addProperty("erro", "bloco do vencedor indisponível no RPC");
		} else {
			linha = br.com.node.ensaio.VencedorDoLeilao.extrair(desfecho.envio().hash(), desfecho.classe(),
					alturaDoVencedor, desfecho.posicao(), bloco,
					br.com.node.ensaio.PagamentosDeBloco.enderecosPadrao());
			if (linha.has("hash_vencedor")) {
				br.com.node.ensaio.TransferenciasInternas.anexar(rpcTrace, linha.get("hash_vencedor").getAsString(),
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
					BigInteger taxaGas = br.com.node.ensaio.Lance48Club.taxaGas(r.gasUsado(), precoGas);
					linha.addProperty("taxa_gas_vencedor_wei", taxaGas == null ? null : taxaGas.toString());
				});
				br.com.node.ensaio.VencedorDoLeilao.medirPagamento48Club(linha);
			}
		}
		br.com.node.ensaio.VencedorDoLeilao.gravar(arquivo, linha);
	}

	private static java.util.Optional<BigInteger> bidRealDoRival(br.com.node.ensaio.Desfechos.Desfecho desfecho,
			RpcDoEnsaio rpc) {

		long alturaN1 = desfecho.envio().altura() + 1;
		int posArb = desfecho.posicao();

		com.google.gson.JsonObject bloco = rpc.bloco(alturaN1).orElse(null);
		if (bloco == null || !bloco.has("transactions"))
			return java.util.Optional.empty();
		com.google.gson.JsonArray txs = bloco.getAsJsonArray("transactions");
		if (posArb < 0 || posArb >= txs.size())
			return java.util.Optional.empty();

		com.google.gson.JsonObject txArb = txs.get(posArb).getAsJsonObject();

		BigInteger bnbPagamento = br.com.node.ensaio.PagamentosDeBloco
				.pagamentoAoBuilder(bloco, posArb, br.com.node.ensaio.PagamentosDeBloco.enderecosPadrao())
				.orElse(BigInteger.ZERO);

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
			br.com.node.util.Falhas.anotar(origem, e);
		}
	}

	private static final boolean GARIMPO_POR_TX = "1".equals(System.getenv("GARIMPO_POR_TX"));

	private static Map<String, Object> ensaioResumo(Cauda cauda, Backrun backrun, Disparo disparo, Gabarito gabarito,
			Gatilhos gatilhos, Simulador simulador, Impostos impostos, br.com.node.ensaio.Desfechos desfechos,
			br.com.node.ensaio.Lances lances, CustoDeGas custoDeGas, RpcDoEnsaio rpcTrace, String origemRpcTrace) {

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