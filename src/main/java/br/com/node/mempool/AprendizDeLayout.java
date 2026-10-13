package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

// Descobre o layout de um contrato a partir de exemplos que o BLOCO rotulou.
//
// Por que existe: medido na BSC, o fluxo pendente é dominado por contrato PRIVADO de bot — seletor que
// não existe em nenhuma base pública e calldata empacotada, sem ABI. Escrever decodificador para cada
// um não escala (a perda medida são 31 contratos distintos para 46 transações), mas esses contratos
// REPETEM o mesmo formato milhões de vezes. Então não se escreve o decodificador: ele é APRENDIDO, com
// o próprio bloco como gabarito.
//
// Como: janela deslizante de 20 bytes (os formatos de bot são empacotados, sem alinhamento de palavra)
// para achar ONDE os tokens movidos apareceram; e a palavra de 32 bytes do montante observado, para
// achar onde ele apareceu. O que não se repete na maioria das amostras é ruído, não layout.
//
// O que NÃO faz, e é deliberado: não aprende sozinho o que já é dado. Contrato de ABI pública
// (Universal Router, 1inch, Pancake V3) ganha decodificador escrito à mão, que é mais barato e não
// precisa de amostra. Aprender é para o OPACO.
public final class AprendizDeLayout {

	private AprendizDeLayout() {
	}

	// `minimo` é o consenso: um deslocamento só vira layout se aparecer em pelo menos tantas amostras.
	// Com ele em 1, um único exemplo com acidente de formatação viraria regra para o mundo.
	public static Optional<LayoutAprendido> aprender(List<ExemploDeMovimento> exemplos, int minimo) {

		if (exemplos == null || minimo < 1 || exemplos.size() < minimo) {
			return Optional.empty();
		}

		byte[] destino = exemplos.get(0).anunciada().destino();
		String seletor = exemplos.get(0).anunciada().seletor();

		// Um layout é de UM contrato e UM método. Misturar é aprender o formato de dois — e o resultado
		// não lê nenhum dos dois. Foi por isso que o mesmo bot com dois command ids (`0x00000001`,
		// `0x00000002`) precisa de dois layouts, e não de um médio.
		for (ExemploDeMovimento exemplo : exemplos) {

			if (!Arrays.equals(exemplo.anunciada().destino(), destino)
					|| !exemplo.anunciada().seletor().equals(seletor)) {
				return Optional.empty();
			}
		}

		// Com as trocas EM ORDEM, aprende-se caminho com direção e pool; é o único layout que o node usa.
		if (exemplos.stream().allMatch(e -> !e.pernas().isEmpty())) {
			return aprenderOrdenado(exemplos, minimo, destino, seletor);
		}

		Map<Integer, Integer> votosDeToken = new TreeMap<>();
		Map<Integer, Integer> votosDeMontante = new TreeMap<>();

		for (ExemploDeMovimento exemplo : exemplos) {

			byte[] calldata = exemplo.anunciada().calldata();

			for (int onde : deslocamentosDosTokens(calldata, exemplo.tokensMovidos())) {
				votosDeToken.merge(onde, 1, Integer::sum);
			}

			int montante = deslocamentoDoMontante(calldata, exemplo.entradaObservada());

			if (montante >= 0) {
				votosDeMontante.merge(montante, 1, Integer::sum);
			}
		}

		List<Integer> tokens = votosDeToken.entrySet().stream().filter(voto -> voto.getValue() >= minimo)
				.map(Map.Entry::getKey).toList();

		if (tokens.isEmpty()) {
			return Optional.empty();
		}

		int entrada = votosDeMontante.entrySet().stream().filter(voto -> voto.getValue() >= minimo)
				.map(Map.Entry::getKey).findFirst().orElse(LayoutAprendido.SEM_MONTANTE);

		LayoutAprendido provisorio = new LayoutAprendido(destino, seletor, tokens, entrada, exemplos.size(), 0);

		return Optional.of(new LayoutAprendido(destino, seletor, tokens, entrada, exemplos.size(),
				confirmados(provisorio, exemplos)));
	}

