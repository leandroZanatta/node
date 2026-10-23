package br.com.node.transformacao.catalogo;

import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.transformacao.nucleo.Par;

public final class Tarifario {

	private final Catalogo catalogo;
	private final Taxas taxas;
	private final Fabricas fabricas;

	private final Set<String> emConflito = ConcurrentHashMap.newKeySet();

	private final Quarentena licoes;

	private volatile EspacamentoPorChamada porChamada;

	public void porChamada(EspacamentoPorChamada porChamada) {
		this.porChamada = porChamada;
	}

	public String porqueSemTaxaV3(byte[] pool) {

		EspacamentoPorChamada chamada = porChamada;

		if (chamada == null) {
			return "V3 sem taxa (factory desconhecida)";
		}

		if (chamada.de(pool).isEmpty()) {
			return "V3 sem espaçamento (perguntado à pool)";
		}

		return catalogo.taxaV3JaVista(pool) ? "V3: taxa instável (dinâmica)" : "V3: taxa ainda não observada";
	}

	public Tarifario(Catalogo catalogo, Taxas taxas, Fabricas fabricas) {
		this(catalogo, taxas, fabricas, new Quarentena());
	}

	public Tarifario(Catalogo catalogo, Taxas taxas, Fabricas fabricas, Quarentena licoes) {
		this.catalogo = catalogo;
		this.taxas = taxas;
		this.fabricas = fabricas;
		this.licoes = licoes;
	}

	public Optional<Integer> taxa(byte[] pool) {

		if (Catalogo.deGerente(pool)) {
			return catalogo.chaveDaPool(pool).filter(ChaveDaPool::modelavel).flatMap(c -> catalogo.taxaNoGerente(pool));
		}

		Optional<Par> par = catalogo.par(pool);

		if (par.isEmpty()) {
			return Optional.empty();
		}

		boolean v3 = catalogo.reservas(pool).isEmpty() && catalogo.estado(pool).isPresent();

		if (!v3) {
			Optional<Integer> medida = licoes.taxaMedida(pool);
			if (medida.isPresent()) {
				return medida;
			}
		}
		Optional<Origem> origem = fabricas.de(pool, par.get());

		if (origem.isPresent() && origem.get().taxa().isPresent()) {
			return conferida(pool, origem.get().taxa().getAsInt(), v3);
		}

		if (v3) {

			EspacamentoPorChamada chamada = porChamada;
			return chamada != null && origem.isEmpty() && chamada.de(pool).isPresent() ? catalogo.taxaObservadaV3(pool)
					: Optional.empty();
		}

		return taxas.fechadaAChamadores(pool) ? Optional.empty() : taxas.de(pool);
	}

	public OptionalInt espacamento(byte[] pool) {

		if (Catalogo.deGerente(pool)) {
			return catalogo.chaveDaPool(pool).map(c -> OptionalInt.of(c.espacamento())).orElse(OptionalInt.empty());
		}

		OptionalInt daIdentidade = catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).map(Origem::espacamento)
				.orElse(OptionalInt.empty());

		EspacamentoPorChamada chamada = porChamada;

		if (daIdentidade.isPresent() || chamada == null || catalogo.reservas(pool).isPresent()
				|| catalogo.estado(pool).isEmpty()
				|| catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).isPresent()) {
			return daIdentidade;
		}

		return chamada.de(pool);
	}

	public boolean solidlyStable(byte[] pool) {
		return catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).map(o -> o.fabrica().solidlyStable())
				.orElse(false);
	}

	public boolean identificada(byte[] pool) {
		return catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).isPresent();
	}

	public int quantasEmConflito() {
		return emConflito.size();
	}

	private Optional<Integer> conferida(byte[] pool, int daIdentidade, boolean v3) {

		Optional<Integer> provada = taxas.de(pool);

		boolean impossivel = provada.isPresent() && (v3 ? provada.get() != daIdentidade : provada.get() < daIdentidade);

		if (impossivel) {
			emConflito.add(HexFormat.of().formatHex(pool));
			return Optional.empty();
		}

		return Optional.of(daIdentidade);
	}
}