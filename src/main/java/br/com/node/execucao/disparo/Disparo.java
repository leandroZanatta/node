package br.com.node.execucao.disparo;
import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.execucao.bundle.MontadorDeBundle;
import br.com.node.execucao.bundle.VereditorDoContrato;
import br.com.node.execucao.bundle.MedidorDeGas;
import br.com.node.execucao.bundle.Salto;
import br.com.node.execucao.bundle.RotaDoModelo;
import br.com.node.execucao.bundle.AbiDoExecutor;
import br.com.node.execucao.bundle.CalculadorDeLance;


import java.io.Closeable;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import br.com.node.execucao.bundle.VereditorDeRelay;
import br.com.node.execucao.disparo.AnalisadorDeTransacao;
import br.com.node.transformacao.catalogo.Achado;
import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Impostos;
import br.com.node.transformacao.catalogo.Quarentena;
import br.com.node.transformacao.catalogo.Tarifario;
import br.com.node.util.Falhas;
import br.com.node.vo.Cabecalho;

public final class Disparo implements Closeable {

	private static final Logger LOG = Logger.getLogger(Disparo.class.getName());

	private static final String CONTRATO = Optional.ofNullable(System.getenv("EXECUTOR")).map(String::trim).map(e -> {
		if (!e.matches("(0x)?[0-9a-fA-F]{40}")) {
			throw new IllegalStateException("EXECUTOR ilegível: " + e);
		}
		return e.replaceFirst("^0x", "").toLowerCase(java.util.Locale.ROOT);
	}).orElse("b0599744c5858f9082c4ca9d92fc314476307496");

	public static byte[] executor() {
		return HexFormat.of().parseHex(CONTRATO);
	}

	private static final Path KILL = Path.of("PARAR");

	private static final long GAS_LIMIT_MARGEM_NUM = 6L;
	private static final long GAS_LIMIT_MARGEM_DEN = 5L;

	private static final BigInteger TETO_GAS_TX = BigInteger.valueOf(2_000_000_000_000_000L);

	private static final String WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";

	private volatile Lances lances;
	private volatile java.util.function.Consumer<Desfechos.Envio> aoEnviar = envio -> {
	};
	private volatile java.util.function.BiConsumer<String, Map<String, Object>> aoRecusarTentativa = (hash, dados) -> {
	};
	private volatile java.util.function.BiConsumer<String, String> aoVeredito = (hash, veredito) -> {
	};
	private volatile java.util.function.LongFunction<Optional<br.com.node.baixa.sync.Cabeca.Momentos>> momentos = altura -> Optional
			.empty();
	private volatile Cabecalho cabecalhoAtual;

	private final java.util.concurrent.atomic.AtomicLong sequenciaDeDrops = new java.util.concurrent.atomic.AtomicLong();

	private final RpcDoEnsaio rpc;
	private final RelayPrivado relay;
	private final Catalogo catalogo;
	private final Tarifario tarifario;
	private final CustoDeGas custoDeGas;
	private final AnalisadorDeTransacao laudos;
	private final Quarentena quarentena;

	private final MontadorDeBundle montador;
	private final VereditorDoContrato vereditor;
	private final MetricasDoDisparo metricas = new MetricasDoDisparo();
	private final MedidorDeGas medidor;

	private final int versaoDoExecutor;

	private BigInteger gastoNoDia = BigInteger.ZERO;

	public Disparo(RpcDoEnsaio rpc, RelayPrivado relay, Catalogo catalogo, Tarifario tarifario, CustoDeGas custoDeGas,
			Impostos impostos, AnalisadorDeTransacao laudos, Quarentena quarentena) {

		this.rpc = rpc;
		this.relay = relay;
		this.catalogo = catalogo;
		this.tarifario = tarifario;
		this.custoDeGas = custoDeGas;
		this.laudos = laudos;
		this.quarentena = quarentena;
		this.montador = new MontadorDeBundle(CONTRATO);
		this.vereditor = new VereditorDoContrato(rpc, quarentena, impostos);
		this.medidor = new MedidorDeGas(rpc);
		this.medidor.aoCalibrar(custoDeGas::fatorDeGas);
		this.versaoDoExecutor = lerVersao();

		LOG.info(() -> "disparo: executor versão " + versaoDoExecutor
				+ (versaoDoExecutor >= 2 ? " — pernas de gerente (V4, Infinity CL) LIGADAS"
						: " — antigo: pernas de gerente DESLIGADAS até o deploy da versão 2 (EXECUTOR=…)"));
		LOG.info(() -> "disparo: "
				+ (!montador.temChave() ? "SEM CHAVE (inerte)"
						: "operador 0x" + montador.endereco()
								+ (montador.armado ? " ARMADO — envia de verdade" : " desarmado (dry-run)"))
				+ " · executor 0x" + CONTRATO);
	}

	public void lancesDe(Lances lances) {
		this.lances = lances;
	}

