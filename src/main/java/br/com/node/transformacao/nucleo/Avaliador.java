package br.com.node.transformacao.nucleo;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public final class Avaliador {

	private static final int PASSOS_DE_BISSECAO = 64;

	public Optional<Oportunidade> melhor(List<Travessia> caminho) {

		if (caminho.size() < 2) {
			throw new IllegalArgumentException("Caminho fechado tem pelo menos duas pernas: " + caminho.size());
		}

		Optional<Curva> ciclo = curvaDo(caminho);

		if (ciclo.isEmpty() || !ciclo.get().rende()) {
			return Optional.empty();
		}

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

	private Optional<BigInteger> lucro(List<Travessia> caminho, BigInteger entrada) {
		return entrada.signum() <= 0 ? Optional.empty()
				: percorrer(caminho, entrada).map(saida -> saida.subtract(entrada));
	}

	private static final int DOBRAS = 48;
	private static final int PASSOS_DOURADOS = 160;

	private static final int PRECISAO_RELATIVA_BITS = 20;

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

		BigInteger melhor = null;
		BigInteger melhorLucro = null;

		for (BigInteger candidato : List.of(baixo, m1, m2, alto)) {
			BigInteger l = candidato.equals(m1) ? l1
					: candidato.equals(m2) ? l2 : lucro(caminho, candidato).orElse(null);
			if (l != null && (melhorLucro == null || l.compareTo(melhorLucro) > 0)) {
				melhor = candidato;
				melhorLucro = l;
			}
		}

		return melhorLucro == null ? BigInteger.ZERO : melhor;
	}

	private static BigInteger aurea(BigInteger largura, boolean deCima) {
		return largura.multiply(BigInteger.valueOf(deCima ? 618_034 : 381_966)).divide(BigInteger.valueOf(1_000_000));
	}

	private BigInteger maiorViavel(List<Travessia> caminho, BigInteger alvo) {

		if (alvo.signum() <= 0 || percorrer(caminho, alvo).isPresent()) {
			return alvo;
		}

		BigInteger viavel = BigInteger.ZERO;
		BigInteger inviavel = alvo;

		BigInteger tolerancia = alvo.shiftRight(PRECISAO_RELATIVA_BITS);

		for (int passo = 0; passo < PASSOS_DE_BISSECAO
				&& inviavel.subtract(viavel).compareTo(tolerancia) > 0; passo++) {

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