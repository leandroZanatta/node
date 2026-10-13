package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

// Os ticks INICIALIZADOS de uma pool de liquidez concentrada, com o `liquidityNet` de cada um, dentro de uma
// FAIXA CONHECIDA [tickMinimo, tickMaximo]. É o que falta ao `PoolV3` para atravessar tick: ao cruzar um tick
// inicializado, a liquidez ativa muda pelo `liquidityNet` dele.
//
// Por que existe, com número: medido em 26/09 pela autópsia do mercado, o rival levou uma MEDIANA de 3,1× o
// lucro que o nosso modelo achava no MESMO ciclo e no MESMO estado — e num caso 5.500× (0,1117 BNB contra
// 0,00002), num ciclo só de pools de espaçamento 1. Sem este mapa a entrada parava no primeiro tick (~0,01% de
// preço); o rival atravessa dezenas.
//
// FORA DA FAIXA NÃO SE SABE, e não se finge: quem pedir o próximo tick além dela recebe a borda, e o swap que
// passaria da borda é recusado. Imutável: cada mudança de liquidez gera um mapa novo (são dezenas de ticks).
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

	// O maior tick inicializado ≤ `tick` dentro da faixa (é o que o preço cruza descendo), ou nulo.
	public Integer abaixoOuIgual(int tick) {
		Integer t = liquidas.floorKey(tick);
		return t != null && t >= tickMinimo ? t : null;
	}

	// O menor tick inicializado > `tick` dentro da faixa (é o que o preço cruza subindo), ou nulo.
	public Integer acima(int tick) {
		Integer t = liquidas.higherKey(tick);
		return t != null && t <= tickMaximo ? t : null;
	}

	public BigInteger liquida(int tick) {
		return liquidas.getOrDefault(tick, BigInteger.ZERO);
	}

	// Um `Mint`/`Burn`/`ModifyLiquidity` de [inferior, superior) com `delta` de liquidez: o `liquidityNet` do
	// inferior sobe `delta`, o do superior desce. Tick fora da faixa não importa para o que a faixa sabe.
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
