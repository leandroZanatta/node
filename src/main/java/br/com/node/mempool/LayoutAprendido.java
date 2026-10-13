package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import br.com.node.vo.SwapPendente;

// O layout APRENDIDO de um contrato: onde, na calldata dele, estão os tokens do caminho e o montante de
// entrada. Não é comportamento — é POSIÇÃO, e posição se confere.
//
// Duas consequências do desenho, e as duas são de propósito:
//
//  - `deslocamentoDaEntrada == SEM_MONTANTE` é um resultado legítimo, não um fracasso. MEDIDO na BSC:
//    46% das transações perdidas não trazem o montante na calldata. Para essas o layout existe (o
//    caminho foi aprendido) mas **não projeta local** — e é essa a informação que manda o contrato para
//    a simulação em vez de chutar um número.
//  - `confirmados` é a acurácia de TREINO (quantas amostras a regra reproduz). O gabarito de verdade é
//    o próximo bloco: comparar o que a regra declarou com o que a cadeia moveu é o que diz se ela ainda
//    vale — e regra que parou de reproduzir o bloco tem de EXPIRAR, porque bot troca de contrato e de
//    formato.
//
// ORDENADO (aprendido de pernas): `deslocamentosDeToken` é o CAMINHO na ordem da troca — o primeiro é o
// token que entra —, `deslocamentosDePool` a pool de cada perna quando ela aparece na calldata, e
// `poolPorPar` a pool que este contrato usou para cada par nos exemplos. Não ordenado (formato antigo):
// posições de um CONJUNTO de tokens, na ordem da calldata — que foi o que projetava swap ao contrário.
public record LayoutAprendido(byte[] destino, String seletor, List<Integer> deslocamentosDeToken,
		int deslocamentoDaEntrada, int exemplos, int confirmados, boolean ordenado, List<Integer> deslocamentosDePool,
		java.util.Map<String, String> poolPorPar) {

	public LayoutAprendido(byte[] destino, String seletor, List<Integer> deslocamentosDeToken, int deslocamentoDaEntrada,
			int exemplos, int confirmados) {
		this(destino, seletor, deslocamentosDeToken, deslocamentoDaEntrada, exemplos, confirmados, false, List.of(),
				java.util.Map.of());
	}

	/** O contrato nunca expôs o montante na calldata: projeta-se por simulação, não por aqui. */
	public static final int SEM_MONTANTE = -1;

	private static final int ENDERECO = 20;
	private static final int PALAVRA = 32;

	public LayoutAprendido {

		if (destino == null || destino.length != ENDERECO) {
			throw new IllegalArgumentException("layout sem contrato de 20 bytes");
		}

		if (seletor == null || seletor.length() != 8) {
			throw new IllegalArgumentException("layout sem seletor de 8 hex");
		}

		if (deslocamentosDeToken == null || deslocamentosDeToken.isEmpty()) {
			throw new IllegalArgumentException("layout sem token nenhum não lê nada");
		}

		if (deslocamentoDaEntrada < SEM_MONTANTE) {
			throw new IllegalArgumentException("deslocamento do montante negativo e diferente de SEM_MONTANTE");
		}

		deslocamentosDeToken = List.copyOf(deslocamentosDeToken);
		deslocamentosDePool = deslocamentosDePool == null ? List.of() : List.copyOf(deslocamentosDePool);
		poolPorPar = poolPorPar == null ? java.util.Map.of() : java.util.Map.copyOf(poolPorPar);
	}

	// A chave do par, sem ordem — a mesma pool atende os dois sentidos.
	public static String chaveDoPar(byte[] a, byte[] b) {
		String x = HexFormat.of().formatHex(a);
		String y = HexFormat.of().formatHex(b);
		return x.compareTo(y) < 0 ? x + "-" + y : y + "-" + x;
	}

	// As pools que ESTE layout sabe para o caminho lido: da calldata, se ele aprendeu onde estão; senão do
	// que o contrato usou nos exemplos. Vazio quando alguma perna não se sabe — e aí quem mapeia não chuta.
	public List<byte[]> poolsLidas(byte[] calldata, List<byte[]> caminho) {

		int pernas = caminho.size() - 1;

		if (deslocamentosDePool.size() == pernas) {
			List<byte[]> pools = new ArrayList<>();
			for (int onde : deslocamentosDePool) {
				if (onde < 0 || calldata.length < onde + ENDERECO) {
					return List.of();
				}
				pools.add(Arrays.copyOfRange(calldata, onde, onde + ENDERECO));
			}
			return List.copyOf(pools);
		}

		List<byte[]> pools = new ArrayList<>();
		for (int i = 0; i < pernas; i++) {
			String pool = poolPorPar.get(chaveDoPar(caminho.get(i), caminho.get(i + 1)));
			if (pool == null) {
				return List.of();
			}
			pools.add(HexFormat.of().parseHex(pool));
		}
		return List.copyOf(pools);
	}

	/** Chave do caderno de layouts: um por contrato E método. */
	public String chave() {
		return chave(destino, seletor);
	}

	/** A mesma chave, montada do lado de fora — é como o caderno acha o layout de uma transação. */
	public static String chave(byte[] destino, String seletor) {
		return HexFormat.of().formatHex(destino) + "@" + seletor;
	}

	/** A chave de uma transação anunciada: contrato + os quatro bytes do seletor. */
	public static String chave(byte[] destino, byte[] calldata) {
		return chave(destino, HexFormat.of().formatHex(calldata, 0, 4));
	}

	/** A regra sabe o montante, então ela consegue projetar sozinha, sem rede. */
	public boolean projetaLocalmente() {
		return deslocamentoDaEntrada != SEM_MONTANTE;
	}

	// Lê uma calldata NOVA por este layout. Vazio — nunca exceção — quando não fecha: calldata curta,
	// menos de dois tokens, ou layout que não conhece o montante. Não projetar é melhor que projetar
	// torto, porque projeção torta decide errado no instante em que a decisão vale.
	public Optional<SwapPendente> ler(byte[] calldata) {

		if (!projetaLocalmente() || calldata == null || calldata.length < deslocamentoDaEntrada + PALAVRA) {
			return Optional.empty();
		}

		Optional<List<byte[]>> caminho = tokensLidos(calldata);

		if (caminho.isEmpty() || caminho.get().size() < 2) {
			return Optional.empty();
		}

		BigInteger entrada = new BigInteger(1,
				Arrays.copyOfRange(calldata, deslocamentoDaEntrada, deslocamentoDaEntrada + PALAVRA));

		if (entrada.signum() <= 0) {
			return Optional.empty();
		}

		// O formato antigo (sem pernas) segue lendo como lia — é o dos testes de layout. No node ele não nasce
		// mais: o loop só anota exemplo com pernas em ordem, porque sem direção o swap sai projetado ao contrário.
		if (!ordenado) {
			return Optional.of(new SwapPendente(entrada, caminho.get()));
		}

		return Optional.of(new SwapPendente(entrada, caminho.get(), poolsLidas(calldata, caminho.get())));
	}

	// Os tokens que ESTE layout leria — a mesma leitura de `ler`, sem exigir o montante, para que a
	// contagem de acertos funcione também nos contratos que nunca o expõem.
	public Optional<List<byte[]>> tokensLidos(byte[] calldata) {

		if (calldata == null) {
			return Optional.empty();
		}

		List<byte[]> tokens = new ArrayList<>();

		for (int onde : deslocamentosDeToken) {

			if (onde < 0 || calldata.length < onde + ENDERECO) {
				return Optional.empty();
			}

			tokens.add(Arrays.copyOfRange(calldata, onde, onde + ENDERECO));
		}

		return Optional.of(List.copyOf(tokens));
	}
}
