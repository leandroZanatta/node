package br.com.node.transformacao.nucleo;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public record MapaDeTicks(NavigableMap<Integer, BigInteger> liquidas, int tickMinimo, int tickMaximo, long altura) {

	public MapaDeTicks {

		if (tickMinimo > tickMaximo) {
			throw new IllegalArgumentException("faixa invertida: " + tickMinimo + " > " + tickMaximo);
		}

		liquidas = Collections.unmodifiableNavigableMap(new TreeMap<>(liquidas));
	}

	public static MapaDeTicks de(Map<Integer, BigInteger> liquidas, int tickMinimo, int tickMaximo, long altura) {
		return new MapaDeTicks(new TreeMap<>(liquidas), tickMinimo, tickMaximo, altura);
	}

	public Integer abaixoOuIgual(int tick) {
		Integer t = liquidas.floorKey(tick);
		return t != null && t >= tickMinimo ? t : null;
	}

	public Integer acima(int tick) {
		Integer t = liquidas.higherKey(tick);
		return t != null && t <= tickMaximo ? t : null;
	}

	public BigInteger liquida(int tick) {
		return liquidas.getOrDefault(tick, BigInteger.ZERO);
	}

	public MapaDeTicks com(int inferior, int superior, BigInteger delta, long novaAltura) {

		TreeMap<Integer, BigInteger> novas = new TreeMap<>(liquidas);

		if (inferior >= tickMinimo && inferior <= tickMaximo) {
			novas.merge(inferior, delta, BigInteger::add);
		}

		if (superior >= tickMinimo && superior <= tickMaximo) {
			novas.merge(superior, delta.negate(), BigInteger::add);
		}

		return new MapaDeTicks(novas, tickMinimo, tickMaximo, Math.max(altura, novaAltura));
	}

	public int tamanho() {
		return liquidas.size();
	}
}