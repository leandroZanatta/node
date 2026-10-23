package br.com.node.transformacao;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.baixa.p2p.Sessao;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.baixa.rpc.Rpc;
import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.transformacao.catalogo.AplicadorDeBloco;
import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Cotador;
import br.com.node.transformacao.catalogo.Eventos;
import br.com.node.transformacao.catalogo.EspacamentoPorChamada;
import br.com.node.transformacao.catalogo.Fabricas;
import br.com.node.transformacao.catalogo.Impostos;
import br.com.node.transformacao.catalogo.ObservatorioArbitragem;
import br.com.node.transformacao.catalogo.Quarentena;
import br.com.node.transformacao.catalogo.Quentes;
import br.com.node.transformacao.catalogo.Tarifario;
import br.com.node.transformacao.catalogo.Taxas;
import br.com.node.transformacao.catalogo.Ticks;
import br.com.node.transformacao.catalogo.VarredorDeGerentes;
import br.com.node.transformacao.mempool.ObservatorioMempool;
import br.com.node.transformacao.mempool.Simulador;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.transformacao.persistencia.ImpostosRepository;
import br.com.node.transformacao.persistencia.PoolsNoGerenteRepository;
import br.com.node.transformacao.persistencia.ProvasDeTaxaRepository;
import br.com.node.transformacao.sondagem.Sondagem;
import br.com.node.util.Falhas;
import br.com.node.util.Resources;
import br.com.node.vo.Log;
import br.com.node.vo.NewBlock;
import br.com.node.vo.NewPooledTransactionHashes;
import br.com.node.vo.Transactions;

public final class OrquestradorDeTransformacao {

	private static final Logger LOG = Logger.getLogger(OrquestradorDeTransformacao.class.getName());

	private static final int GRAVACAO_SEGUNDOS = Resources.inteiro("persistencia.taxas.intervalo.segundos");

	private final List<ObservatorioArbitragem.AoConfirmar> ouvintesDeConfirmacao = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<Map<String, Pool>, byte[]>> ouvintesDeProjecao = new CopyOnWriteArrayList<>();

	private final Catalogo catalogo;
	private final Impostos impostos;
	private final Taxas taxas;
	private final Quarentena quarentena;
	private final Quentes quentes;
	private final Tarifario tarifario;
	private final Cotador cotador;
	private final Ticks ticks;
	private final ObservatorioArbitragem observatorio;
	private final ObservatorioMempool mempool;
	private final Simulador simulador;
	private final Sondagem sondagem;
	private final AplicadorDeBloco aplicador;
	private final Optional<VarredorDeGerentes> varredor;
	private final ExecutorService chavesDeGerente;
	private final ScheduledExecutorService gravacao;
	private final ProvasDeTaxaRepository provas;
	private final ImpostosRepository impostosProvados;