	public void aoEnviar(java.util.function.Consumer<Desfechos.Envio> ouvinte) {
		this.aoEnviar = ouvinte;
	}

	public void aoRecusarTentativa(java.util.function.BiConsumer<String, Map<String, Object>> ouvinte) {
		this.aoRecusarTentativa = ouvinte;
	}

	public void aoVeredito(java.util.function.BiConsumer<String, String> ouvinte) {
		this.aoVeredito = ouvinte;
	}

	public void medirCom(
			java.util.function.LongFunction<Optional<br.com.node.baixa.sync.Cabeca.Momentos>> momentos) {
		this.momentos = momentos;
	}

	public void aoAplicarCabecalho(Cabecalho cabecalho) {
		this.cabecalhoAtual = cabecalho;
	}

	public void vereditoPor(RpcDoEnsaio rpc) {
		this.vereditor.rpcAlternativo(rpc);
	}

	public void disparar(Achado achado, byte[] cruaPendente) {

		if (!montador.temChave()) {
			LOG.warning("disparo: DROP sem chave do operador — keystore ausente ou OPERADOR_SENHA vazia");
			return;
		}

		if (Files.exists(KILL)) {
			LOG.warning("disparo: kill-switch (arquivo PARAR) presente — não envio");
			return;
		}

		long inicio = System.nanoTime();
		long entradaMs = System.currentTimeMillis();
		byte[] raiz = achado.ciclo().pernas().get(0).entra();

		if (!quarentena.podeEmprestar(achado.ciclo().pernas().get(0).pool())) {
			LOG.info(() -> "disparo: DROP — a pool do empréstimo chama um callback que o Executor não implementa");
			return;
		}

		String rotaDoLance = chaveDaRota(achado);
		Lances lancesAgora = lances;
		if (lancesAgora == null) {
			throw new IllegalStateException("disparo sem `Lances` ligado — o Main tem de chamar lancesDe");
		}

		BigInteger gasDaCauda = custoDeGas.estimarGasDasTravessias(achado.travessias(), achado.ciclo().pernas());
		Optional<BigInteger> piso = custoDeGas.piso(raiz, achado.oportunidade().entrada(), gasDaCauda);

		if (piso.isEmpty()) {
			LOG.info(() -> "disparo: DROP sem piso (raiz não-numerário)");
			return;
		}

		int fracaoMilha = lancesAgora.fracaoPara(rotaDoLance);
		BigInteger precoDoGas = custoDeGas.prioridade();
		BigInteger gasParaOLance = gasDaCauda.multiply(BigInteger.valueOf(6)).divide(BigInteger.valueOf(5));
		BigInteger lucroOportunidadeBnb = custoDeGas.paraBnb(raiz, achado.oportunidade().lucro())
				.orElseThrow(() -> semCambio(raiz));
		BigInteger necessarioBnb = lucroOportunidadeBnb.multiply(BigInteger.valueOf(fracaoMilha))
				.divide(BigInteger.valueOf(1000));
		BigInteger necessarioBnbSemLimiteDeSaldo = necessarioBnb;
		boolean solicitadoAoBuilder = relay.ativo() && montador.armado;
		boolean limitadoPorSaldo = false;

		BigInteger saldo = montador.saldoOperador();
		boolean saldoConhecido = saldo != null;
		if (saldo != null) {
			BigInteger gasMaximo = custoDeGas.maxTaxa()
					.multiply(gasDaCauda.multiply(BigInteger.valueOf(6)).divide(BigInteger.valueOf(5))
							.add(solicitadoAoBuilder && necessarioBnb.signum() > 0
									? BigInteger.valueOf(CustoDeGas.GAS_TX_PAGAMENTO_BUILDER)
									: BigInteger.ZERO));
			BigInteger disponivel = saldo.subtract(gasMaximo).max(BigInteger.ZERO);
			if (necessarioBnb.compareTo(disponivel) > 0) {
				limitadoPorSaldo = true;
				metricas.contarSaldoLimitado();
				necessarioBnb = disponivel;
			}
		}

		boolean limitadoPorPct = false;

		BigInteger necessarioRaiz = custoDeGas.deBnb(raiz, necessarioBnb).orElseThrow(() -> semCambio(raiz));
		BigInteger pisoBase = piso.get();
		BigInteger pisoComPagamento = solicitadoAoBuilder && necessarioBnb.signum() > 0
				? pisoBase.add(custoDeGas.custoDaTransferenciaBuilder(raiz))
				: pisoBase;
		BigInteger lucroParaLance = CalculadorDeLance.lucroParaLance(achado.oportunidade().lucro(), pisoComPagamento,
				CalculadorDeLance.MARGEM_DE_ESTADO_MILESIMOS);
		BigInteger excedenteLucro = CalculadorDeLance.maximoPagamento(lucroParaLance, pisoComPagamento);
		BigInteger tetoPagamentoRaiz = custoDeGas.deBnb(raiz, CalculadorDeLance.TETO_PAGAMENTO)
				.orElseThrow(() -> semCambio(raiz));
		boolean limitadoPorLucro = solicitadoAoBuilder && necessarioRaiz.compareTo(excedenteLucro) > 0;
		boolean limitadoPeloTeto = solicitadoAoBuilder && necessarioRaiz.compareTo(tetoPagamentoRaiz) > 0;
		BigInteger pagamentoRaiz = relay.ativo() && montador.armado
				? CalculadorDeLance.pagamentoAoBuilder(necessarioRaiz, lucroParaLance, pisoComPagamento,
						tetoPagamentoRaiz)
				: BigInteger.ZERO;
		if (limitadoPorLucro) {
			metricas.contarLucroLimitado();
		}
		BigInteger pagamentoBnb = pagamentoRaiz.signum() > 0
				? custoDeGas.paraBnb(raiz, pagamentoRaiz).orElseThrow(() -> semCambio(raiz))
				: BigInteger.ZERO;
		BigInteger pisoDoEnvio = pagamentoBnb.signum() > 0 ? pisoComPagamento : pisoBase;
		BigInteger ofertadoWeiPorGas = precoDoGas;
		BigInteger chao = pagamentoBnb.signum() > 0
				? pisoDoEnvio.add(pagamentoRaiz).subtract(BigInteger.TEN).max(pisoDoEnvio)
				: pisoDoEnvio;

		Optional<RotaDoModelo.Chamada> chamada = RotaDoModelo.traduzir(achado, catalogo, tarifario, chao);

		if (chamada.isEmpty()) {
			LOG.info(() -> "disparo: DROP sem tradução — rota " + achado.ciclo().pernas().size()
					+ " pernas não vira chamada (par/taxa/sentido/ponto-base)");
			return;
		}

		List<Salto> rota = chamada.get().rota();

		boolean semLoan = versaoDoExecutor >= 2 && HexFormat.of().formatHex(raiz).equalsIgnoreCase(WBNB)
				&& montador.saldoExecutorWBNB().compareTo(chamada.get().montante()) >= 0;

		if (!semLoan && versaoDoExecutor < 3 && (rota.get(0).v3() || rota.get(0).deGerente())) {
			LOG.info(() -> "disparo: DROP — ponta " + (rota.get(0).deGerente() ? "de gerente" : "V3")
					+ " e o Executor publicado só empresta de V2 (versão " + versaoDoExecutor + ")");
			return;
		}

		if (versaoDoExecutor < 2 && rota.stream().anyMatch(Salto::deGerente)) {
			LOG.info(() -> "disparo: DROP — rota por gerente (V4/Infinity) e o Executor publicado é o antigo");
			return;
		}

		byte[] calldata = semLoan
				? AbiDoExecutor.executarV2(rota, chamada.get().montante(), chamada.get().lucroMinimo())
				: versaoDoExecutor >= 2
						? AbiDoExecutor.executarComEmprestimoV2(rota, chamada.get().montante(),
								chamada.get().lucroMinimo())
						: AbiDoExecutor.executarComEmprestimo(rota, chamada.get().montante(),
								chamada.get().lucroMinimo());

		BigInteger gasEstimado = custoDeGas.estimarGas(rota, calldata, semLoan);
		long gasFinal = comFolgaDeLimite(gasEstimado);

		RotaDoModelo.Chamada chamadaFinal = chamada.get();

		if (!montador.armado) {
			LOG.info(() -> "disparo (dry-run): MANDARIA altura " + achado.altura() + " · rota "
					+ achado.ciclo().pernas().size() + " pernas · gás " + gasFinal / 1_000 + "k"
					+ (semLoan ? " · SEM LOAN (saldo executor)" : ""));
			autopsiarAsync(achado, chamadaFinal, calldata, "dry_run", cruaPendente, null, null, null);
			return;
		}

		BigInteger maxTaxa = custoDeGas.maxTaxa();
		BigInteger custoMaximo = maxTaxa.multiply(BigInteger.valueOf(gasFinal));

		if (custoMaximo.compareTo(TETO_GAS_TX) > 0) {
			registrarDropSemEnvio(achado, rotaDoLance, fracaoMilha, pisoDoEnvio, chamadaFinal, rota,
					"gas_acima_do_teto_por_transacao",
					Map.of("custo_maximo_gas_bnb_wei", custoMaximo.toString(), "teto_gas_tx_bnb_wei",
							TETO_GAS_TX.toString(), "gas_estimado", gasEstimado.toString(), "gas_limite_assinado",
							gasFinal));
			LOG.info(() -> "disparo: gás caro (" + bnb(custoMaximo) + " BNB > teto por tx) — pulo esta");
			return;
		}

		long alturaDoProximoBloco = achado.altura() + 1;
		Cabecalho cab = cabecalhoAtual;
		byte[] parentHash = (cab != null && cab.numero() == achado.altura()) ? cab.hash() : new byte[32];

		MontadorDeBundle.Pacote pacote = montador.empacotar(cruaPendente, alturaDoProximoBloco, parentHash, precoDoGas,
				maxTaxa, gasFinal, gasDaCauda, calldata, pagamentoBnb);

		boolean paga = pagamentoBnb.signum() > 0;
		boolean aceito;
		long milisAteAceiteRelay = 0;
		Map<String, Object> respostasRelay = new LinkedHashMap<>();

		String[] bundle48Ref = { null };
		if (relay.ativo()) {
			long antesDoRelay = (System.nanoTime() - inicio) / 1_000_000;
			RelayPrivado.Envio envio = relay.enviar(pacote.bid(), pacote.lista());
			aceito = envio.aceito();
			milisAteAceiteRelay = antesDoRelay + envio.latenciaAtePrimeiraAceitacaoMs();

			envio.porBuilder().forEach((nome, resposta) -> {
				respostasRelay.put(nome,
						detalheBuilder(resposta, pacote.nonceOperadorUsado(), paga, cruaPendente != null));
				if ("48club".equals(nome) && resposta.bundle() != null && !resposta.bundle().isBlank()) {
					bundle48Ref[0] = resposta.bundle();
				}
				if (!resposta.aceito()) {
					LOG.info(() -> "disparo: relay " + nome + " recusou (" + String.valueOf(resposta.motivo())
							+ ") · altura " + achado.altura() + " · rota " + rotaDoLance);
					metricas.registrarRecusaDoRelay(nome, resposta.motivo());
					if (VereditorDoContrato.naoSeMove(resposta.motivo())) {
						vereditor.suspeitarDaRota(rota, resposta.motivo());
					}
					String motivoRelay = VereditorDeRelay.motivoDoVeredito(resposta.motivo()).trim();
					if (!motivoRelay.isBlank()) {
						String razao = "relay " + nome + " recusou (" + motivoRelay + ")";
						if (VereditorDeRelay.proibicao(motivoRelay)) {
							quarentena.condenar(HexFormat.of().parseHex(chamadaFinal.rota().get(0).pool()),
									VereditorDeRelay.PENA_DE_PROIBICAO, razao);
						} else if (VereditorDeRelay.overflow(motivoRelay)) {
							chamadaFinal.rota().stream().filter(s -> !s.v3() && !s.deGerente()).forEach(s -> quarentena
									.condenar(HexFormat.of().parseHex(s.pool()), VereditorDeRelay.PENA_DE_ESTADO, razao));
						}
					}
				}
			});

			if (paga) {
				metricas.registrarPagamento(pagamentoBnb);
			}
		} else {
			somarGasto(custoMaximo);
			aceito = rpc.enviar(HexFormat.of().formatHex(pacote.arbRaw())).isPresent();
			respostasRelay.put("rpc_publico", Map.of("aceito", aceito));
		}

		long milis = (System.nanoTime() - inicio) / 1_000_000;
		String caminho = cruaPendente == null ? "cauda" : "n+1";
		long tempoAteConfirmacao = aceito && relay.ativo() ? milisAteAceiteRelay : milis;

		Map<String, Object> tempo = anotarLinhaDoTempo(achado.altura(), entradaMs, tempoAteConfirmacao, caminho);

		if (aceito) {
			metricas.registrarEnvio(caminho, milis, pacote.arbHash());
			medidor.anotar(gasEstimado, pacote.arbHash());
		} else {
			metricas.registrarRecusa(caminho, milis);
		}

		Map<String, Object> detalhes = detalhesEnvio(achado, chamadaFinal, rota, pisoDoEnvio, fracaoMilha,
				ofertadoWeiPorGas, necessarioBnbSemLimiteDeSaldo, necessarioBnb, saldoConhecido, limitadoPorSaldo,
				limitadoPorPct, necessarioRaiz, pagamentoRaiz, pagamentoBnb, precoDoGas, gasDaCauda, gasEstimado,
				gasFinal, maxTaxa, relay.builders().stream().anyMatch(b -> "48club".equals(b.nome())), caminho,
				tempoAteConfirmacao, milis, tempo, respostasRelay, aceito, solicitadoAoBuilder, limitadoPorLucro,
				limitadoPeloTeto, semLoan, calldata, versaoDoExecutor);

		if (aceito) {
			java.util.Set<String> poolsDaRota = new java.util.HashSet<>();
			achado.ciclo().pernas().forEach(p -> poolsDaRota.add(HexFormat.of().formatHex(p.pool())));
			aoEnviar.accept(new Desfechos.Envio(pacote.arbHash(), achado.altura(), poolsDaRota,
					entradaMs + tempoAteConfirmacao, caminho, rotaDoLance, fracaoMilha, ofertadoWeiPorGas,
					lucroOportunidadeBnb, gasParaOLance, detalhes));
		} else {
			detalhes.put("lucro_previsto_bnb_wei", lucroOportunidadeBnb.toString());
			aoRecusarTentativa.accept(pacote.arbHash(), detalhes);
		}

		autopsiarAsync(achado, chamadaFinal, calldata, aceito ? "enviada" : "recusada_relay", cruaPendente,
				aceito ? pacote.arbHash() : null, pacote.arbHash(), bundle48Ref[0]);
	}

