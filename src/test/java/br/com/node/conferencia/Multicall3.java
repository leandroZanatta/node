package br.com.node.conferencia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Multicall3 {

	
	
	
	public static final String ENDERECO = "0xca11bde05977b3631167028862be2a173976ca11";

	private static final String AGGREGATE3 = Abi.seletor("aggregate3((address,bool,bytes)[])");

	private static final int PALAVRA = 64;

	private Multicall3() {
	}

	
	
	
	
	public static String codificar(List<Chamada> chamadas) {

		StringBuilder offsets = new StringBuilder();
		StringBuilder tuplas = new StringBuilder();

		long deslocamento = 32L * chamadas.size();

		for (Chamada chamada : chamadas) {

			String tupla = tupla(chamada);

			offsets.append(Abi.palavra(BigInteger.valueOf(deslocamento)));
			tuplas.append(tupla);

			deslocamento += tupla.length() / 2;
		}

		return AGGREGATE3 + Abi.palavra(BigInteger.valueOf(32)) + Abi.palavra(BigInteger.valueOf(chamadas.size()))
				+ offsets + tuplas;
	}

	
	
	
	public static List<Optional<String>> decodificar(String retorno, int quantas) {

		String hex = retorno.substring(2);

		int inicioDoArray = numero(hex, 0) * 2;
		int quantidade = numero(hex, inicioDoArray);
		int inicioDosOffsets = inicioDoArray + PALAVRA;

		if (quantidade != quantas) {
			throw new IllegalStateException(
					"Multicall3 devolveu " + quantidade + " resultados para " + quantas + " chamadas");
		}

		List<Optional<String>> respostas = new ArrayList<>();

		for (int i = 0; i < quantidade; i++) {

			int tupla = inicioDosOffsets + numero(hex, inicioDosOffsets + i * PALAVRA) * 2;
			boolean sucesso = numero(hex, tupla) != 0;
			int dados = tupla + numero(hex, tupla + PALAVRA) * 2;
			int tamanho = numero(hex, dados);

			respostas.add(sucesso && tamanho > 0
					? Optional.of("0x" + hex.substring(dados + PALAVRA, dados + PALAVRA + tamanho * 2))
					: Optional.empty());
		}

		return respostas;
	}

	private static String tupla(Chamada chamada) {

		String dados = semPrefixo(chamada.dados());
		String preenchimento = "0".repeat((PALAVRA - dados.length() % PALAVRA) % PALAVRA);

		return "0".repeat(24) + semPrefixo(chamada.para()).toLowerCase() + Abi.palavra(BigInteger.ONE)
				+ Abi.palavra(BigInteger.valueOf(0x60)) + Abi.palavra(BigInteger.valueOf(dados.length() / 2)) + dados
				+ preenchimento;
	}

	private static int numero(String hex, int posicao) {
		return new BigInteger(hex.substring(posicao, posicao + PALAVRA), 16).intValueExact();
	}

	private static String semPrefixo(String hex) {
		return hex.startsWith("0x") ? hex.substring(2) : hex;
	}
}