	public OrquestradorDeTransformacao(Postgres banco, RpcDoEnsaio rpc) {

		PoolsNoGerenteRepository poolsNoGerente = new PoolsNoGerenteRepository(banco);
		this.chavesDeGerente = Executors.newSingleThreadExecutor(tarefa -> {
			Thread thread = new Thread(tarefa, "chaves-de-gerente");
			thread.setDaemon(true);
			return thread;
		});

		this.catalogo = new Catalogo();
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
		this.varredor = VarredorDeGerentes.urlDoAmbiente()
				.map(url -> new VarredorDeGerentes(url, poolsNoGerente, catalogo::liberarPedidas));
		LOG.info(() -> varredor.isPresent() ? "varredura de gerentes: ativa (RPC_LOGS)"
				: "varredura de gerentes: DESLIGADA (sem RPC_LOGS) — só pools que nascerem ao vivo terão chave");

		this.impostos = new Impostos();
		this.impostosProvados = new ImpostosRepository(banco);
		impostos.semear(impostosProvados.carregar());
		impostos.aoProvar(impostosProvados::anotar);

		this.provas = new ProvasDeTaxaRepository(banco);
		this.taxas = new Taxas();
		taxas.semear(provas.carregar());
		taxas.aoProvar(provas::anotar);

		this.gravacao = Executors.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "gravacao-de-taxas"));

		this.quarentena = new Quarentena(Path.of("quarentena.json"));
		this.tarifario = new Tarifario(catalogo, taxas, new Fabricas(), quarentena);
		this.cotador = new Cotador(catalogo, impostos, tarifario, quarentena);
		this.quentes = new Quentes(Path.of("quentes.json"));
		tarifario.porChamada(new EspacamentoPorChamada(pool -> tickSpacing(rpc, pool)));
		this.ticks = new Ticks(rpc::ethCall);
		catalogo.aoMudarLiquidez(ticks::aoMudarLiquidez);
		cotador.ticks(ticks);

		this.mempool = new ObservatorioMempool(catalogo, tarifario::taxa, tarifario::espacamento,
				tarifario::solidlyStable,
				(overlay, crua) -> ouvintesDeProjecao.forEach(ouvinte -> ouvinte.accept(overlay, crua)));
		this.simulador = new Simulador(rpc::chamar, mempool::aprenderDe);
		mempool.aoNaoDecodificar(simulador::talvez);
		mempool.aoValidarDecodificado(simulador::validar);

		this.observatorio = new ObservatorioArbitragem(catalogo,
				(ObservatorioArbitragem.AoConfirmar) (arbitragem, altura, indice, gatilho) -> {
					mempool.confirmar(arbitragem, altura);
					ouvintesDeConfirmacao.forEach(ouvinte -> ouvinte.aceitar(arbitragem, altura, indice, gatilho));
					quentes.vistaEmArbitragem(arbitragem.pools().stream().map(HexFormat.of()::formatHex).toList());
				});

		this.aplicador = new AplicadorDeBloco(catalogo);
		aplicador.aoTransicao(transicao -> {
			naBorda("taxas: observar", () -> taxas.observar(transicao));
			naBorda("impostos: observar", () -> impostos.observar(transicao));
		});
		aplicador.aoAplicarTransacao((logs, altura) -> naBorda("observatório: observar", () -> observatorio.observar(logs, altura)));

		this.sondagem = new Sondagem(catalogo, impostos, new Rpc());
	}

	public AplicadorDeBloco aplicador() {
		return aplicador;
	}

	public void antesDaTransacao(BiConsumer<List<Log>, Long> ouvinte) {
		aplicador.antesDaTransacao(ouvinte);
	}

	public void aoAplicarTransacao(BiConsumer<List<Log>, Long> ouvinte) {
		aplicador.aoAplicarTransacao(ouvinte);
	}

	public void aoConfirmarArbitragem(ObservatorioArbitragem.AoConfirmar ouvinte) {
		ouvintesDeConfirmacao.add(ouvinte);
	}

	public void aoProjetar(BiConsumer<Map<String, Pool>, byte[]> ouvinte) {
		ouvintesDeProjecao.add(ouvinte);
	}

	public void aoBlocoComPrecos(BiConsumer<Long, List<BigInteger>> ouvinte) {
		mempool.aoBlocoComPrecos(ouvinte);
	}

	public void observarHashes(Sessao sessao, NewPooledTransactionHashes mensagem) {
		mempool.observar(sessao, mensagem);
	}

	public void observarTransacoes(Sessao sessao, Transactions mensagem) {
		mempool.observar(sessao, mensagem);
	}

	public void blocoAnunciado(NewBlock bloco) {
		mempool.blocoAnunciado(bloco);
	}

	public Catalogo catalogo() {
		return catalogo;
	}

	public Cotador cotador() {
		return cotador;
	}

	public Tarifario tarifario() {
		return tarifario;
	}

	public Quentes quentes() {
		return quentes;
	}

	public Quarentena quarentena() {
		return quarentena;
	}

	public Impostos impostos() {
		return impostos;
	}

	public ObservatorioMempool mempool() {
		return mempool;
	}

	public Map<String, Object> resumoDoMercado() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("pools_v2", catalogo.poolsV2());
		resumo.put("pools_v3", catalogo.poolsV3());
		resumo.put("pools_v4", catalogo.resumoDoGerente(Eventos.GERENTE_V4));
		resumo.put("pools_infinity_cl", catalogo.resumoDoGerente(Eventos.GERENTE_INFINITY_CL));
		resumo.put("pares_conhecidos", catalogo.paresConhecidos());
		resumo.put("pools_descartadas", catalogo.poolsDescartadas());
		resumo.put("arbitragem", observatorio.resumo());
		resumo.put("mempool", mempool.resumo());
		return resumo;
	}

	public Map<String, Object> resumoDoEnsaio() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("impostos_assumidos", impostos.assumidos());
		resumo.put("simulador", simulador.resumo());
		resumo.put("ticks", ticks.resumo());
		return resumo;
	}

	public Optional<VarredorDeGerentes> varredor() {
		return varredor;
	}

	public void iniciar() {
		sondagem.iniciar();
		varredor.ifPresent(VarredorDeGerentes::iniciar);
		gravacao.scheduleWithFixedDelay(() -> descarregar(provas, impostosProvados), GRAVACAO_SEGUNDOS,
				GRAVACAO_SEGUNDOS, TimeUnit.SECONDS);
		gravacao.scheduleWithFixedDelay(quentes::salvar, 60, 60, TimeUnit.SECONDS);
	}

	public void fechar() {
		varredor.ifPresent(VarredorDeGerentes::close);
		chavesDeGerente.shutdownNow();
		mempool.close();
		simulador.close();
		quentes.salvar();
		sondagem.close();
		gravacao.shutdownNow();
		descarregar(provas, impostosProvados);
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

	private static void naBorda(String origem, Runnable observador) {
		try {
			observador.run();
		} catch (RuntimeException e) {
			Falhas.anotar(origem, e);
		}
	}
}