	public void examinarSemEnviar(Achado achado) {
		byte[] raiz = achado.ciclo().pernas().get(0).entra();
		BigInteger gas = custoDeGas.estimarGasDasTravessias(achado.travessias(), achado.ciclo().pernas());
		Optional<RotaDoModelo.Chamada> chamada = custoDeGas.piso(raiz, achado.oportunidade().entrada(), gas)
				.flatMap(p -> RotaDoModelo.traduzir(achado, catalogo, tarifario, p));
		if (chamada.isEmpty()) {
			return;
		}
		String de = montador.endereco() != null ? montador.endereco() : "0000000000000000000000000000000000000001";
		vereditor.submeterAsync(() -> {
			try {
				laudos.examinar(de, CONTRATO, achado, chamada.get(), "sombra", "nao_pedido");
			} catch (RuntimeException e) {
				Falhas.anotar("autópsia sombra", e);
			}
		});
	}

	public void atualizarNonce() {
		montador.atualizarNonce(rpc);
	}

	public int versaoDoExecutor() {
		return versaoDoExecutor;
	}

	public synchronized long enviadas() {
		return metricas.enviadas();
	}

	public synchronized long recusadas() {
		return metricas.recusadas();
	}

	public Map<String, Long> recusasDoRelay() {
		return metricas.recusasDoRelay();
	}

