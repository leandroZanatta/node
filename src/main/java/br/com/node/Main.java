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
import br.com.node.sincronizacao.VarredorDeGerentes;
import br.com.node.catalogo.Cauda;
import br.com.node.catalogo.Cotador;
import br.com.node.catalogo.Fabricas;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.Quarentena;
import br.com.node.catalogo.ObservatorioArbitragem;
import br.com.node.catalogo.Tarifario;
import br.com.node.catalogo.Taxas;
import br.com.node.descoberta.Descoberta;
import br.com.node.descoberta.Sementes;
import br.com.node.ensaio.Autopsia;
import br.com.node.ensaio.Backrun;
import br.com.node.ensaio.CustoDeGas;
import br.com.node.ensaio.Disparo;
import br.com.node.ensaio.DiarioDeEnsaio;
import br.com.node.ensaio.Gabarito;
import br.com.node.ensaio.Gatilhos;
import br.com.node.mempool.Simulador;
import br.com.node.ensaio.RelayPrivado;
import br.com.node.ensaio.RpcDoEnsaio;
import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.mempool.ObservatorioMempool;
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
import br.com.node.sondagem.Rpc;
import br.com.node.sondagem.Sondagem;
import br.com.node.util.Identidade;
import br.com.node.util.Diario;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.NoRemoto;

public final class Main {

