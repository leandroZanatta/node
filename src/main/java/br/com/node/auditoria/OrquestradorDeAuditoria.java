package br.com.node.auditoria;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
import br.com.node.auditoria.mercado.AutopsiaDoMercado;
import br.com.node.auditoria.pagamentos.PagamentosDeBloco;
import br.com.node.auditoria.pagamentos.TransferenciasInternas;
import br.com.node.auditoria.pagamentos.VencedorDoLeilao;
import br.com.node.auditoria.registro.DiarioDeEnsaio;
import br.com.node.auditoria.relatorio.RelatorioExecucao;
import br.com.node.baixa.OrquestradorDeBaixa;
import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.execucao.OrquestradorDeExecucao;
import br.com.node.execucao.bundle.Lance48Club;
import br.com.node.execucao.disparo.Desfechos;
import br.com.node.execucao.disparo.Lances;
import br.com.node.transformacao.OrquestradorDeTransformacao;
import br.com.node.util.Falhas;

public final class OrquestradorDeAuditoria {

	private static final Logger LOG = Logger.getLogger(OrquestradorDeAuditoria.class.getName());

	private final OrquestradorDeTransformacao mercado;
	private final RpcDoEnsaio rpc;
	private final RpcDoEnsaio rpcTrace;
	private final Path arquivoVencedores;
	private final Path arquivoPagamentos;
	private final Lances lances;
	private final DiarioDeEnsaio diario;
	private final PagamentosDeBloco pagamentos;
	private final AutopsiaDoMercado autopsiaDoMercado;
	private final RelatorioExecucao relatorio;
	private final HttpApi httpApi;
	private final ExecutorService bidLookup;
	private final ScheduledExecutorService agenda;

