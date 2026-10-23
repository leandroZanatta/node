package br.com.node.transformacao.catalogo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import br.com.node.transformacao.nucleo.Ciclo;
import br.com.node.transformacao.nucleo.Direcao;
import br.com.node.transformacao.nucleo.Par;
import br.com.node.transformacao.nucleo.Perna;
import br.com.node.transformacao.nucleo.Pool;
import br.com.node.transformacao.nucleo.PoolV2;
import br.com.node.transformacao.nucleo.PoolV3;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.transformacao.nucleo.Travessia;

public final class Cotador {

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] BUSD = HexFormat.of().parseHex("e9e7cea3dedca5984780bafc599bd69add087d56");
	private static final byte[] USDC = HexFormat.of().parseHex("8ac76a51cc950d9822d68b83fe1ad97b32cd580d");
	private static final byte[] U = HexFormat.of().parseHex("ce24439f2d9c6a2289f741120fe202248b666666");

	private static final java.math.BigInteger PISO_WBNB = java.math.BigInteger.valueOf(50_000_000_000_000_000L);
	private static final java.math.BigInteger PISO_USDT = new java.math.BigInteger("30000000000000000000");

	private final Catalogo catalogo;
	private final Impostos impostos;
	private final Tarifario tarifario;
	private final Quarentena quarentena;

	public Cotador(Catalogo catalogo, Impostos impostos, Tarifario tarifario, Quarentena quarentena) {
		this.catalogo = catalogo;
		this.impostos = impostos;
		this.tarifario = tarifario;
		this.quarentena = quarentena;
	}

	private volatile Ticks ticks;

	public void ticks(Ticks ticks) {
		this.ticks = ticks;
	}

	public PoolV3 comTicks(byte[] pool, PoolV3 v3) {

		Ticks mapas = ticks;

		if (mapas == null) {
			return v3;
		}

		byte[] gerente = Catalogo.deGerente(pool) ? catalogo.chaveDaPool(pool).map(ChaveDaPool::gerente).orElse(null)
				: null;

		if (Catalogo.deGerente(pool) && gerente == null) {
			return v3;
		}

		mapas.garantir(pool, v3.tick(), v3.tickSpacing(), gerente);

		return v3.comTicks(mapas.mapa(pool).orElse(null));
	}

	public boolean livre(byte[] pool) {
		return quarentena.livre(pool);
	}

	public String motivoDeFora(byte[] pool) {

		if (!quarentena.livre(pool)) {
			return "quarentena";
		}

		if (Catalogo.deGerente(pool)) {
			Optional<ChaveDaPool> chave = catalogo.chaveDaPool(pool);
			if (chave.isEmpty()) {
				return "gerente sem chave (varredura não chegou)";
			}
			if (!chave.get().modelavel()) {
				return chave.get().taxaLp() == ChaveDaPool.TAXA_DINAMICA ? "gerente: taxa dinâmica" : "gerente: hook";
			}
		}

		Optional<Par> par = catalogo.par(pool);

		if (par.isEmpty()) {
			return "sem par no catálogo";
		}

		if (!algumSentido(pool, par.get())) {
			return "imposto";
		}

		if (!liquidezSuficiente(pool, par.get())) {
			return "liquidez abaixo do piso";
		}

		if (tarifario.taxa(pool).isEmpty()) {
			return catalogo.reservas(pool).isEmpty() && catalogo.estado(pool).isPresent()
					? tarifario.porqueSemTaxaV3(pool)
					: "V2 sem taxa provada";
		}

		if (estado(pool).isEmpty()) {
			return "sem estado (nenhum evento desde o início)";
		}

		if (catalogo.atividade(pool) <= 0) {
			return "sem atividade (fora do grafo da cauda)";
		}

		return "no grafo";
	}

	public boolean precifica(byte[] pool) {
		return quarentena.livre(pool) && catalogo.par(pool)
				.filter(par -> algumSentido(pool, par) && liquidezSuficiente(pool, par)).isPresent()
				&& tarifario.taxa(pool).isPresent() && estado(pool).isPresent();
	}

	public Optional<List<Travessia>> travessias(Ciclo ciclo) {
		return travessias(ciclo, Map.of());
	}

	public Optional<List<Travessia>> travessias(Ciclo ciclo, Map<String, Pool> overlay) {

		List<Travessia> caminho = new ArrayList<>();

		for (Perna perna : ciclo.pernas()) {

			Optional<Travessia> travessia = travessia(perna, overlay);

			if (travessia.isEmpty()) {
				return Optional.empty();
			}

			caminho.add(travessia.get());
		}

		return Optional.of(caminho);
	}

	private Optional<Travessia> travessia(Perna perna, Map<String, Pool> overlay) {

		if (!quarentena.livre(perna.pool())) {
			return Optional.empty();
		}

		Optional<Par> par = catalogo.par(perna.pool());
		Optional<Integer> taxa = tarifario.taxa(perna.pool());
		Optional<Catalogo.Lados> lados = catalogo.lados(perna.pool());
		Optional<ImpostosDaPerna> imposto = impostosDaPerna(perna.pool(), perna.entra(), perna.sai());

		if (par.isEmpty() || taxa.isEmpty() || lados.isEmpty() || imposto.isEmpty()) {
			return Optional.empty();
		}

		Direcao direcao = Arrays.equals(perna.entra(), lados.get().token0()) ? Direcao.ZERO_PARA_UM
				: Direcao.UM_PARA_ZERO;

		return pool(perna, taxa.get(), overlay)
				.map(pool -> new Travessia(pool, direcao, imposto.get().entra(), imposto.get().sai()));
	}

	private Optional<Pool> pool(Perna perna, int taxa, Map<String, Pool> overlay) {

		Pool projetada = overlay.get(HexFormat.of().formatHex(perna.pool()));

		if (projetada != null) {
			return Optional.of(projetada);
		}

		Optional<ReservasV2> reservas = catalogo.reservas(perna.pool());

		if (reservas.isPresent()) {
			return Optional
					.of(tarifario.solidlyStable(perna.pool())
							? new br.com.node.transformacao.nucleo.PoolSolidlyStable(reservas.get().reserva0(),
									reservas.get().reserva1(), taxa)
							: new PoolV2(reservas.get().reserva0(), reservas.get().reserva1(), taxa));
		}

		OptionalInt espacamento = tarifario.espacamento(perna.pool());

		if (espacamento.isEmpty()) {
			return Optional.empty();
		}

		return catalogo.estado(perna.pool()).map(estado -> comTicks(perna.pool(),
				new PoolV3(estado.sqrtPrecoX96(), estado.liquidez(), estado.tick(), taxa, espacamento.getAsInt())));
	}

	public record ImpostosDaPerna(int entra, int sai) {
	}

	public Optional<ImpostosDaPerna> impostosDaPerna(byte[] pool, byte[] entra, byte[] sai) {

		java.util.OptionalInt venda = impostos.venda(pool, entra);
		java.util.OptionalInt compra = impostos.compra(pool, sai);

		if (venda.isEmpty() || compra.isEmpty() || venda.getAsInt() >= 10_000 || compra.getAsInt() >= 10_000) {
			return Optional.empty();
		}

		if (venda.getAsInt() > 0 && catalogo.reservas(pool).isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new ImpostosDaPerna(venda.getAsInt(), compra.getAsInt()));
	}

	public double fatorDeImposto(byte[] pool, byte[] de, byte[] para) {
		return impostosDaPerna(pool, de, para).map(i -> (1 - i.entra() / 10_000.0) * (1 - i.sai() / 10_000.0))
				.orElse(0.0);
	}

	private boolean algumSentido(byte[] pool, Par par) {
		return impostosDaPerna(pool, par.token0(), par.token1()).isPresent()
				|| impostosDaPerna(pool, par.token1(), par.token0()).isPresent();
	}

	private static final java.math.BigInteger TETO_RESERVA_V2 = java.math.BigInteger.ONE.shiftLeft(112)
			.subtract(java.math.BigInteger.ONE).multiply(java.math.BigInteger.valueOf(95))
			.divide(java.math.BigInteger.valueOf(100));

	private boolean liquidezSuficiente(byte[] pool, Par par) {

		Optional<ReservasV2> reservas = catalogo.reservas(pool);

		if (reservas.isEmpty()) {
			return true;
		}

		java.math.BigInteger r0 = reservas.get().reserva0();
		java.math.BigInteger r1 = reservas.get().reserva1();

		if (r0.compareTo(TETO_RESERVA_V2) >= 0 || r1.compareTo(TETO_RESERVA_V2) >= 0) {
			return false;
		}

		if (Arrays.equals(par.token0(), WBNB)) {
			return r0.compareTo(PISO_WBNB) >= 0;
		}
		if (Arrays.equals(par.token1(), WBNB)) {
			return r1.compareTo(PISO_WBNB) >= 0;
		}
		if (Arrays.equals(par.token0(), USDT) || Arrays.equals(par.token0(), BUSD) || Arrays.equals(par.token0(), USDC)
				|| Arrays.equals(par.token0(), U)) {
			return r0.compareTo(PISO_USDT) >= 0;
		}
		if (Arrays.equals(par.token1(), USDT) || Arrays.equals(par.token1(), BUSD) || Arrays.equals(par.token1(), USDC)
				|| Arrays.equals(par.token1(), U)) {
			return r1.compareTo(PISO_USDT) >= 0;
		}

		return true;
	}

	private Optional<?> estado(byte[] pool) {

		Optional<ReservasV2> reservas = catalogo.reservas(pool);

		return reservas.isPresent() ? reservas : catalogo.estado(pool);
	}
}