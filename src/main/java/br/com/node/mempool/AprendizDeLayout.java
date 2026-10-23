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

public final class AprendizDeLayout {

	private AprendizDeLayout() {
	}

	public static Optional<LayoutAprendido> aprender(List<ExemploDeMovimento> exemplos, int minimo) {

		if (exemplos == null || minimo < 1 || exemplos.size() < minimo) {
			return Optional.empty();
		}

		byte[] destino = exemplos.get(0).anunciada().destino();
		String seletor = exemplos.get(0).anunciada().seletor();

		for (ExemploDeMovimento exemplo : exemplos) {

			if (!Arrays.equals(exemplo.anunciada().destino(), destino)
					|| !exemplo.anunciada().seletor().equals(seletor)) {
				return Optional.empty();
			}
		}

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

	private static Optional<LayoutAprendido> aprenderOrdenado(List<ExemploDeMovimento> exemplos, int minimo,
			byte[] destino, String seletor) {

		int pernas = exemplos.get(0).pernas().size();

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

	private static Optional<Integer> maisVotada(Map<Integer, Integer> votos, int minimo, List<Integer> tomadas) {
		return votos.entrySet().stream().filter(v -> v.getValue() >= minimo && !tomadas.contains(v.getKey()))
				.max((a, b) -> a.getValue().equals(b.getValue()) ? Integer.compare(b.getKey(), a.getKey())
						: Integer.compare(a.getValue(), b.getValue()))
				.map(Map.Entry::getKey);
	}

	private static int confirmados(LayoutAprendido layout, List<ExemploDeMovimento> exemplos) {

		int reproduz = 0;

		for (ExemploDeMovimento exemplo : exemplos) {

			Optional<List<byte[]>> lidos = layout.tokensLidos(exemplo.anunciada().calldata());

			if (lidos.isPresent() && new LinkedHashSet<>(hex(lidos.get()))
					.equals(new LinkedHashSet<>(hex(exemplo.tokensMovidos())))) {
				reproduz++;
			}
		}

		return reproduz;
	}

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