	public Map<String, Object> milis() {
		return metricas.resumo(montador.saldoOperador(), montador.saldoExecutorWBNB(),
				vereditor.vereditoSemEstadoDoBloco());
	}

	public Map<String, Object> autopsias() {
		return laudos.resumo();
	}

	public synchronized String ultimoEnvio() {
		return metricas.ultimoEnvio();
	}

	public Map<String, Long> motivos() {
		return vereditor.motivos();
	}

	public Map<String, Object> gasMedido() {
		return medidor.resumo();
	}

	@Override
	public void close() {
		vereditor.close();
		laudos.close();
		medidor.close();
		LOG.info(() -> "disparo encerrado: " + metricas.enviadas() + " enviadas, " + metricas.recusadas()
				+ " recusadas");
	}

	private void autopsiarAsync(Achado achado, RotaDoModelo.Chamada chamada, byte[] calldata, String desfecho,
			byte[] cruaPendente, String hashEnviado, String hashTentativa, String bundle48) {

		String dadosHex = HexFormat.of().formatHex(calldata);
		List<Salto> rota = chamada.rota();
		String de = montador.endereco();

		vereditor.submeterAsync(() -> {
			if (cruaPendente != null) {
				laudos.concluir(hashTentativa, Map.<String, Object>of("status", "nao_aplicavel_sem_transacao_alvo"));
				return;
			}

			String veredito = vereditor.anotar(de, CONTRATO, rota, dadosHex, achado.altura());

			if (hashEnviado != null) {
				aoVeredito.accept(hashEnviado, veredito);
			}

			Map<String, Object> extras = bundle48 != null ? new LinkedHashMap<>(consultar48Club(bundle48)) : null;
			if (VereditorDeRelay.transferenciaFalhou(VereditorDeRelay.motivoDoVeredito(veredito))
					|| VereditorDoContrato.falhaNaChamada(veredito)) {
				if (extras == null) {
					extras = new LinkedHashMap<>();
				}
				extras.put("trace_executor",
						vereditor.rastrearChamada(de, CONTRATO, dadosHex, achado.altura())
								.map(Disparo::resumirTraceFalha)
								.orElse(Map.of("status", "debug_traceCall indisponivel no RPC")));
			}

			try {
				laudos.examinar(de, CONTRATO, achado, chamada, desfecho, veredito, hashTentativa, extras);
			} catch (RuntimeException e) {
				Falhas.anotar("autópsia do envio", e);
				laudos.concluir(hashTentativa,
						Map.<String, Object>of("status", "falha_na_autopsia", "erro", e.getClass().getSimpleName()));
			}
		});
	}