	// O CAMINHO na ordem da troca — t0 = o que entra na primeira perna, t(i+1) = o que sai da perna i — e
	// a posição de cada um por consenso; a pool de cada perna, onde aparecer; e o par → pool dos exemplos.
	//
	// CICATRIZ de 24/09: o aprendiz antigo votava em TODA posição onde QUALQUER token movido aparecia e lia
	// na ordem da calldata. Resultado medido: token repetido virava caminho de ida e volta (WBNB→X→WBNB), e
	// o contrato que escreve a saída antes da entrada era projetado ao contrário. A conferência comparava
	// conjuntos e não via nenhum dos dois — o "1 em 3 errado" do placar era o PISO do erro.
	private static Optional<LayoutAprendido> aprenderOrdenado(List<ExemploDeMovimento> exemplos, int minimo,
			byte[] destino, String seletor) {

		int pernas = exemplos.get(0).pernas().size();

		// Número diferente de trocas é outro caminho: o contrato roteia diferente conforme o valor, e a
		// mesma posição não significa a mesma coisa. Fica sem layout.
		if (exemplos.stream().anyMatch(e -> e.pernas().size() != pernas)) {
			return Optional.empty();
		}

		List<Integer> caminho = new ArrayList<>();

		for (int k = 0; k <= pernas; k++) {

			Map<Integer, Integer> votos = new TreeMap<>();

			for (ExemploDeMovimento exemplo : exemplos) {
				byte[] token = k == 0 ? exemplo.pernas().get(0).entra() : exemplo.pernas().get(k - 1).sai();
				for (int onde : posicoes(exemplo.anunciada().calldata(), token)) {
					votos.merge(onde, 1, Integer::sum);
				}
			}

			Optional<Integer> escolhida = maisVotada(votos, minimo, caminho);

			if (escolhida.isEmpty()) {
				return Optional.empty();
			}

			caminho.add(escolhida.get());
		}

		List<Integer> pools = new ArrayList<>();

		for (int i = 0; i < pernas; i++) {

			Map<Integer, Integer> votos = new TreeMap<>();

			for (ExemploDeMovimento exemplo : exemplos) {
				for (int onde : posicoes(exemplo.anunciada().calldata(), exemplo.pernas().get(i).pool())) {
					votos.merge(onde, 1, Integer::sum);
				}
			}

			Optional<Integer> escolhida = maisVotada(votos, minimo, pools);

			if (escolhida.isEmpty()) {
				pools.clear();
				break;
			}

			pools.add(escolhida.get());
		}

		// Par → pool, só onde todos os exemplos concordam: o mesmo par por duas pools é contrato que
		// escolhe a pool em tempo de execução, e aí o mapa não pode afirmar qual.
		Map<String, String> poolPorPar = new java.util.HashMap<>();
		java.util.Set<String> ambiguos = new java.util.HashSet<>();

		for (ExemploDeMovimento exemplo : exemplos) {
			for (br.com.node.nucleo.Perna perna : exemplo.pernas()) {
				String par = LayoutAprendido.chaveDoPar(perna.entra(), perna.sai());
				String pool = HexFormat.of().formatHex(perna.pool());
				String antes = poolPorPar.putIfAbsent(par, pool);
				if (antes != null && !antes.equals(pool)) {
					ambiguos.add(par);
				}
			}
		}
		ambiguos.forEach(poolPorPar::remove);

		Map<Integer, Integer> votosDeMontante = new TreeMap<>();

		for (ExemploDeMovimento exemplo : exemplos) {
			int montante = deslocamentoDoMontante(exemplo.anunciada().calldata(), exemplo.entradaObservada());
			if (montante >= 0) {
				votosDeMontante.merge(montante, 1, Integer::sum);
			}
		}

		int entrada = maisVotada(votosDeMontante, minimo, List.of()).orElse(LayoutAprendido.SEM_MONTANTE);

		LayoutAprendido provisorio = new LayoutAprendido(destino, seletor, caminho, entrada, exemplos.size(), 0, true,
				pools, poolPorPar);

		int reproduz = 0;

		for (ExemploDeMovimento exemplo : exemplos) {
			if (reproduz(provisorio, exemplo.anunciada().calldata(), exemplo.pernas())) {
				reproduz++;
			}
		}

		return Optional.of(new LayoutAprendido(destino, seletor, caminho, entrada, exemplos.size(), reproduz, true,
				pools, poolPorPar));
	}

