package br.com.node.mempool;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Conta os seletores de calldata que o roteamento NÃO decodificou como swap — o mapa do fluxo
// pendente que ainda falta cobrir (Universal Router, V3, agregadores). É diagnóstico do teto de
// recall (P25): mede ONDE está o volume antes de escrever decoder, para mirar o maior primeiro em vez
// de adivinhar. Escrito pela thread da mempool, lido pela do relatório — o mapa concorrente basta.
//
// E CONTA O CONTRATO DE DESTINO junto com o seletor, porque foi o que a medição mostrou decidir o
// próximo passo: o seletor sozinho diz "não decodifiquei", não diz se é um ROUTER conhecido que
// faltou ou um bot privado. MEDIDO em 14 blocos: 106 txs perdidas com 48 seletores e 46 contratos,
// e o top-1 contrato respondendo por 28% da perda — concentração que só se enxerga com o par
// (seletor, contrato). Sem ele, `bb6904ae` é um número; com ele, é um endereço a investigar.
public final class SeletoresVistos {

	private final Map<String, AtomicLong> contagem = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> porContrato = new ConcurrentHashMap<>();

	// Teto das chaves. Bots criam contrato novo o tempo todo, e cada (seletor, contrato) virava chave
	// para sempre — crescimento sem fim numa corrida de noite inteira. Passou do teto, poda a cauda que
	// só apareceu uma vez: o que importa aqui é o TOPO, e o topo sobrevive à poda.
	private static final int TETO_DE_CHAVES = 50_000;

	public void anotar(byte[] calldata, byte[] destino) {

		if (calldata == null || calldata.length < 4) {
			return;
		}

		String seletor = HexFormat.of().formatHex(calldata, 0, 4);

		contagem.computeIfAbsent(seletor, s -> new AtomicLong()).incrementAndGet();

		String onde = seletor + "@" + (destino == null || destino.length == 0 ? "create"
				: HexFormat.of().formatHex(destino));

		porContrato.computeIfAbsent(onde, s -> new AtomicLong()).incrementAndGet();

		if (porContrato.size() > TETO_DE_CHAVES) {
			porContrato.values().removeIf(n -> n.get() <= 1);
		}

		if (contagem.size() > TETO_DE_CHAVES) {
			contagem.values().removeIf(n -> n.get() <= 1);
		}
	}

	// Os `n` seletores mais frequentes, contagem desc — o topo do fluxo a decodificar.
	public Map<String, Long> top(int n) {

		Map<String, Long> topo = new LinkedHashMap<>();

		contagem.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get())).limit(n)
				.forEach(e -> topo.put(e.getKey(), e.getValue().get()));

		return topo;
	}

	// Os `n` pares (seletor, contrato) mais frequentes — para a perda virar ENDEREÇO, não número.
	public Map<String, Long> topPorContrato(int n) {

		Map<String, Long> topo = new LinkedHashMap<>();

		porContrato.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get())).limit(n)
				.forEach(e -> topo.put(e.getKey(), e.getValue().get()));

		return topo;
	}
}
