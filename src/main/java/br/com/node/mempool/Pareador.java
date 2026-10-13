package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import br.com.node.catalogo.Eventos;
import br.com.node.nucleo.Par;
import br.com.node.vo.Log;

// Junta o que foi ANUNCIADO com o que o BLOCO mostrou, pelo id da transação.
//
// É o que transforma o bloco fechado em gabarito de graça: X = a calldata que vimos na mempool, Y = os
// tokens e o montante que a cadeia provou. Sem esse par não há aprendizado supervisionado — e não é
// preciso anotar nada à mão, porque o pareamento é por hash e o rótulo é a própria cadeia.
//
// Três vazios diferentes, e os três descartam o exemplo INTEIRO em vez de produzir rótulo duvidoso:
// anunciada que não entrou (não há Y), incluída que não vimos anunciada (não há X) e pool que o catálogo
// não conhece (o token não se inventa). Aprender com rótulo duvidoso é pior que não aprender: a regra
// errada sobrevive ao exemplo que a desmentiu.
public final class Pareador {

	private Pareador() {
	}

	public static List<ExemploDeMovimento> parear(Map<String, TransacaoAnunciada> anunciadas,
			Map<String, List<Log>> porId, Function<byte[], Optional<Par>> parDaPool) {

		List<ExemploDeMovimento> exemplos = new ArrayList<>();

		for (Map.Entry<String, TransacaoAnunciada> anuncio : anunciadas.entrySet()) {

			List<Log> logs = porId.get(anuncio.getKey());

			if (logs == null || logs.isEmpty()) {
				continue;
			}

			List<byte[]> tokens = new ArrayList<>();
			BigInteger entrada = BigInteger.ZERO;
			List<br.com.node.nucleo.Perna> pernas = pernas(logs, parDaPool);

			for (Log log : logs) {

				boolean v2 = log.assinadoPor(Eventos.SWAP_V2);
				boolean v3 = Eventos.ehSwapV3(log);

				if (!v2 && !v3) {
					continue;
				}

				Optional<Par> par = parDaPool.apply(log.endereco());

				if (par.isEmpty()) {
					continue;
				}

				juntar(tokens, par.get().token0());
				juntar(tokens, par.get().token1());

				// A entrada da TRANSAÇÃO é a da primeira troca; as seguintes são a saída da anterior —
				// por isso só a primeira é lida, e ela é lida uma vez.
				if (entrada.signum() <= 0) {
					entrada = entradaDaTroca(log, v2);
				}
			}

			if (tokens.size() < 2 || entrada.signum() <= 0) {
				continue;
			}

			exemplos.add(new ExemploDeMovimento(anuncio.getValue(), tokens, entrada, pernas));
		}

		return List.copyOf(exemplos);
	}

	// As trocas da tx EM ORDEM, com a direção de cada uma: no V2 entrou o token cujo `amountIn` é
	// positivo; no V3, o de montante positivo. Troca em pool sem par conhecido, ou com direção ambígua,
	// ESVAZIA o resultado — caminho com buraco no meio não é caminho, e ensinaria posição errada.
	public static List<br.com.node.nucleo.Perna> pernas(List<Log> logs, Function<byte[], Optional<Par>> parDaPool) {

		List<br.com.node.nucleo.Perna> pernas = new ArrayList<>();

		for (Log log : logs) {

			boolean v2 = log.assinadoPor(Eventos.SWAP_V2);
			boolean v3 = Eventos.ehSwapV3(log);

			if (!v2 && !v3) {
				continue;
			}

			Optional<Par> par = parDaPool.apply(log.endereco());

			if (par.isEmpty()) {
				return List.of();
			}

			Boolean entraZero;

			if (v2) {
				boolean zero = Eventos.palavra(log, 0).signum() > 0;
				boolean um = Eventos.palavra(log, 1).signum() > 0;
				entraZero = zero == um ? null : zero;
			} else {
				boolean zero = Eventos.palavraComSinalGrande(log, 0).signum() > 0;
				boolean um = Eventos.palavraComSinalGrande(log, 1).signum() > 0;
				entraZero = zero == um ? null : zero;
			}

			if (entraZero == null) {
				return List.of();
			}

			byte[] entra = entraZero ? par.get().token0() : par.get().token1();
			byte[] sai = entraZero ? par.get().token1() : par.get().token0();
			pernas.add(new br.com.node.nucleo.Perna(log.endereco(), entra, sai));
		}

		return List.copyOf(pernas);
	}

	// O que ENTROU na pool. No V2 o evento dá quatro montantes sem sinal e um dos dois `in` é zero; no
	// V3 são dois com sinal, e o positivo é o que entrou (negativo é o que saiu).
	private static BigInteger entradaDaTroca(Log log, boolean v2) {

		if (v2) {
			BigInteger entrada0 = Eventos.palavra(log, 0);
			return entrada0.signum() > 0 ? entrada0 : Eventos.palavra(log, 1);
		}

		BigInteger montante0 = Eventos.palavraComSinalGrande(log, 0);

		if (montante0.signum() > 0) {
			return montante0;
		}

		BigInteger montante1 = Eventos.palavraComSinalGrande(log, 1);

		// Dois negativos não descrevem uma troca: devolver zero descarta o exemplo em vez de escolher um
		// lado por conta própria.
		return montante1.signum() > 0 ? montante1 : BigInteger.ZERO;
	}

	private static void juntar(List<byte[]> tokens, byte[] token) {

		for (byte[] ja : tokens) {
			if (Arrays.equals(ja, token)) {
				return;
			}
		}

		tokens.add(token);
	}
}