	public OrquestradorDeAuditoria(OrquestradorDeBaixa baixa, OrquestradorDeTransformacao mercado,
			OrquestradorDeExecucao execucao, RpcDoEnsaio rpc) {

		this.mercado = mercado;
		this.rpc = rpc;
		this.rpcTrace = rpcDoTrace(rpc);
		this.arquivoPagamentos = Path.of(System.getenv().getOrDefault("PAGAMENTOS_LOG", "pagamentos.jsonl"));
		this.arquivoVencedores = Path.of(System.getenv().getOrDefault("VENCEDORES_LOG", "vencedores.jsonl"));
		this.lances = execucao.lances();

		String carimbo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path arquivoDoRelatorio = Path.of("target", "relatorio-execucao-" + carimbo + ".jsonl");

		this.diario = new DiarioDeEnsaio(Path.of("target", "ensaio.jsonl"));
		this.pagamentos = new PagamentosDeBloco(rpc, arquivoPagamentos, PagamentosDeBloco.enderecosPadrao());
		this.autopsiaDoMercado = new AutopsiaDoMercado(mercado.catalogo(), mercado.cotador(), mercado.tarifario(),
				execucao.custoDeGas(), execucao.gabarito(), Path.of("target", "mercado-" + carimbo + ".jsonl"));
		autopsiaDoMercado.versaoDoExecutor(execucao.versaoDoExecutor());
		autopsiaDoMercado.verificarCom(saldos());

		this.bidLookup = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "bid-lookup");
			t.setDaemon(true);
			return t;
		});

		execucao.aoConcluirAutopsia(diario::autopsia);
		execucao.aoEnviar(diario::envio);
		execucao.aoRecusarTentativa(diario::recusa);
		execucao.aoDecidir(this::aoDecidir);
		mercado.aoConfirmarArbitragem((arbitragem, altura, indice, gatilho) -> autopsiaDoMercado.confirmar(arbitragem, altura, indice, gatilho));
		mercado.antesDaTransacao((logs, altura) -> naBorda("mercado: antes", () -> autopsiaDoMercado.antes(logs, altura)));

		this.relatorio = new RelatorioExecucao(arquivoDoRelatorio, baixa::resumo, () -> mercado.resumoDoMercado(),
				() -> resumoDoEnsaio(execucao));

		Path arquivoDespachos = Path.of("target", "ensaio.jsonl");
		try {
			this.httpApi = new HttpApi(Integer.parseInt(System.getenv().getOrDefault("API_PORTA", "8080")), relatorio,
					autopsiaDoMercado, arquivoDespachos, arquivoPagamentos, arquivoVencedores,
					Path.of(System.getenv().getOrDefault("FENDAS_GRANDES_LOG", "fendas_grandes.jsonl")),
					() -> {
						Map<String, Object> p = new LinkedHashMap<>();
						p.put("v2", mercado.catalogo().poolsV2());
						p.put("v3", mercado.catalogo().poolsV3());
						p.put("pares_conhecidos", mercado.catalogo().paresConhecidos());
						p.put("descartadas", mercado.catalogo().poolsDescartadas());
						return p;
					}, mercado.quarentena());
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível iniciar HTTP API", e);
		}

		this.agenda = Executors.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "auditoria-agenda"));
	}

	public void iniciar() {
		agenda.scheduleWithFixedDelay(() -> relatorio.registrar("snapshot"), 0, 60, TimeUnit.SECONDS);
		httpApi.iniciar();
	}

	public void aoFecharBloco(long altura) {
		naBorda("pagamentos: bloco fechado", () -> pagamentos.aoFecharBloco(altura));
	}

	public Path arquivoDoRelatorio() {
		return relatorio.arquivo();
	}

	public void fechar() {
		relatorio.registrar("final");
		relatorio.close();
		autopsiaDoMercado.close();
		agenda.shutdownNow();
		pagamentos.close();
		diario.close();
		bidLookup.shutdownNow();
	}

	private void aoDecidir(Desfechos.Desfecho desfecho) {

		if (ganhouOutro(desfecho) && desfecho.posicao() != null) {
			bidLookup.submit(() -> registrarVencedor(desfecho));
		}
		boolean deveEnriquecer = (desfecho.classe().equals("pool_mexida_n1") || desfecho.classe().equals("rival_n1"))
				&& desfecho.folgaMs() != null && desfecho.folgaMs() > 0 && desfecho.posicao() != null;
		if (deveEnriquecer) {
			bidLookup.submit(() -> {
				Optional<BigInteger> bidOpt = bidRealDoRival(desfecho);
				synchronized (lances) {
					lances.aprender(desfecho, bidOpt.orElse(null));
				}
				diario.desfecho(desfecho, lances.fracaoPara(desfecho.envio().rota()), bidOpt.isPresent());
			});
		} else {
			synchronized (lances) {
				lances.aprender(desfecho);
			}
			diario.desfecho(desfecho, lances.fracaoPara(desfecho.envio().rota()));
		}
	}

	private Map<String, Object> resumoDoEnsaio(OrquestradorDeExecucao execucao) {

		Map<String, Object> resumo = new LinkedHashMap<>(execucao.resumo());
		resumo.putAll(mercado.resumoDoEnsaio());
		resumo.put("trace_rpc", Map.of("origem", rpcTrace == rpc ? "RPC de execucao padrao" : "RPC_TRACE",
				"hosts", rpcTrace.hosts()));
		mercado.varredor().ifPresent(v -> resumo.put("varredura_de_gerentes", v.resumo()));
		resumo.put("mercado", autopsiaDoMercado.resumo());
		return resumo;
	}

	private void registrarVencedor(Desfechos.Desfecho desfecho) {
		try {
			gravarVencedor(desfecho);
		} catch (RuntimeException e) {
			LOG.warning(() -> "vencedor do leilão não lido para " + desfecho.envio().hash() + ": " + e);
		}
	}

	private void gravarVencedor(Desfechos.Desfecho desfecho) {

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
		VencedorDoLeilao.gravar(arquivoVencedores, linha);
	}

	private Optional<BigInteger> bidRealDoRival(Desfechos.Desfecho desfecho) {

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
			return Optional.empty();

		String hashArb = txArb.get("hash").getAsString();
		return rpc.recibo(hashArb).filter(r -> r.sucesso()).map(r -> bnbPagamento);
	}

	private static boolean ganhouOutro(Desfechos.Desfecho desfecho) {
		return switch (desfecho.classe()) {
		case "rival_n1", "rival_n2", "pool_mexida_n1", "pool_mexida_n2" -> true;
		default -> false;
		};
	}

	private AutopsiaDoMercado.Saldos saldos() {
		return new AutopsiaDoMercado.Saldos() {
			@Override
			public Optional<BigInteger> bnb(byte[] conta, long bloco) {
				JsonArray parametros = new JsonArray();
				parametros.add("0x" + HexFormat.of().formatHex(conta));
				parametros.add("0x" + Long.toHexString(bloco));
				return rpc.chamar("eth_getBalance", parametros).map(JsonParser::parseString)
						.map(JsonElement::getAsJsonObject).filter(o -> o.has("result"))
						.map(o -> new BigInteger(o.get("result").getAsString().substring(2), 16));
			}

			@Override
			public Optional<BigInteger> token(byte[] token, byte[] conta, long bloco) {
				String dados = "70a08231" + "0".repeat(24) + HexFormat.of().formatHex(conta);
				return rpc.ethCall(HexFormat.of().formatHex(token), dados, "0x" + Long.toHexString(bloco))
						.filter(r -> r.length() >= 66).map(r -> new BigInteger(r.substring(2, 66), 16));
			}
		};
	}

	private static RpcDoEnsaio rpcDoTrace(RpcDoEnsaio padrao) {
		return Optional.ofNullable(System.getenv("RPC_TRACE")).map(String::trim).filter(u -> !u.isBlank())
				.map(urls -> java.util.Arrays.stream(urls.split(",")).map(String::trim).filter(u -> !u.isBlank())
						.toList())
				.filter(urls -> !urls.isEmpty()).map(RpcDoEnsaio::new).orElse(padrao);
	}

	private static void naBorda(String origem, Runnable observador) {
		try {
			observador.run();
		} catch (RuntimeException e) {
			Falhas.anotar(origem, e);
		}
	}
}
