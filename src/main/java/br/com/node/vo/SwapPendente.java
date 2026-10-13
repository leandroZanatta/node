package br.com.node.vo;

import java.math.BigInteger;
import java.util.List;

// A intenção de um swap ainda na mempool: quanto entra, e por qual caminho de tokens.
//
// É o que se extrai da calldata de uma transação pendente para PROJETAR o efeito dela nas reservas
// antes de o bloco fechar. O caminho é a sequência de tokens (path do roteador): cada par consecutivo
// é uma perna. Só a intenção — a saída real sai de aplicar a curva de cada pool, com as truncagens
// delas, não do que o usuário pediu de mínimo.
//
// `pools`, quando não vazio, é a pool de CADA perna, na ordem — a que a tx de fato vai atravessar. Vem
// de quem sabe: o layout aprendido que leu a pool na calldata, ou o roteador que só troca na sua
// factory. Vazio quer dizer "só sei os tokens", e aí quem mapeia NÃO chuta entre pools do mesmo par.
public record SwapPendente(BigInteger entra, List<byte[]> caminho, List<byte[]> pools) {

	public SwapPendente {
		caminho = List.copyOf(caminho);
		pools = pools == null ? List.of() : List.copyOf(pools);
	}

	public SwapPendente(BigInteger entra, List<byte[]> caminho) {
		this(entra, caminho, List.of());
	}
}
