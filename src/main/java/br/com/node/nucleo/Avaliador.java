package br.com.node.nucleo;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

// Diz se um caminho fechado rende, de quanto entrar, e quanto volta.
//
// A DIVISÃO DE TRABALHO É O PONTO: a `Curva` diz ONDE OLHAR, as pools dizem O QUE SE
// RECEBE. Compor a curva do ciclo custa três multiplicações por perna e responde "rende?"
// sem nenhuma divisão — é isso que permite varrer milhares de ciclos por bloco. Só nos
// poucos que passam é que se paga o caminho exato, pool por pool, com as truncagens de
// cada contrato.
//
// Usar a curva para prometer lucro seria prometer wei que o contrato não paga, e é
// exatamente a classe de erro que produz revert: a curva ignora arredondamento, e o
// arredondamento do V2 é contra quem chama.
//
// TETO DE TICK: a pool V3 recusa o que cruza o tick, porque a liquidez do outro lado
// exigiria história que os pares não guardam. Como o lucro cresce monotonicamente até x*,
// a melhor entrada viável é `min(x*, o maior que não cruza)` — e achá-la é bisseção, não
// otimização. Recusar o ciclo inteiro por causa do teto seria jogar fora a oportunidade
// por não saber medi-la.
public final class Avaliador {

	// 64 passos de bisseção sobre um alvo de até 2^256 fecham em um wei. Contar passos em
	// vez de convergir por tolerância é o que garante que isto termina.
	private static final int PASSOS_DE_BISSECAO = 64;

