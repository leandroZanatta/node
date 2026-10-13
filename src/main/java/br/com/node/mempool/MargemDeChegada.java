package br.com.node.mempool;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import br.com.node.catalogo.Catalogo;
import br.com.node.nucleo.Passo;
import br.com.node.vo.SwapPendente;

// Mede se a projeção do swap pendente dá margem para chegarmos antes dos outros.
// Não é garantia de execução: é uma avaliação de viabilidade do overlay antes do bloco.
// O resultado é binário e a margem mostra o quanto a projeção compra a prioridade.
public record MargemDeChegada(boolean chegaAntes, long margemEmPontosBase, BigInteger lucroProjetado) {

	public static MargemDeChegada avaliar(SwapPendente swap, Catalogo catalogo,
			Function<byte[], Optional<Integer>> taxaDaPool, BigInteger custoDeExecucao) {

		Optional<List<Passo>> passos = Roteamento.projetar(swap, catalogo, taxaDaPool);
		if (passos.isEmpty()) {
			return new MargemDeChegada(false, 0, BigInteger.ZERO);
		}

		BigInteger entrada = swap.entra();
		BigInteger saida = passos.get().getLast().saida();
		BigInteger lucro = saida.subtract(entrada).subtract(custoDeExecucao);
		long bps = lucro.signum() <= 0 ? 0 : lucro.multiply(BigInteger.valueOf(10_000)).divide(entrada).longValueExact();

		return new MargemDeChegada(lucro.signum() > 0, bps, lucro);
	}
}
