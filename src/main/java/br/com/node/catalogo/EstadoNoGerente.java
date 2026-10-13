package br.com.node.catalogo;

import br.com.node.nucleo.EstadoV3;

// Uma pool que mora dentro de um GERENTE (Uniswap V4, PancakeSwap Infinity CL): o preço e a liquidez
// são os do V3 — a matemática da faixa é a mesma —, e a taxa é a que o último `Swap` cobrou.
//
// A taxa vem do EVENTO e não da identidade porque aqui ela pode mudar de swap para swap (pool com
// taxa dinâmica, decidida por hook). Medido em 25/09: 95, 449, 2, 1, 38 e 70000 milionésimos entre
// as mais frequentes — nada dos degraus fixos do V3. Enquanto o `PoolKey` (tokens, espaçamento,
// hooks) não for conhecido, este estado é só observação: não entra no grafo.
public record EstadoNoGerente(byte[] gerente, EstadoV3 estado, int taxa) {

	// Taxa de 100% ou mais não é taxa: é payload inventado ou palavra lida errada.
	public EstadoNoGerente {

		if (taxa < 0 || taxa >= br.com.node.nucleo.Pool.ESCALA_DA_TAXA) {
			throw new IllegalArgumentException("taxa fora de [0, 100%): " + taxa);
		}
	}
}