	private static Map<String, Object> resumirTraceFalha(com.google.gson.JsonObject raiz) {
		List<Map<String, Object>> quadros = new java.util.ArrayList<>();
		coletarQuadrosDeFalha(raiz, quadros, 0);
		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("status", "trace_obtido");
		resumo.put("quadros_transfer_ou_falha", quadros);
		return resumo;
	}

	private static void coletarQuadrosDeFalha(com.google.gson.JsonObject quadro, List<Map<String, Object>> encontrados,
			int profundidade) {
		if (profundidade > 64) {
			return;
		}
		String input = quadro.has("input") && quadro.get("input").isJsonPrimitive() ? quadro.get("input").getAsString()
				: "";
		String erro = quadro.has("error") && quadro.get("error").isJsonPrimitive() ? quadro.get("error").getAsString()
				: "";
		boolean chamadaTransfer = input.length() >= 10 && input.regionMatches(true, 2, "a9059cbb", 0, 8);
		if (chamadaTransfer || !erro.isBlank()) {
			Map<String, Object> detalhe = new LinkedHashMap<>();
			for (String campo : List.of("type", "from", "to", "input", "output", "error", "gasUsed")) {
				if (quadro.has(campo) && !quadro.get(campo).isJsonNull()) {
					detalhe.put(campo, quadro.get(campo).getAsString());
				}
			}
			encontrados.add(detalhe);
		}
		if (quadro.has("calls") && quadro.get("calls").isJsonArray()) {
			for (com.google.gson.JsonElement filho : quadro.getAsJsonArray("calls")) {
				if (filho.isJsonObject()) {
					coletarQuadrosDeFalha(filho.getAsJsonObject(), encontrados, profundidade + 1);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> consultar48Club(String bundleHash) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://explore.48.club/v2/bundle?hash=" + bundleHash))
					.timeout(Duration.ofSeconds(8)).GET().build();
			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			Map<String, Object> resultado = new com.google.gson.Gson().fromJson(resp.body(), Map.class);
			return Map.of("48club_status", resultado != null ? resultado : Map.of("bruto", resp.body()));
		} catch (Exception e) {
			return Map.of("48club_status", Map.of("erro", e.getClass().getSimpleName() + ": " + e.getMessage()));
		}
	}

	private void registrarDropSemEnvio(Achado achado, String rotaId, int fracaoMilha, BigInteger pisoDoEnvio,
			RotaDoModelo.Chamada chamada, List<Salto> rota, String motivo, Map<String, Object> detalhes) {
		Map<String, Object> tentativa = new LinkedHashMap<>(detalhes);
		tentativa.put("altura", achado.altura());
		tentativa.put("caminho", "cauda_ou_n+1");
		tentativa.put("rota_id", rotaId);
		tentativa.put("pools",
				achado.ciclo().pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).sorted().toList());
		tentativa.put("raiz_ativo", HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()));
		tentativa.put("montante_entrada_wei", chamada.montante().toString());
		tentativa.put("lucro_previsto_ativo_wei", achado.oportunidade().lucro().toString());
		tentativa.put("lucro_minimo_ativo_wei", chamada.lucroMinimo().toString());
		tentativa.put("piso_ativo_wei", pisoDoEnvio.toString());
		tentativa.put("lance_fracao_milha", fracaoMilha);
		tentativa.put("motivo_nao_enviado", motivo);
		tentativa.put("pernas",
				rota.stream()
						.map(s -> Map.of("pool", s.pool(), "tipo", s.v3() ? "V3" : s.deGerente() ? "gerente" : "V2",
								"token_entrada", s.entra(), "token_saida", s.sai(), "taxa_bps", s.taxaBps()))
						.toList());
		tentativa.put("relay_aceitou", null);
		tentativa.put("tempo", Map.of("marcos_disponiveis", false));
		aoRecusarTentativa.accept("drop:" + achado.altura() + ":" + rotaId + ":" + sequenciaDeDrops.incrementAndGet(),
				tentativa);
	}