	static {
		// CICATRIZ: PRIMEIRA coisa do processo, antes de qualquer Logger emitir.
		// Bloco estático roda antes de qualquer campo estático (LOG incluso) e
		// antes do main; sem isso os primeiros LOG.* iam só para o console e o
		// arquivo nascia depois — perdendo o boot, que é o que mais importa
		// depurar. Diario não usa Resources (ver javadoc dela).
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

		// O `PoolKey` das pools de gerente (V4, Infinity CL): o catálogo pede a chave da pool que mexeu, e a
		// resposta vem do banco numa thread própria — o cursor não espera banco. A pool que nasce ao vivo vai
		// para o banco pelo mesmo caminho.
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
		tarifario.porChamada(new br.com.node.catalogo.EspacamentoPorChamada(pool -> tickSpacing(rpcEnsaio, pool)));
		// Os mapas de ticks: a pool de liquidez concentrada cota ATRAVESSANDO ticks, não só até o primeiro.
		br.com.node.catalogo.Ticks ticks = new br.com.node.catalogo.Ticks((para, dados, bloco) -> ethCall(rpcEnsaio, para, dados, bloco));
		catalogo.aoMudarLiquidez(ticks::aoMudarLiquidez);
		cotador.ticks(ticks);
		RelayPrivado relay = new RelayPrivado();
		CustoDeGas custoDeGas = new CustoDeGas(rpcEnsaio, catalogo);
		custoDeGas.atualizar();
		// A autópsia compara com o estado do BLOCO N: pelo `RPC_LOGS` (estado histórico) quando configurado; os
		// dataseeds públicos já o descartaram, e sem ele a autópsia diz "sem estado do bloco" em vez de comparar
		// com outro bloco.
		RpcDoEnsaio rpcDaAutopsia = java.util.Optional.ofNullable(System.getenv("RPC_LOGS")).map(String::trim)
				.filter(u -> !u.isBlank()).map(u -> new RpcDoEnsaio(List.of(u))).orElse(rpcEnsaio);
		Autopsia autopsia = new Autopsia(rpcDaAutopsia, quarentena, impostos,
				null);
		autopsia.aoConcluir(diarioDeEnsaio::autopsia);
		Disparo disparo = new Disparo(rpcEnsaio, relay, catalogo, tarifario, custoDeGas, impostos, autopsia,
				quarentena);
		disparo.atualizarNonce();
		// O desfecho de cada envio (e a folga até o bloco seguinte), e o lance que aprende com ele.
		br.com.node.ensaio.Desfechos desfechos = new br.com.node.ensaio.Desfechos(br.com.node.ensaio.Disparo.executor(),
				cabeca::momentos, null);
		Path arquivoPagamentos = Path.of(java.util.Optional.ofNullable(System.getenv("PAGAMENTOS_LOG")).orElse("pagamentos.jsonl"));
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
		// Busca o bid real do rival (gas + BNB ao builder EOA) de forma assíncrona para não bloquear o cursor.
		java.util.concurrent.ExecutorService bidLookup = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "bid-lookup");
			t.setDaemon(true);
			return t;
		});
		desfechos.aoDecidir(desfecho -> {
			boolean deveEnriquecer = (desfecho.classe().equals("pool_mexida_n1") || desfecho.classe().equals("rival_n1"))
					&& desfecho.folgaMs() != null && desfecho.folgaMs() > 0
					&& desfecho.posicao() != null;
			if (deveEnriquecer) {
				bidLookup.submit(() -> {
					java.util.Optional<BigInteger> bidOpt = bidRealDoRival(desfecho, rpcEnsaio);
					lances.aprender(desfecho, bidOpt.orElse(null));
					diarioDeEnsaio.desfecho(desfecho, lances.fracaoPara(desfecho.envio().rota()), bidOpt.isPresent());
				});
			} else {
				lances.aprender(desfecho);
				diarioDeEnsaio.desfecho(desfecho, lances.fracaoPara(desfecho.envio().rota()));
			}
		});
		Gabarito gabarito = new Gabarito(cotador::motivoDeFora);
		Backrun backrun = new Backrun(custoDeGas, disparo, gabarito);
		backrun.medirCom(cabeca::momentos);
		disparo.medirCom(cabeca::momentos);
		Cauda cauda = new Cauda(catalogo, cotador, tarifario, achado -> backrun.considerar(List.of(achado), null));
		// O Executor 3 empresta também da ponta V3 ou de gerente: a busca deixa de exigir V2 na entrada.
		cauda.pontaLivre(disparo.versaoDoExecutor() >= 3);
		cauda.aoSemFlashAchar(achado -> gabarito.anotar(achado, false));
		// Duas camadas: a rápida só nas pools QUENTES, o organizador no grafo inteiro. `CAUDA_UMA_CAMADA=1` volta
		// ao jeito antigo (uma camada, grafo inteiro a cada bloco), para o A/B no mesmo servidor.
		br.com.node.catalogo.Quentes quentes = new br.com.node.catalogo.Quentes(Path.of("quentes.json"));
		if (!"1".equals(System.getenv("CAUDA_UMA_CAMADA"))) {
			cauda.quentesDe(quentes);
		}
		// A raiz da cauda é sempre numerário (WBNB/USDT): sem câmbio é erro, não fenda de valor zero.
		cauda.valorarCom(achado -> custoDeGas.paraBnb(achado.ciclo().pernas().get(0).entra(), achado.oportunidade().lucro())
				.orElseThrow(() -> new IllegalStateException("fenda da cauda sem câmbio BNB para a raiz "
						+ java.util.HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()))));

		ObservatorioMempool mempool = new ObservatorioMempool(catalogo, tarifario::taxa, tarifario::espacamento,
				tarifario::solidlyStable,
				(overlay, crua) -> cauda.projetar(overlay, cabeca.alvo())
						.ifPresent(achado -> backrun.considerar(List.of(achado), crua)));
		Gatilhos gatilhos = new Gatilhos(rpcEnsaio, mempool::etapas, mempool::primeiraVisaoMs,
				arbitragem -> custoDeGas.paraBnb(arbitragem.token(), arbitragem.sobra()));
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
				return ethCall(rpcEnsaio, java.util.HexFormat.of().formatHex(token), dados, "0x" + Long.toHexString(bloco))
						.filter(r -> r.length() >= 66).map(r -> new java.math.BigInteger(r.substring(2, 66), 16));
			}
		});
		Simulador simulador = new Simulador(rpcEnsaio::chamar, mempool::aprenderDe);
		mempool.aoNaoDecodificar(simulador::talvez);
		mempool.aoBlocoComPrecos(desfechos::precos);
		ObservatorioArbitragem observatorio = new ObservatorioArbitragem(catalogo,
				(ObservatorioArbitragem.AoConfirmar) (arbitragem, altura, indice, gatilho) -> {
					mempool.confirmar(arbitragem, altura);
					gabarito.confirmar(arbitragem, altura);
					mercado.confirmar(arbitragem, altura, indice, gatilho);
					gatilhos.investigar(arbitragem, altura, indice, gatilho);
					desfechos.arbitragem(arbitragem, altura, indice);
					quentes.vistaEmArbitragem(arbitragem.pools().stream().map(java.util.HexFormat.of()::formatHex).toList());
				});

		Frente frente = new Frente(sessoes, Resources.inteiro("sincronizacao.lote"));
		// Os OBSERVADORES rodam na thread do cursor, e cada um na sua borda: exceção de um vai às `Falhas` com a
		// pilha e o bloco segue aplicado. CICATRIZ de 27/09 (22:18–22:46): uma exceção aqui matava o cursor calado
		// — 25 min parado na altura da partida, sem uma linha de log.
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
					Map<String, Object> resumo = ensaioResumo(cauda, backrun, disparo, gabarito, gatilhos, simulador, impostos,
							desfechos, lances);
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
		// O bloco inteiro (`NewBlock`) vira atalho: a frente pede só o recibo, sem a ida da header.
		// A/B: ATALHO_CABECA=0 desliga, para medir no mesmo servidor quanto o atalho ganha.
		boolean atalhoDaCabeca = !"0".equals(System.getenv("ATALHO_CABECA"));
		lotacao.aoReceberBloco((sessao, bloco) -> {
			if (atalhoDaCabeca) {
				frente.adiantar(sessao, bloco.cabecalho());
			}
			// Atualiza o parentHash para o próximo lance 48club (RawBid.parentHash = hash do bloco atual).
			disparo.aoAplicarCabecalho(bloco.cabecalho());
			// A margem de chegada dos swaps (quanto antes do bloco nós os vimos), para o N+1.
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
		// Thread PRÓPRIA: na `gravacao` o aquecimento esperava o banco, a lista de pools da cauda e as RPCs
		// de gás e nonce, e a conexão esfriava. MEDIDO em 24/09: disparo com mediana de 611 ms, contra
		// 193 ms com a conexão quente e 1,33 s com uma nova.
		ScheduledExecutorService aquecimento = Executors
				.newSingleThreadScheduledExecutor(tarefa -> {
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
			httpApi = new br.com.node.api.HttpApi(apiPorta, relatorio, mercado, arquivoDespachos, arquivoPagamentos, () -> {
					Map<String, Object> p = new LinkedHashMap<>();
					p.put("v2", catalogo.poolsV2());
					p.put("v3", catalogo.poolsV3());
					p.put("pares_conhecidos", catalogo.paresConhecidos());
					p.put("descartadas", catalogo.poolsDescartadas());
					return p;
				});
		} catch (java.io.IOException e) {
			throw new IllegalStateException("não foi possível iniciar HTTP API na porta " + apiPorta, e);
		}
		httpApi.iniciar();

		LOG.info(() -> "node " + chave.idHex().substring(0, 16) + "… no ar; relatório em " + relatorio.arquivo());

		Thread.currentThread().join();
	}

	// eth_call cru: o resultado em hex, ou vazio se o nó não respondeu ou reverteu.
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

	// `tickSpacing()` (0xd0c93a7c) da própria pool, para as V3 de factory desconhecida. int24 na palavra.
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

	// Calcula o bid total do rival a partir de dados on-chain: fetcha o bloco N+1 e procura a tx de
	// pagamento ao builder pelo mesmo remetente da tx de arb, em posições adjacentes. Com o BNB e o
	// gás usado da tx de arb: bid = gasPrice×0,9 + BNB/gasUsado. Vazio quando não encontra pagamento
	// ou o RPC falha.
	//
	// Busca pelo REMETENTE (mesmo `from` que a arb), não pelo destinatário: rivais usam builders
	// variados — só o 48club e o BlockRazor são conhecidos, mas há outros. Uma tx de BNB puro (sem
	// dados) do mesmo remetente, perto da arb, é o pagamento ao builder, independente do endereço.
	// Deltas cobrem bundle típico [pag, arb] e [arb, pag] e casos com 1 tx de separação.
	private static java.util.Optional<BigInteger> bidRealDoRival(
			br.com.node.ensaio.Desfechos.Desfecho desfecho,
			RpcDoEnsaio rpc) {

		long alturaN1 = desfecho.envio().altura() + 1;
		int posArb = desfecho.posicao();

		com.google.gson.JsonObject bloco = rpc.bloco(alturaN1).orElse(null);
		if (bloco == null || !bloco.has("transactions")) return java.util.Optional.empty();
		com.google.gson.JsonArray txs = bloco.getAsJsonArray("transactions");
		if (posArb < 0 || posArb >= txs.size()) return java.util.Optional.empty();

		com.google.gson.JsonObject txArb = txs.get(posArb).getAsJsonObject();

		BigInteger bnbPagamento = br.com.node.ensaio.PagamentosDeBloco.pagamentoAoBuilder(bloco, posArb,
				br.com.node.ensaio.PagamentosDeBloco.enderecosPadrao()).orElse(BigInteger.ZERO);

		if (bnbPagamento.signum() <= 0) return java.util.Optional.empty();

		BigInteger bnbFinal = bnbPagamento;
		String hashArb = txArb.get("hash").getAsString();
		return rpc.recibo(hashArb)
				.filter(r -> r.sucesso())
				// O total que o rival pagou ao builder, em BNB. Não se converte para "por gás" aqui: quem precisa do
				// total (o aprendiz de lances) multiplica de volta por um gás que não é o dele — o erro antigo.
				.map(r -> r.sucesso() ? bnbFinal : null);
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

	// A/B do disparo do garimpo (25/09): GARIMPO_POR_TX=1 reproduz o jeito antigo (na 1ª tx do bloco),
	// para medir no mesmo servidor quanto o disparo por bloco inteiro ganhou. Sem a variável, por bloco.
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
			br.com.node.ensaio.Lances lances) {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("garimpo_disparado", GARIMPO_POR_TX ? "por_tx (antigo)" : "por_bloco");
		resumo.put("falhas", br.com.node.util.Falhas.resumo());
		resumo.put("impostos_assumidos", impostos.assumidos());
		resumo.put("desfechos", desfechos.resumo());
		resumo.put("lances", lances.resumo());
		resumo.put("cauda", cauda.resumo());
		resumo.put("backrun", backrun.resumo());
		resumo.put("gabarito", gabarito.resumo());
		resumo.put("gatilhos", gatilhos.resumo());
		resumo.put("simulador", simulador.resumo());
		resumo.put("disparo", Map.of("enviadas", disparo.enviadas(), "recusadas", disparo.recusadas(),
"ultimo_envio", disparo.ultimoEnvio(),
				"motivos_do_contrato", disparo.motivos(), "gas_medido", disparo.gasMedido(), "autopsia",
				disparo.autopsias(), "recusas_do_relay", disparo.recusasDoRelay(), "milis", disparo.milis(),
				"executor_versao", disparo.versaoDoExecutor()));

		return resumo;
	}

	private static List<NoRemoto> sementes(PeersRepository peers) {

		List<NoRemoto> todas = new ArrayList<>(Sementes.carregar());

		todas.addAll(peers.carregar());

		return todas;
	}
}
