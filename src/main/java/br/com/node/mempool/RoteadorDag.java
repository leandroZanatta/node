package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import br.com.node.vo.SwapPendente;

// O roteador DAG 0x5994814f…, seletor `0c307f76` (formato do roteador da OKX DEX):
//
//   dagSwapTo(uint256 pedido, address destino,
//             (uint256 deToken, address paraToken, uint256 montante, uint256 minimo, uint256 prazo) base,
//             (address[] adaptadores, address[] repasses, uint256[] rawData, bytes[] extras, uint256 deToken)[] caminhos)
//
// `rawData` = pool nos 160 bits baixos, peso (10.000 = tudo) logo acima, sentido invertido no bit 255. Token em
// `uint256` traz o endereço nos 160 bits baixos. Só a rota LINEAR vira projeção: um caminho por salto, uma pool
// por caminho, peso inteiro — a rota dividida entre pools não se projeta como uma perna só. Quando a "pool" é um
// adaptador (gerente), o `mapearParaPools` recusa por não achar o par no catálogo. MEDIDO no servidor: 24 dos 235
// gatilhos de arbitragens alheias recebidos e não decodificados eram deste contrato.
final class RoteadorDag {

	static final String SELETOR = "0c307f76";

	private static final int PALAVRA = 32;
	private static final int INICIO = 4;
	private static final BigInteger MASCARA_ENDERECO = BigInteger.ONE.shiftLeft(160).subtract(BigInteger.ONE);
	private static final int PESO_INTEIRO = 10_000;
	private static final byte[] WBNB = java.util.HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] NATIVO_EEE = java.util.HexFormat.of().parseHex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

	private RoteadorDag() {
	}

	static Optional<SwapPendente> decodificar(byte[] calldata) {
		try {
			return ler(calldata);
		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("decodificar RoteadorDag", e);
			return Optional.empty();
		}
	}

	private static Optional<SwapPendente> ler(byte[] calldata) {

		if (calldata.length < INICIO + 8 * PALAVRA) {
			return Optional.empty();
		}

		byte[] deToken = token(palavra(calldata, INICIO + 2 * PALAVRA));
		byte[] paraToken = token(palavra(calldata, INICIO + 3 * PALAVRA));
		BigInteger montante = palavra(calldata, INICIO + 4 * PALAVRA);

		int arranjo = INICIO + inteiro(palavra(calldata, INICIO + 7 * PALAVRA));
		int caminhos = inteiro(palavra(calldata, arranjo));

		if (caminhos < 1 || caminhos > 8 || montante.signum() <= 0) {
			return Optional.empty();
		}

		List<byte[]> caminho = new ArrayList<>();
		List<byte[]> pools = new ArrayList<>();

		for (int i = 0; i < caminhos; i++) {

			int tupla = arranjo + PALAVRA + inteiro(palavra(calldata, arranjo + PALAVRA + i * PALAVRA));
			int raw = tupla + inteiro(palavra(calldata, tupla + 2 * PALAVRA));

			if (inteiro(palavra(calldata, raw)) != 1) {
				return Optional.empty();
			}

			BigInteger dado = palavra(calldata, raw + PALAVRA);
			int peso = dado.shiftRight(160).and(BigInteger.valueOf(0xffff)).intValue();

			if (peso != PESO_INTEIRO) {
				return Optional.empty();
			}

			byte[] de = token(palavra(calldata, tupla + 4 * PALAVRA));

			if (i == 0 && !Arrays.equals(de, deToken)) {
				return Optional.empty();
			}

			caminho.add(de);
			pools.add(endereco(dado));
		}

		caminho.add(paraToken);

		return Optional.of(new SwapPendente(montante, caminho, pools));
	}

	// O BNB nativo (0x0 ou 0xeeee…) é o WBNB para o catálogo.
	private static byte[] token(BigInteger valor) {
		byte[] endereco = endereco(valor);
		return Arrays.equals(endereco, new byte[20]) || Arrays.equals(endereco, NATIVO_EEE) ? WBNB : endereco;
	}

	private static byte[] endereco(BigInteger valor) {
		byte[] cru = valor.and(MASCARA_ENDERECO).toByteArray();
		byte[] endereco = new byte[20];
		int n = Math.min(20, cru.length);
		System.arraycopy(cru, cru.length - n, endereco, 20 - n, n);
		return endereco;
	}

	private static BigInteger palavra(byte[] calldata, int base) {
		return new BigInteger(1, Arrays.copyOfRange(calldata, base, base + PALAVRA));
	}

	private static int inteiro(BigInteger valor) {
		return valor.intValueExact();
	}
}
