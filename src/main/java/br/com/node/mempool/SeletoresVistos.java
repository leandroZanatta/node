package br.com.node.mempool;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SeletoresVistos {

	private final Map<String, AtomicLong> contagem = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> porContrato = new ConcurrentHashMap<>();

	private static final int TETO_DE_CHAVES = 50_000;

	public void anotar(byte[] calldata, byte[] destino) {

		if (calldata == null || calldata.length < 4) {
			return;
		}

		String seletor = HexFormat.of().formatHex(calldata, 0, 4);

		contagem.computeIfAbsent(seletor, s -> new AtomicLong()).incrementAndGet();

		String onde = seletor + "@"
				+ (destino == null || destino.length == 0 ? "create" : HexFormat.of().formatHex(destino));

		porContrato.computeIfAbsent(onde, s -> new AtomicLong()).incrementAndGet();

		if (porContrato.size() > TETO_DE_CHAVES) {
			porContrato.values().removeIf(n -> n.get() <= 1);
		}

		if (contagem.size() > TETO_DE_CHAVES) {
			contagem.values().removeIf(n -> n.get() <= 1);
		}
	}

	public Map<String, Long> top(int n) {

		Map<String, Long> topo = new LinkedHashMap<>();

		contagem.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get())).limit(n)
				.forEach(e -> topo.put(e.getKey(), e.getValue().get()));

		return topo;
	}

	public Map<String, Long> topPorContrato(int n) {

		Map<String, Long> topo = new LinkedHashMap<>();

		porContrato.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get())).limit(n)
				.forEach(e -> topo.put(e.getKey(), e.getValue().get()));

		return topo;
	}
}