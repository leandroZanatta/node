package br.com.node.transformacao.sondagem;
import br.com.node.baixa.rpc.Cadeia;


import java.io.Closeable;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import br.com.node.transformacao.catalogo.Catalogo;
import br.com.node.transformacao.catalogo.Impostos;
import br.com.node.transformacao.nucleo.Ciclo;
import br.com.node.transformacao.nucleo.Grafo;
import br.com.node.transformacao.nucleo.Par;
import br.com.node.transformacao.nucleo.Perna;
import br.com.node.transformacao.nucleo.ReservasV2;

public final class Sondagem implements Closeable {

	private static final Logger LOG = Logger.getLogger(Sondagem.class.getName());

	private static final int RECUO = 20;

	private static final List<byte[]> NUMERARIOS = List.of(
			HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"),
			HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955"),
			HexFormat.of().parseHex("e9e7cea3dedca5984780bafc599bd69add087d56"),
			HexFormat.of().parseHex("8ac76a51cc950d9822d68b83fe1ad97b32cd580d"),
			HexFormat.of().parseHex("ce24439f2d9c6a2289f741120fe202248b666666"));

	private static final int PERNAS = 3;

	private static final int POR_RODADA = 40;

	private final Catalogo catalogo;
	private final Impostos impostos;
	private final Sonda sonda;
	private final Cadeia cadeia;

	private final Set<String> medidos = ConcurrentHashMap.newKeySet();

	private final ScheduledExecutorService laco = Executors
			.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "sondagem"));

	public Sondagem(Catalogo catalogo, Impostos impostos, Cadeia cadeia) {
		this.catalogo = catalogo;
		this.impostos = impostos;
		this.cadeia = cadeia;
		this.sonda = new Sonda(cadeia);
	}

	public void iniciar() {
		laco.scheduleWithFixedDelay(this::rodada, 5, 30, TimeUnit.SECONDS);
	}

	@Override
	public void close() {
		laco.shutdownNow();
	}

	private void rodada() {

		try {

			long altura = cadeia.cabeca() - RECUO;
			int mediu = 0;

			for (byte[] pool : poolsEmCiclo()) {

				if (mediu >= POR_RODADA) {
					break;
				}

				Par par = catalogo.par(pool).orElse(null);

				if (par == null) {
					continue;
				}

				int antes = mediu;
				mediu += medir(pool, par.token0(), altura);
				mediu += medir(pool, par.token1(), altura);

				if (mediu == antes) {
					continue;
				}
			}

			int m = mediu;
			LOG.info(() -> "sondagem: " + m + " tokens medidos nesta rodada, " + impostos.quantosTributados()
					+ " tributados, " + impostos.quantosLimpos() + " limpos, " + impostos.quantosSuspeitos()
					+ " suspeitos, " + impostos.quantosProvados() + " provados pela sonda");

		} catch (RuntimeException e) {
			LOG.warning(() -> "rodada de sondagem falhou, fica para a próxima: " + e.getMessage());
		}
	}

	private List<byte[]> poolsEmCiclo() {

		Grafo grafo = new Grafo();

		for (byte[] pool : catalogo.pools()) {

			if (br.com.node.transformacao.catalogo.Catalogo.deGerente(pool)) {
				continue;
			}

			boolean temEstado = catalogo.reservas(pool).isPresent() || catalogo.estado(pool).isPresent();

			if (temEstado) {
				catalogo.par(pool).ifPresent(par -> grafo.ligar(pool, par));
			}
		}

		Set<String> vistas = new java.util.LinkedHashSet<>();
		List<byte[]> pools = new java.util.ArrayList<>();

		for (byte[] numerario : NUMERARIOS) {
			for (Ciclo ciclo : grafo.ciclos(numerario, PERNAS)) {
				for (Perna perna : ciclo.pernas()) {
					if (vistas.add(HexFormat.of().formatHex(perna.pool()))) {
						pools.add(perna.pool());
					}
				}
			}
		}

		pools.sort(java.util.Comparator.comparingInt(this::suspeita).thenComparing(
				java.util.Comparator.comparingLong(catalogo::atividade).thenComparing(this::reserva).reversed()));

		return pools;
	}

	private int suspeita(byte[] pool) {

		return catalogo.par(pool)
				.filter(par -> impostos.suspeito(pool, par.token0()) || impostos.suspeito(pool, par.token1()))
				.isPresent() ? 0 : 1;
	}

	private BigInteger reserva(byte[] pool) {
		return catalogo.reservas(pool).map(ReservasV2::reserva0).orElse(BigInteger.ZERO);
	}

	private int medir(byte[] pool, byte[] token, long altura) {

		if (impostos.jaProvado(pool, token)) {
			return 0;
		}

		String chave = HexFormat.of().formatHex(pool) + ":" + HexFormat.of().formatHex(token);

		if (!impostos.suspeito(pool, token) && !medidos.add(chave)) {
			return 0;
		}

		Medida venda = sonda.venda(token, pool, pool, altura);

		if (venda.motivo() == Medida.Motivo.SEM_RESPOSTA) {

			medidos.remove(chave);
			return 0;
		}

		if (venda.motivo().definitivo() && !venda.mediu()) {
			impostos.honeypot(pool, token);
			return 1;
		}

		Medida compra = sonda.compra(pool, token, altura);

		if (compra.motivo().definitivo() && !compra.mediu()) {
			impostos.honeypot(pool, token);
		} else if (compra.mediu() || (venda.mediu() && venda.bps() > 0)) {

			if (venda.mediu()) {
				impostos.medidaVenda(pool, token, venda.bps());
			}
			if (compra.mediu()) {
				if (compra.bps() >= 10_000) {
					impostos.honeypot(pool, token);
				} else {
					impostos.medidaCompra(pool, token, compra.bps());
				}
			}
		} else {

			medidos.remove(chave);
		}

		return 1;
	}
}