	public Optional<Oportunidade> melhor(List<Travessia> caminho) {

		if (caminho.size() < 2) {
			throw new IllegalArgumentException("Caminho fechado tem pelo menos duas pernas: " + caminho.size());
		}

		Optional<Curva> ciclo = curvaDo(caminho);

		if (ciclo.isEmpty() || !ciclo.get().rende()) {
			return Optional.empty();
		}

		// Com mapa de ticks, a curva só descreve a faixa CORRENTE, e a entrada ótima dela é o teto do primeiro tick
		// — exatamente o que o rival passa. Ali se otimiza sobre o caminho EXATO (`PoolV3` atravessando).
		BigInteger entrada = caminho.stream().anyMatch(t -> t.pool() instanceof PoolV3 v3 && v3.atravessa())
				? otimaAtravessando(caminho, ciclo.get().entradaOtima())
				: maiorViavel(caminho, ciclo.get().entradaOtima());

		if (entrada.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger saida = percorrer(caminho, entrada).orElse(BigInteger.ZERO);

		if (saida.compareTo(entrada) <= 0) {
			return Optional.empty();
		}

		return Optional.of(new Oportunidade(entrada, saida));
	}

	// Vazio quando alguma perna não tem curva: pool drenada tem coeficiente zero, e `Curva`
	// recusa isso em vez de devolver uma função degenerada. Aqui a recusa é RESPOSTA, não
	// erro — do contrário uma única pool zerada no catálogo derrubaria a avaliação do bloco
	// inteiro, e com ela o cursor que a chamou.
	private Optional<Curva> curvaDo(List<Travessia> caminho) {

		try {

			Curva ciclo = caminho.get(0).curva();

			for (int perna = 1; perna < caminho.size(); perna++) {
				ciclo = ciclo.compor(caminho.get(perna).curva());
			}

			return Optional.of(ciclo);

		} catch (IllegalArgumentException semCurva) {
			return Optional.empty();
		}
	}

	// Vazio quando alguma perna recusa: entrada que cruza tick, pool sem reserva, saída
	// zerada por truncagem. Recusa não é exceção aqui — é resposta.
	private Optional<BigInteger> percorrer(List<Travessia> caminho, BigInteger entrada) {

		BigInteger carregando = entrada;

		for (Travessia travessia : caminho) {

			try {

				carregando = travessia.aplicar(carregando).saida();

			} catch (IllegalArgumentException recusou) {
				return Optional.empty();
			}

			if (carregando.signum() <= 0) {
				return Optional.empty();
			}
		}

		return Optional.of(carregando);
	}

	// O lucro de uma entrada pelo caminho exato, ou vazio se alguma perna recusa (passou da faixa conhecida de
	// ticks, pool sem liquidez).
	private Optional<BigInteger> lucro(List<Travessia> caminho, BigInteger entrada) {
		return entrada.signum() <= 0 ? Optional.empty() : percorrer(caminho, entrada).map(saida -> saida.subtract(entrada));
	}

	private static final int DOBRAS = 48;
	private static final int PASSOS_DOURADOS = 160;

	// A busca PARA quando o intervalo cai abaixo de 2^-20 (~1 milionésimo) da entrada. Perto do pico o lucro é
	// plano — o que se perde por errar a entrada em ε é da ordem de ε² —, e a entrada escolhida é SEMPRE
	// avaliada pelo caminho exato, então o lucro prometido segue ao wei. CICATRIZ de 26/09: a busca ternária ia
	// até 2 wei, ~340 caminhos exatos por ciclo (cada um atravessando dezenas de ticks), e a avaliação do
	// garimpo chegou a ~1.000 ms (p90) no servidor.
	private static final int PRECISAO_RELATIVA_BITS = 20;

	// O lucro de um ciclo de AMMs é côncavo na entrada (cada perna tem saída côncava e crescente, e a composição
	// preserva isso), e a região viável é um intervalo [0, X] — X é onde alguma perna passa da faixa conhecida.
	// Então: dobra a partir da ótima da faixa corrente enquanto o lucro sobe, e busca de SEÇÃO ÁUREA no
	// intervalo que contém o pico — um caminho exato por passo (a ternária gastava dois), com o inviável valendo
	// menos infinito, o que mantém a função unimodal no intervalo inteiro.
	private BigInteger otimaAtravessando(List<Travessia> caminho, BigInteger inicial) {

		BigInteger baixo = BigInteger.ZERO;
		BigInteger alto = inicial.signum() > 0 ? inicial : BigInteger.ONE;
		Optional<BigInteger> lucroAlto = lucro(caminho, alto);

		if (lucroAlto.isEmpty()) {
			alto = maiorViavel(caminho, alto);
			lucroAlto = lucro(caminho, alto);
		}

		for (int dobra = 0; dobra < DOBRAS && lucroAlto.isPresent(); dobra++) {

			BigInteger dobrado = alto.shiftLeft(1);
			Optional<BigInteger> lucroDobrado = lucro(caminho, dobrado);

			if (lucroDobrado.isEmpty()) {
				baixo = alto.shiftRight(1);
				alto = maiorViavel(caminho, dobrado);
				break;
			}

			if (lucroDobrado.get().compareTo(lucroAlto.get()) <= 0) {
				baixo = alto.shiftRight(1);
				alto = dobrado;
				break;
			}

			alto = dobrado;
			lucroAlto = lucroDobrado;
		}

		BigInteger tolerancia = alto.shiftRight(PRECISAO_RELATIVA_BITS).max(BigInteger.TWO);
		BigInteger m1 = baixo.add(aurea(alto.subtract(baixo), false));
		BigInteger m2 = baixo.add(aurea(alto.subtract(baixo), true));
		BigInteger l1 = lucro(caminho, m1).orElse(null);
		BigInteger l2 = lucro(caminho, m2).orElse(null);

		for (int passo = 0; passo < PASSOS_DOURADOS && alto.subtract(baixo).compareTo(tolerancia) > 0; passo++) {

			if (l2 == null || (l1 != null && l1.compareTo(l2) >= 0)) {
				alto = m2;
				m2 = m1;
				l2 = l1;
				m1 = baixo.add(aurea(alto.subtract(baixo), false));
				l1 = lucro(caminho, m1).orElse(null);
			} else {
				baixo = m1;
				m1 = m2;
				l1 = l2;
				m2 = baixo.add(aurea(alto.subtract(baixo), true));
				l2 = lucro(caminho, m2).orElse(null);
			}
		}

		// Os dois pontos internos já avaliados entram na escolha final junto com as bordas.
		BigInteger melhor = null;
		BigInteger melhorLucro = null;

		for (BigInteger candidato : List.of(baixo, m1, m2, alto)) {
			BigInteger l = candidato.equals(m1) ? l1 : candidato.equals(m2) ? l2 : lucro(caminho, candidato).orElse(null);
			if (l != null && (melhorLucro == null || l.compareTo(melhorLucro) > 0)) {
				melhor = candidato;
				melhorLucro = l;
			}
		}

		return melhorLucro == null ? BigInteger.ZERO : melhor;
	}

	// Os pontos da seção áurea num intervalo de largura `largura`: 0,382 (o de baixo) ou 0,618 (o de cima).
	private static BigInteger aurea(BigInteger largura, boolean deCima) {
		return largura.multiply(BigInteger.valueOf(deCima ? 618_034 : 381_966)).divide(BigInteger.valueOf(1_000_000));
	}

	private BigInteger maiorViavel(List<Travessia> caminho, BigInteger alvo) {

		if (alvo.signum() <= 0 || percorrer(caminho, alvo).isPresent()) {
			return alvo;
		}

		BigInteger viavel = BigInteger.ZERO;
		BigInteger inviavel = alvo;
		// A mesma parada relativa da busca áurea: a borda viável a um milionésimo basta — o que sai daqui é
		// sempre reavaliado pelo caminho exato. Até 1 wei eram 64 caminhos, e a busca pede esta borda duas vezes.
		BigInteger tolerancia = alvo.shiftRight(PRECISAO_RELATIVA_BITS);

		for (int passo = 0; passo < PASSOS_DE_BISSECAO && inviavel.subtract(viavel).compareTo(tolerancia) > 0; passo++) {

			BigInteger meio = viavel.add(inviavel).shiftRight(1);

			if (meio.equals(viavel) || meio.equals(inviavel)) {
				break;
			}

			if (percorrer(caminho, meio).isPresent()) {
				viavel = meio;
			} else {
				inviavel = meio;
			}
		}

		return viavel;
	}
}
