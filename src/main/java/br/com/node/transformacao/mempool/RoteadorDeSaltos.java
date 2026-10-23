package br.com.node.transformacao.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import br.com.node.transformacao.catalogo.Eventos;
import br.com.node.util.Keccak;
import br.com.node.vo.SwapPendente;

public final class RoteadorDeSaltos {

	public static final String SELETOR = "4d819a2a";

	private static final int PALAVRA = 32;
	private static final int INICIO = 4;
	private static final int CAMPOS_DO_SALTO = 10;
	private static final byte[] NATIVO = new byte[20];
	private static final byte[] WBNB = java.util.HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	private RoteadorDeSaltos() {
	}

	public static Optional<SwapPendente> decodificar(byte[] calldata) {
		try {
			return ler(calldata);
		} catch (RuntimeException e) {

			return Optional.empty();
		}
	}

	private static Optional<SwapPendente> ler(byte[] calldata) {

		if (calldata.length < INICIO + 5 * PALAVRA) {
			return Optional.empty();
		}

		BigInteger montante = palavra(calldata, INICIO + 2 * PALAVRA);
		int arranjo = INICIO + inteiro(palavra(calldata, INICIO));
		int saltos = inteiro(palavra(calldata, arranjo));

		if (saltos < 1 || saltos > 8 || montante.signum() <= 0) {
			return Optional.empty();
		}

		List<byte[]> caminho = new ArrayList<>();
		List<byte[]> pools = new ArrayList<>();

		for (int i = 0; i < saltos; i++) {

			int salto = arranjo + PALAVRA + inteiro(palavra(calldata, arranjo + PALAVRA + i * PALAVRA));
			int tipo = inteiro(palavra(calldata, salto));
			byte[] entra = endereco(calldata, salto + PALAVRA);
			byte[] sai = endereco(calldata, salto + 2 * PALAVRA);

			Optional<byte[]> pool = switch (tipo) {
			case 0, 1 -> Optional.of(endereco(calldata, salto + 3 * PALAVRA));
			case 2, 3 -> Optional.of(poolId(calldata, salto, tipo, entra, sai));
			default -> Optional.empty();
			};

			if (pool.isEmpty()) {
				return Optional.empty();
			}

			if (i == 0) {
				caminho.add(emWbnb(entra));
			}
			caminho.add(emWbnb(sai));
			pools.add(pool.get());
		}

		return Optional.of(new SwapPendente(montante, caminho, pools));
	}

	private static byte[] poolId(byte[] calldata, int salto, int tipo, byte[] entra, byte[] sai) {

		boolean crescente = new BigInteger(1, entra).compareTo(new BigInteger(1, sai)) < 0;
		byte[] moeda0 = crescente ? entra : sai;
		byte[] moeda1 = crescente ? sai : entra;

		byte[] taxa = Arrays.copyOfRange(calldata, salto + 4 * PALAVRA, salto + 5 * PALAVRA);
		byte[] espacamento = Arrays.copyOfRange(calldata, salto + 5 * PALAVRA, salto + 6 * PALAVRA);
		byte[] hooks = Arrays.copyOfRange(calldata, salto + 6 * PALAVRA, salto + 7 * PALAVRA);
		byte[] parametros = Arrays.copyOfRange(calldata, salto + 9 * PALAVRA, salto + CAMPOS_DO_SALTO * PALAVRA);

		byte[] chave = tipo == 2 ? concatenar(emPalavra(moeda0), emPalavra(moeda1), taxa, espacamento, hooks)
				: concatenar(emPalavra(moeda0), emPalavra(moeda1), hooks, emPalavra(Eventos.GERENTE_INFINITY_CL), taxa,
						parametros);

		return Keccak.hash(chave);
	}

	private static byte[] emWbnb(byte[] token) {
		return Arrays.equals(token, NATIVO) ? WBNB : token;
	}

	private static byte[] emPalavra(byte[] endereco) {
		byte[] palavra = new byte[PALAVRA];
		System.arraycopy(endereco, 0, palavra, PALAVRA - endereco.length, endereco.length);
		return palavra;
	}

	private static byte[] concatenar(byte[]... partes) {
		byte[] saida = new byte[partes.length * PALAVRA];
		for (int i = 0; i < partes.length; i++) {
			System.arraycopy(partes[i], 0, saida, i * PALAVRA, PALAVRA);
		}
		return saida;
	}

	private static byte[] endereco(byte[] calldata, int base) {
		return Arrays.copyOfRange(calldata, base + 12, base + PALAVRA);
	}

	private static BigInteger palavra(byte[] calldata, int base) {
		return new BigInteger(1, Arrays.copyOfRange(calldata, base, base + PALAVRA));
	}

	private static int inteiro(BigInteger valor) {
		return valor.intValueExact();
	}
}