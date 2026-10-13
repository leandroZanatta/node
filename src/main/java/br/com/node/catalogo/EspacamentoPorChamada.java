package br.com.node.catalogo;

import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

// O ESPAÇAMENTO de uma V3 de factory desconhecida, perguntado à PRÓPRIA pool (`tickSpacing()`), uma vez.
//
// É o que a identidade pelo endereço dava e aqui falta: sem espaçamento não se sabe até onde vai a faixa de
// liquidez, e o `PoolV3` aceitaria entrada que cruza tick. O `tickSpacing` é imutável no V3 e nos forks
// (fica no construtor da pool), então uma resposta vale para sempre. Medido em 25/09: a pool Slipstream
// `0xe7a0…` (factory `0x73dc…`, que a tabela não conhece) estava em 41 de 532 arbitragens alheias de 20 min.
//
// A pergunta sai numa thread própria: quem precifica nunca espera rede. Até a resposta chegar, a pool fica de
// fora — como sempre ficou.
public final class EspacamentoPorChamada {

	private final Function<byte[], OptionalInt> perguntar;
	private final Map<String, Integer> sabidos = new ConcurrentHashMap<>();
	private final Set<String> pedidos = ConcurrentHashMap.newKeySet();
	private final ExecutorService fila = Executors.newSingleThreadExecutor(tarefa -> {
		Thread thread = new Thread(tarefa, "espacamento-por-chamada");
		thread.setDaemon(true);
		return thread;
	});

	public EspacamentoPorChamada(Function<byte[], OptionalInt> perguntar) {
		this.perguntar = perguntar;
	}

	public OptionalInt de(byte[] pool) {

		String chave = HexFormat.of().formatHex(pool);
		Integer sabido = sabidos.get(chave);

		if (sabido != null) {
			return OptionalInt.of(sabido);
		}

		if (pedidos.add(chave)) {
			fila.execute(() -> perguntar.apply(pool).ifPresent(espacamento -> {
				// O que os contratos aceitam: positivo e dentro do que o tick máximo comporta.
				if (espacamento > 0 && espacamento <= 16_384) {
					sabidos.put(chave, espacamento);
				}
			}));
		}

		return OptionalInt.empty();
	}

	public int sabidos() {
		return sabidos.size();
	}
}
