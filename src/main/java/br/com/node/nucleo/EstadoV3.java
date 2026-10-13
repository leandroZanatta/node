package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

// O preço e a liquidez de uma pool V3, na altura em que valem.
//
// Não há reserva aqui, e não é omissão: V3 não tem reserva. O `Swap` entrega os três
// campos prontos, e a liquidez ativa muda também por `Mint`/`Burn` cuja faixa cobre
// o tick corrente — o delta que faltava no projeto anterior.
public record EstadoV3(BigInteger sqrtPrecoX96, BigInteger liquidez, int tick, long altura) {

	private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);

	// Os tipos do contrato, que são fato de protocolo e não escolha nossa: `liquidity` é
	// uint128 e `sqrtPriceX96` é uint160.
	private static final BigInteger LIQUIDEZ_MAXIMA = BigInteger.ONE.shiftLeft(128);
	private static final BigInteger SQRT_MAXIMO = BigInteger.ONE.shiftLeft(160);

	// RECUSA ESTADO IMPOSSÍVEL, e isto achou o maior falso positivo que este projeto teve.
	//
	// O dossiê imprimiu uma pool com `liquidez = 1,41e48` — dez ordens de grandeza acima do
	// uint128. Em hexadecimal eram 160 bits exatos: um ENDEREÇO. O cursor lia o `amount` do Mint
	// na palavra 0, onde o Mint guarda o `sender`, e somava o endereço à liquidez.
	//
	// Esse estado entrava na curva e saía como oportunidade de 1,6 BILHÃO de pontos-base,
	// passando por todos os filtros a jusante, porque gas e retorno só sabem comparar, não
	// duvidar. O tipo do contrato é o lugar certo para duvidar — barato, exato, e na entrada.
	public EstadoV3 {

		if (sqrtPrecoX96.signum() <= 0 || sqrtPrecoX96.compareTo(SQRT_MAXIMO) >= 0) {
			throw new IllegalArgumentException("sqrtPriceX96 fora do uint160: " + sqrtPrecoX96);
		}

		if (liquidez.signum() < 0 || liquidez.compareTo(LIQUIDEZ_MAXIMA) >= 0) {
			throw new IllegalArgumentException("liquidez fora do uint128: " + liquidez);
		}

		if (Math.abs(tick) > TickMath.TICK_MAXIMO) {
			throw new IllegalArgumentException("tick fora da faixa do contrato: " + tick);
		}
	}

	// A TAXA EXATA QUE UM SWAP COBROU, pelo estado de ANTES e o de DEPOIS (o do evento), quando a liquidez não
	// mudou — liquidez diferente é tick cruzado (ou aporte no meio), e aí a faixa de antes não explica a troca.
	// Serve às pools de factory DESCONHECIDA, cuja taxa não está numa tabela: medido em 25/09 numa pool
	// Slipstream (`0xe7a0…`), 218 de 218 pares deram 3000 exatos; noutra (`0x767f…`), de taxa dinâmica, a
	// taxa andou entre 20 e 58 milionésimos de um swap para o outro. Quantias no sinal do V3: positivo ENTRA.
	public static Optional<Integer> taxaEntre(EstadoV3 antes, EstadoV3 depois, BigInteger quantia0, BigInteger quantia1) {

		if (antes == null || !antes.liquidez().equals(depois.liquidez()) || depois.liquidez().signum() <= 0
				|| antes.sqrtPrecoX96().equals(depois.sqrtPrecoX96())) {
			return Optional.empty();
		}

		BigInteger l = depois.liquidez();
		BigInteger s0 = antes.sqrtPrecoX96();
		BigInteger s1 = depois.sqrtPrecoX96();
		boolean entraToken0 = s1.compareTo(s0) < 0;

		BigInteger bruta = entraToken0 ? quantia0 : quantia1;
		BigInteger liquida = entraToken0 ? l.multiply(Q96).multiply(s0.subtract(s1)).divide(s0.multiply(s1))
				: l.multiply(s1.subtract(s0)).divide(Q96);

		if (bruta.signum() <= 0 || liquida.signum() <= 0 || liquida.compareTo(bruta) > 0) {
			return Optional.empty();
		}

		long taxa = bruta.subtract(liquida).multiply(BigInteger.valueOf(Pool.ESCALA_DA_TAXA))
				.add(bruta.shiftRight(1)).divide(bruta).longValue();

		return taxa >= 0 && taxa < Pool.ESCALA_DA_TAXA ? Optional.of((int) taxa) : Optional.empty();
	}

	// Os degraus que alguma factory conhecida cria: a união das tabelas da PancakeSwap V3 (2500) e da
	// Uniswap V3 (3000, que faltava — pool Uniswap de 0,3% nunca tinha a taxa recuperada).
	//
	// O ESPAÇAMENTO NÃO MORA MAIS AQUI. Ele é de cada factory, não da taxa: a conferência contra o
	// contrato achou uma pool de taxa 10000 com espaçamento 2000, onde o mapa universal dizia 200. Vem de
	// `Fabricas`, pela identidade da pool. E a própria taxa, em V3, a identidade prova pelo endereço — a
	// taxa por troca daqui serve de conferência.
	private static final List<Integer> DEGRAUS = List.of(100, 500, 2500, 3000, 10000);

	// A taxa que faz este swap fechar, deduzida do PRÓPRIO evento — sem estado anterior.
	//
	// Por que dá: o `Swap` entrega `amount0`, `amount1`, o `sqrtPriceX96` DEPOIS e a
	// liquidez. Dentro do tick, a saída amarra o preço de antes (`√antes = √depois +
	// saída1·Q96/L` quando entra token0), e com os dois preços a entrada LÍQUIDA fica
	// determinada. O que sobra entre a entrada bruta do evento e essa líquida é a taxa, e
	// ela só pode ser um dos degraus.
	//
	// Isto é o que faltava para o V3 entrar no grafo: sem taxa não há curva, sem curva a
	// pool não é enumerável, e V3 é onde a liquidez está. No projeto anterior foram 6.111
	// pools V3 catalogadas e ZERO arbitragem detectada nelas.
	public Optional<Integer> taxaQueFecha(BigInteger quantia0, BigInteger quantia1) {

		if (quantia0.signum() == quantia1.signum()) {
			return Optional.empty();
		}

		boolean entraToken0 = quantia0.signum() > 0;

		BigInteger bruta = entraToken0 ? quantia0 : quantia1;
		BigInteger saida = entraToken0 ? quantia1.negate() : quantia0.negate();

		if (bruta.signum() <= 0 || saida.signum() <= 0 || liquidez.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger liquidaExigida = entraToken0 ? entradaDeToken0(saida) : entradaDeToken1(saida);

		if (liquidaExigida.signum() <= 0 || liquidaExigida.compareTo(bruta) > 0) {
			return Optional.empty();
		}

		return degrauQueExplica(bruta, liquidaExigida);
	}

	// Entra token0, sai token1: `saída = L·(√antes − √depois)/Q96`, então
	// `√antes = √depois + saída·Q96/L`, e a entrada líquida é o token0 entre os dois
	// preços.
	private BigInteger entradaDeToken0(BigInteger saida) {

		BigInteger sqrtAntes = sqrtPrecoX96.add(saida.multiply(Q96).divide(liquidez));

		return liquidez.multiply(Q96).multiply(sqrtAntes.subtract(sqrtPrecoX96))
				.divide(sqrtAntes.multiply(sqrtPrecoX96));
	}

	// Entra token1, sai token0: `saída = L·Q96·(√depois − √antes)/(√depois·√antes)`,
	// invertido dá `√antes = L·Q96·√depois / (L·Q96 + saída·√depois)`.
	private BigInteger entradaDeToken1(BigInteger saida) {

		BigInteger numerador = liquidez.multiply(Q96);
		BigInteger sqrtAntes = numerador.multiply(sqrtPrecoX96).divide(numerador.add(saida.multiply(sqrtPrecoX96)));

		return liquidez.multiply(sqrtPrecoX96.subtract(sqrtAntes)).divide(Q96);
	}

	// O contrato cobra `ceil(bruta·taxa/E)` e passa o resto para a curva. A margem de um
	// wei é a truncagem das duas inversões acima, não folga de critério: sem ela, toda
	// recuperação exata falharia por arredondamento e o V3 ficaria fora do grafo para
	// sempre.
	private Optional<Integer> degrauQueExplica(BigInteger bruta, BigInteger liquidaExigida) {

		BigInteger escala = BigInteger.valueOf(Pool.ESCALA_DA_TAXA);

		Integer unico = null;

		for (int taxa : DEGRAUS) {

			BigInteger cobrada = bruta.multiply(BigInteger.valueOf(taxa)).add(escala).subtract(BigInteger.ONE)
					.divide(escala);

			if (bruta.subtract(cobrada).subtract(liquidaExigida).abs().compareTo(BigInteger.ONE) > 0) {
				continue;
			}

			// DOIS DEGRAUS QUE SERVEM NÃO É RESPOSTA, É EMPATE. Em swap pequeno a diferença
			// entre 100 e 10000 milionésimos cabe dentro do wei de folga, e aí "o primeiro que
			// bate" escolhe por ordem de lista — não por evidência.
			//
			// E o empate aqui é pior que taxa errada: o degrau amarra o `tickSpacing`, e
			// espaçamento grande demais faz `maximoNoTick` grande demais, o que faz `aplicar`
			// ACEITAR entrada que cruza tick. A nossa própria recusa deixa de proteger, e o
			// resultado é lucro fantasma grande — 378 pontos-base num ciclo que ninguém tomou.
			if (unico != null) {
				return Optional.empty();
			}

			unico = taxa;
		}

		return Optional.ofNullable(unico);
	}
}