	private Map<String, Object> anotarLinhaDoTempo(long altura, long entradaMs, long relayMs, String caminho) {
		Optional<br.com.node.baixa.sync.Cabeca.Momentos> m = momentos.apply(altura);
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("inicio_disparo_epoch_ms", entradaMs);
		linha.put("confirmacao_relay_epoch_ms", entradaMs + relayMs);
		linha.put("inicio_disparo_ate_confirmacao_ms", relayMs);
		if (m.isEmpty()) {
			linha.put("marcos_disponiveis", false);
			return linha;
		}
		long bloco = m.get().blocoMs();
		long anuncio = m.get().anuncioMs();
		long aplicado = m.get().aplicadoMs();
		long total = entradaMs + relayMs - bloco;
		linha.put("marcos_disponiveis", true);
		linha.put("bloco_ms", bloco);
		linha.put("anuncio_ms", anuncio > 0 ? anuncio : null);
		linha.put("aplicado_ms", aplicado > 0 ? aplicado : null);
		linha.put("bloco_ate_anuncio_ms", anuncio > 0 ? anuncio - bloco : null);
		linha.put("anuncio_ate_aplicado_ms", anuncio > 0 ? aplicado - anuncio : null);
		linha.put("aplicado_ate_decisao_ms", aplicado > 0 ? entradaMs - aplicado : null);
		linha.put("bloco_ate_resposta_relay_ms", total);
		metricas.etapa(caminho + ".1_bloco_ate_anuncio", anuncio > 0 ? anuncio - bloco : -1);
		metricas.etapa(caminho + ".2_anuncio_ate_aplicado", anuncio > 0 ? aplicado - anuncio : -1);
		metricas.etapa(caminho + ".3_aplicado_ate_decisao", aplicado > 0 ? entradaMs - aplicado : -1);
		metricas.etapa(caminho + ".4_decisao_ate_relay", relayMs);
		metricas.etapa(caminho + ".5_total_bloco_ate_relay", total);
		return linha;
	}

