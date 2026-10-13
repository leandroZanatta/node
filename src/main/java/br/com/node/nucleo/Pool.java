package br.com.node.nucleo;

import java.math.BigInteger;

// Uma pool sobre a qual se pode calcular um swap.
//
// Selada em duas, e as duas NÃO são variações da mesma coisa:
//
//   PoolV2 tem RESERVA — e reserva muda em swap, mint, burn e skim.
//   PoolV3 tem PREÇO E LIQUIDEZ — e mint/burn mudam o saldo SEM mudar o preço.
//
// Tratar V3 como reserva e aplicar deltas de saldo lê um aporte de liquidez como
// variação enorme de preço a favor. Não gera "um pouco de erro": gera oportunidade
// fantasma e estado que deriva sem volta. Foi o que cegou o projeto anterior —
// 6.111 pools V3 catalogadas e ZERO arbitragem detectada nelas, contra 63.423 nas
// V2.
//
// A conversão de V3 para reservas virtuais existe DENTRO do cálculo de um swap, e
// nunca como estado guardado.
public sealed interface Pool permits PoolV2, PoolV3, PoolSolidlyStable {

	// Taxa em milionésimos: 2500 = 25 bps = 0,25%. Uma unidade só para os dois,
	// porque V2 se expressa em bps e V3 em milionésimos, e converter na cabeça é
	// como uma pool gravada com 5 bps passa a cobrar 1000.
	int ESCALA_DA_TAXA = 1_000_000;

	// Um ponto-base em milionésimos. As factories cobram múltiplos dele — 2500 (Pancake), 1000
	// (Biswap), 3000 (fork padrão) e os quatro degraus do V3 —, então taxa fora dele não é taxa
	// incomum: é estado errado sendo absorvido pela conta.
	int PONTO_BASE = 100;

	// O que sai, e o estado que fica. Arredondamento SEMPRE contra nós: saída para
	// baixo. Superestimar saída fabrica lucro que não existe — subestimar só perde
	// uma oportunidade, e perder oportunidade não custa gas.
	Passo aplicar(Direcao direcao, BigInteger entrada);

	// A mesma travessia escrita como `a·x/(b+c·x)`. Está aqui, e não numa classe que
	// olha a pool de fora, porque os coeficientes saem da álgebra DESTA curva — quem
	// souber derivá-los de fora vai derivá-los errado quando nascer a terceira pool.
	Curva curva(Direcao direcao);
}
