package br.com.node.catalogo;

import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

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