	private static Map<String, Object> detalheBuilder(RelayPrivado.Resposta resposta, long nonceOperador, boolean paga,
			boolean temAlvo) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("aceito", resposta.aceito());
		d.put("motivo", resposta.motivo());
		d.put("bundle", resposta.bundle());
		d.put("latencia_resposta_ms", resposta.latenciaMs());
		d.put("status_http", resposta.statusHttp());
		d.put("codigo_erro_json_rpc", resposta.codigoErro());
		d.put("mensagem_erro_completa", resposta.mensagemErro());
		d.put("dados_erro", resposta.dadosErro());
		d.put("resposta_bruta", resposta.respostaBruta());
		d.put("resposta_truncada", resposta.respostaTruncada());
		d.put("hash_transacao_falha", resposta.hashTransacaoFalha());
		d.put("indice_transacao_falha", resposta.indiceTransacaoFalha());
		d.put("seletor_revert", resposta.seletorRevert());
		d.put("texto_revert", resposta.textoRevert());
		d.put("nonce_operador_usado", nonceOperador);
		d.put("nonce_pagamento_usado", paga ? nonceOperador + 1 : null);
		d.put("transacoes_bundle", transacoesDoBundle(resposta.hashesTransacoes(), temAlvo, paga, nonceOperador,
				resposta.indiceTransacaoFalha()));
		return d;
	}

	private static List<Map<String, Object>> transacoesDoBundle(List<String> hashes, boolean temAlvo, boolean paga,
			long nonceOperador, Integer indiceFalha) {
		List<String> papeis = new java.util.ArrayList<>();
		if (temAlvo)
			papeis.add("transacao_alvo_mempool");
		papeis.add("arbitragem_operador");
		if (paga)
			papeis.add("pagamento_builder");
		List<Map<String, Object>> resultado = new java.util.ArrayList<>();
		for (int i = 0; i < hashes.size(); i++) {
			Map<String, Object> tx = new LinkedHashMap<>();
			tx.put("indice", i);
			tx.put("papel", i < papeis.size() ? papeis.get(i) : "desconhecido");
			tx.put("hash", hashes.get(i));
			Long nonce = null;
			if ("arbitragem_operador".equals(tx.get("papel"))) {
				nonce = nonceOperador;
			} else if ("pagamento_builder".equals(tx.get("papel"))) {
				nonce = nonceOperador + 1;
			}
			tx.put("nonce", nonce);
			tx.put("indicada_como_falha_pelo_relay", indiceFalha != null && indiceFalha == i);
			resultado.add(tx);
		}
		return resultado;
	}

	private static Map<String, Object> detalhesEnvio(Achado achado, RotaDoModelo.Chamada chamada, List<Salto> rota,
			BigInteger piso, int fracaoMilha, BigInteger ofertado, BigInteger necessarioBnbSemLimiteDeSaldo,
			BigInteger necessarioBnb, boolean saldoConhecido, boolean limitadoPorSaldo, boolean limitadoPorPct,
			BigInteger necessarioRaiz, BigInteger pagamentoRaiz, BigInteger pagamentoBnb, BigInteger precoDoGas,
			BigInteger gasDaCauda, BigInteger gasEstimado, long gasFinal, BigInteger maxTaxa, boolean enviadoAo48Club,
			String caminho, long tempoAteConfirmacao, long milisTotal, Map<String, Object> tempo,
			Map<String, Object> respostasRelay, boolean aceito, boolean solicitadoAoBuilder, boolean limitadoPorLucro,
			boolean limitadoPeloTeto, boolean semLoan, byte[] calldata, int versaoExecutor) {

		Map<String, Object> detalhes = new LinkedHashMap<>();
		detalhes.put("altura", achado.altura());
		detalhes.put("caminho", caminho);
		detalhes.put("executor", "0x" + CONTRATO);
		detalhes.put("versao_executor", versaoExecutor);
		detalhes.put("seletor_executor", "0x" + HexFormat.of().formatHex(calldata, 0, 4));
		detalhes.put("sem_loan", semLoan);
		detalhes.put("rota_id", chaveDaRota(achado));
		detalhes.put("pools",
				achado.ciclo().pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).sorted().toList());
		boolean envioPrivado = !respostasRelay.containsKey("rpc_publico");
		detalhes.put("submissao_aceita", aceito);
		detalhes.put("relay_aceitou", envioPrivado ? aceito : null);
		detalhes.put("raiz_ativo", HexFormat.of().formatHex(achado.ciclo().pernas().get(0).entra()));
		detalhes.put("montante_entrada_wei", chamada.montante().toString());
		detalhes.put("saida_prevista_wei", achado.oportunidade().saida().toString());
		detalhes.put("lucro_previsto_ativo_wei", achado.oportunidade().lucro().toString());
		detalhes.put("lucro_minimo_ativo_wei", chamada.lucroMinimo().toString());
		detalhes.put("piso_ativo_wei", piso.toString());
		detalhes.put("pernas", rota.stream().map(s -> {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("pool", s.pool());
			p.put("tipo", s.v3() ? "V3" : s.deGerente() ? "gerente" : "V2");
			p.put("token_entrada", s.entra());
			p.put("token_saida", s.sai());
			p.put("taxa_bps", s.taxaBps());
			return p;
		}).toList());

		Map<String, Object> lance = new LinkedHashMap<>();
		lance.put("fracao_milha", fracaoMilha);
		lance.put("pagamento_direto_builder_bnb_wei", pagamentoBnb.toString());
		lance.put("pagamento_direto_builder_ativo_raiz_wei", pagamentoRaiz.toString());
		lance.put("necessario_ativo_raiz_wei", necessarioRaiz.toString());
		lance.put("necessario_bnb_sem_limite_saldo_wei", necessarioBnbSemLimiteDeSaldo.toString());
		lance.put("necessario_bnb_apos_limite_saldo_wei", necessarioBnb.toString());
		lance.put("solicitado_ao_builder", solicitadoAoBuilder);
		lance.put("saldo_operador_conhecido", saldoConhecido);
		lance.put("limitado_por_saldo", limitadoPorSaldo);
		lance.put("limitado_por_pct", limitadoPorPct);
		lance.put("limitado_por_lucro", limitadoPorLucro);
		lance.put("limitado_pelo_teto_builder", limitadoPeloTeto);
		detalhes.put("lance", lance);

		Map<String, Object> gas = new LinkedHashMap<>();
		gas.put("estimado", gasEstimado.toString());
		gas.put("limite_assinado", gasFinal);
		gas.put("max_fee_wei_por_gas", maxTaxa.toString());
		gas.put("priority_fee_wei_por_gas", precoDoGas.toString());
		detalhes.put("gas", gas);

		Map<String, Object> transporte = new LinkedHashMap<>();
		transporte.put("tipo", respostasRelay.containsKey("rpc_publico") ? "rpc_publico" : "bundle_privado");
		transporte.put("caminho", caminho);
		transporte.put("quantidade_tx_bundle", respostasRelay.containsKey("rpc_publico") ? 1
				: (caminho.equals("n+1") ? 2 : 1) + (pagamentoBnb.signum() > 0 ? 1 : 0));
		transporte.put("tempo_ate_primeira_aceitacao_ms", tempoAteConfirmacao);
		transporte.put("tempo_ate_todas_as_respostas_ms", milisTotal);
		transporte.put("respostas_por_builder", respostasRelay);
		detalhes.put("transporte", transporte);
		detalhes.put("tempo", tempo);
		return detalhes;
	}

	private static String chaveDaRota(Achado achado) {
		return String.join("-",
				achado.ciclo().pernas().stream().map(p -> HexFormat.of().formatHex(p.pool())).sorted().toList());
	}

	private static long comFolgaDeLimite(BigInteger gas) {
		return gas.multiply(BigInteger.valueOf(GAS_LIMIT_MARGEM_NUM)).divide(BigInteger.valueOf(GAS_LIMIT_MARGEM_DEN))
				.longValueExact();
	}

	private static IllegalStateException semCambio(byte[] raiz) {
		return new IllegalStateException(
				"sem câmbio BNB para a raiz " + HexFormat.of().formatHex(raiz) + " depois de o piso ter cotado");
	}

	private static String bnb(BigInteger wei) {
		return new java.math.BigDecimal(wei).divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)))
				.setScale(6, java.math.RoundingMode.DOWN).toPlainString();
	}

	private int lerVersao() {
		try {
			com.google.gson.JsonObject chamada = new com.google.gson.JsonObject();
			chamada.addProperty("to", "0x" + CONTRATO);
			chamada.addProperty("data", "0x" + HexFormat.of().formatHex(AbiDoExecutor.VERSAO));
			com.google.gson.JsonArray parametros = new com.google.gson.JsonArray();
			parametros.add(chamada);
			parametros.add("latest");
			return rpc.chamar("eth_call", parametros).map(com.google.gson.JsonParser::parseString)
					.map(com.google.gson.JsonElement::getAsJsonObject).filter(o -> o.has("result"))
					.map(o -> o.get("result").getAsString()).filter(r -> r.length() > 2)
					.map(r -> new BigInteger(r.substring(2), 16).intValue())
					.orElseThrow(() -> new IllegalStateException("versão do Executor 0x" + CONTRATO
							+ " não veio do RPC — não subo sem saber que contrato estou chamando"));
		} catch (RuntimeException e) {
			throw new IllegalStateException("versão do Executor 0x" + CONTRATO + " ilegível: " + e.getMessage(), e);
		}
	}

	private synchronized void somarGasto(BigInteger custo) {
		gastoNoDia = gastoNoDia.add(custo);
	}
}