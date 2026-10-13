package br.com.node.mempool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.node.catalogo.Catalogo;
import br.com.node.nucleo.Arbitragem;
import br.com.node.nucleo.Par;

// Guarda as maiores arbitragens em WBNB já confirmadas, com o rastro da mempool que as previu.
//
// Saiu do `ObservatorioMempool` porque é outra responsabilidade: aquele INGERE a mempool e
// projeta; este ACUMULA o dossiê — o lucro total em WBNB e um top-N conferível no explorador,
// cada item cruzando a confirmação on-chain com a projeção que a antecipou. O acúmulo é
// compartilhado (a thread que confirma escreve, a do relatório lê), então a trava é uma só e
// mora aqui, não espalhada em `synchronized(this)` no observatório.
final class DossieWbnb {

	private static final int MAIORES = 10;
	private static final BigDecimal WEI_POR_BNB = new BigDecimal("1000000000000000000");

	private final Catalogo catalogo;
	private BigInteger lucro = BigInteger.ZERO;
	private final List<Map<String, Object>> maiores = new ArrayList<>();

	DossieWbnb(Catalogo catalogo) {
		this.catalogo = catalogo;
	}

	// Soma o lucro e guarda o item, mantendo só os `MAIORES` por lucro. `projecao` nulo é
	// confirmação que nenhuma projeção da mempool antecipou — vira "nao_medido", não zero.
	synchronized void registrar(Arbitragem arbitragem, long altura, Instant instante, Projecao projecao,
			Long adiantamentoMs) {

		lucro = lucro.add(arbitragem.sobra());

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("altura", altura);
		item.put("instante_confirmacao_local", instante.toString());
		item.put("pools", arbitragem.pools().stream().map(DossieWbnb::hex).toList());
		item.put("pares", arbitragem.pools().stream().map(this::par).toList());
		item.put("pernas", arbitragem.pernas());
		item.put("lucro_wbnb_wei", arbitragem.sobra().toString());
		item.put("lucro_bnb", bnb(arbitragem.sobra()));
		item.put("mempool_hash_correlato", projecao == null ? null : projecao.hash());
		item.put("mempool_primeiro_visto", projecao == null ? null : projecao.instante().toString());
		item.put("adiantamento_ms", adiantamentoMs);
		item.put("status_temporal", adiantamentoMs == null ? "nao_medido" : adiantamentoMs >= 0 ? "adiantado" : "atrasado");
		item.put("entrada_pendente_wei", projecao == null ? null : projecao.entrada().toString());
		item.put("saida_pendente_wei", projecao == null ? null : projecao.saida().toString());

		maiores.add(item);
		maiores.sort((a, b) -> new BigInteger((String) b.get("lucro_wbnb_wei"))
				.compareTo(new BigInteger((String) a.get("lucro_wbnb_wei"))));
		if (maiores.size() > MAIORES) {
			maiores.remove(maiores.size() - 1);
		}
	}

	synchronized String lucroWei() {
		return lucro.toString();
	}

	synchronized String lucroBnb() {
		return bnb(lucro);
	}

	synchronized List<Map<String, Object>> itens() {
		return List.copyOf(maiores);
	}

	private Map<String, String> par(byte[] pool) {

		Optional<Par> par = catalogo.par(pool);

		if (par.isEmpty()) {
			return Map.of("pool", hex(pool));
		}

		return Map.of("pool", hex(pool), "token0", hex(par.get().token0()), "token1", hex(par.get().token1()));
	}

	private static String bnb(BigInteger wei) {
		return new BigDecimal(wei).divide(WEI_POR_BNB).stripTrailingZeros().toPlainString();
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