	// O layout lê desta calldata o MESMO caminho, na mesma ordem, e as mesmas pools que a cadeia fez?
	// Pools só contam quando o layout as sabe; não sabê-las não é errar — o mapeador então não chuta.
	static boolean reproduz(LayoutAprendido layout, byte[] calldata, List<br.com.node.nucleo.Perna> reais) {

		Optional<List<byte[]>> lido = layout.tokensLidos(calldata);

		if (lido.isEmpty() || lido.get().size() != reais.size() + 1) {
			return false;
		}

		for (int k = 0; k <= reais.size(); k++) {
			byte[] esperado = k == 0 ? reais.get(0).entra() : reais.get(k - 1).sai();
			if (!Arrays.equals(lido.get().get(k), esperado)) {
				return false;
			}
		}

		List<byte[]> pools = layout.poolsLidas(calldata, lido.get());

		if (pools.isEmpty()) {
			return true;
		}

		for (int i = 0; i < reais.size(); i++) {
			if (!Arrays.equals(pools.get(i), reais.get(i).pool())) {
				return false;
			}
		}

		return true;
	}

	private static List<Integer> posicoes(byte[] calldata, byte[] endereco) {

		List<Integer> achados = new ArrayList<>();

		for (int onde = 0; onde + 20 <= calldata.length; onde++) {
			if (Arrays.equals(calldata, onde, onde + 20, endereco, 0, 20)) {
				achados.add(onde);
			}
		}

		return achados;
	}

	// A posição com mais votos (≥ mínimo), fora das já tomadas por outro item; empate vai para a primeira.
	private static Optional<Integer> maisVotada(Map<Integer, Integer> votos, int minimo, List<Integer> tomadas) {
		return votos.entrySet().stream().filter(v -> v.getValue() >= minimo && !tomadas.contains(v.getKey()))
				.max((a, b) -> a.getValue().equals(b.getValue()) ? Integer.compare(b.getKey(), a.getKey())
						: Integer.compare(a.getValue(), b.getValue()))
				.map(Map.Entry::getKey);
	}

	// Quantas amostras a regra reproduz. Compara CONJUNTO de tokens, não ordem: a ordem que o leitor
	// devolve é a da calldata, e quem valida se ela é um caminho possível é o mapa de pools adiante —
	// não o aprendiz.
	private static int confirmados(LayoutAprendido layout, List<ExemploDeMovimento> exemplos) {

		int reproduz = 0;

		for (ExemploDeMovimento exemplo : exemplos) {

			Optional<List<byte[]>> lidos = layout.tokensLidos(exemplo.anunciada().calldata());

			if (lidos.isPresent()
					&& new LinkedHashSet<>(hex(lidos.get())).equals(new LinkedHashSet<>(hex(exemplo.tokensMovidos())))) {
				reproduz++;
			}
		}

		return reproduz;
	}

	// Todos os deslocamentos onde ALGUM token movido aparece, byte a byte.
	private static List<Integer> deslocamentosDosTokens(byte[] calldata, List<byte[]> tokens) {

		List<Integer> achados = new ArrayList<>();

		for (int onde = 0; onde + 20 <= calldata.length; onde++) {

			for (byte[] token : tokens) {

				if (Arrays.equals(calldata, onde, onde + 20, token, 0, 20)) {
					achados.add(onde);
					break;
				}
			}
		}

		return achados;
	}

	// O primeiro deslocamento cuja palavra de 32 bytes é EXATAMENTE o montante observado, ou -1.
	//
	// V1 só aprende palavra de 32 bytes — o montante no formato ABI. O empacotado de tamanho variável
	// precisaria do delimitador do campo, e adivinhar tamanho é projetar torto; esses contratos ficam
	// sem montante e vão para a simulação, que é o honesto.
	private static int deslocamentoDoMontante(byte[] calldata, BigInteger montante) {

		byte[] alvo = palavra(montante);

		for (int onde = 0; onde + 32 <= calldata.length; onde++) {

			if (Arrays.equals(calldata, onde, onde + 32, alvo, 0, 32)) {
				return onde;
			}
		}

		return -1;
	}

	private static byte[] palavra(BigInteger valor) {

		byte[] bytes = valor.toByteArray();
		byte[] palavra = new byte[32];
		int copiar = Math.min(bytes.length, 32);

		System.arraycopy(bytes, bytes.length - copiar, palavra, 32 - copiar, copiar);

		return palavra;
	}

	private static List<String> hex(List<byte[]> valores) {
		return valores.stream().map(valor -> HexFormat.of().formatHex(valor)).toList();
	}
}
