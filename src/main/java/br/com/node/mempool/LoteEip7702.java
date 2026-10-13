package br.com.node.mempool;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// O LOTE de uma conta EIP-7702: a EOA delegada chama a si mesma com `execute((address,uint256,bytes)[])`, e o
// swap de verdade é uma das chamadas de dentro — o roteador não aparece como destino da tx.
//
// Por que existe, com número: na corrida de 26/09 (5 h, servidor AWS), as backruns do mesmo bloco acima de
// US$ 1 somaram ~US$ 2.197, e US$ 1.580 (72%) vieram de 15 gatilhos com este seletor, tx tipo 4 de EOAs
// delegadas a 0x4bd6eceb…; dentro, um approve e um `swapExactTokensForETHSupportingFeeOnTransferTokens` no
// PancakeRouter V2. O decodificador olhava só o destino da tx (a própria EOA) e o seletor do lote: nada.
//
// Só os seletores PROVADOS na cadeia entram em SELETORES. Calldata com um deles e fora do layout ESTOURA —
// é lote que achamos que entendemos e não entendemos, e isso tem de aparecer, não virar "não é swap".
public final class LoteEip7702 {

	// `0x329aa2b9`: execute((address,uint256,bytes)[]) do delegado 0x4bd6eceb16565c9904ea52ac2fd609bce82b6035.
	static final Set<String> SELETORES = Set.of("329aa2b9");

	public record Chamada(byte[] alvo, BigInteger valor, byte[] dados) {
	}

	private LoteEip7702() {
	}

	// Vazio quando o seletor não é de lote conhecido; as chamadas, na ordem, quando é.
	public static Optional<List<Chamada>> desembrulhar(byte[] calldata) {

		if (calldata == null || calldata.length < 4 || !SELETORES.contains(HexFormat.of().formatHex(calldata, 0, 4))) {
			return Optional.empty();
		}

		byte[] corpo = Arrays.copyOfRange(calldata, 4, calldata.length);

		// (address,uint256,bytes)[] é dinâmico: palavra 0 = deslocamento do array; nele, o tamanho e depois um
		// deslocamento por elemento, relativo ao início das cabeças (logo após o tamanho).
		int array = inteiro(corpo, 0, "deslocamento do array");
		int tamanho = inteiro(corpo, array, "tamanho do array");

		if (tamanho <= 0 || tamanho > 64) {
			throw new IllegalArgumentException("lote 7702 com " + tamanho + " chamadas");
		}

		int cabecas = array + 32;
		List<Chamada> chamadas = new ArrayList<>(tamanho);

		for (int i = 0; i < tamanho; i++) {

			int elemento = cabecas + inteiro(corpo, cabecas + 32 * i, "deslocamento da chamada " + i);
			byte[] alvo = Arrays.copyOfRange(palavra(corpo, elemento, "alvo da chamada " + i), 12, 32);
			BigInteger valor = new BigInteger(1, palavra(corpo, elemento + 32, "valor da chamada " + i));
			int dados = elemento + inteiro(corpo, elemento + 64, "deslocamento dos dados da chamada " + i);
			int comprimento = inteiro(corpo, dados, "tamanho dos dados da chamada " + i);

			if (dados + 32 + comprimento > corpo.length) {
				throw new IllegalArgumentException("dados da chamada " + i + " passam do fim do lote");
			}

			chamadas.add(new Chamada(alvo, valor, Arrays.copyOfRange(corpo, dados + 32, dados + 32 + comprimento)));
		}

		return Optional.of(chamadas);
	}

	private static byte[] palavra(byte[] corpo, int inicio, String qual) {

		if (inicio < 0 || inicio + 32 > corpo.length) {
			throw new IllegalArgumentException("lote 7702 truncado lendo " + qual + " em " + inicio);
		}

		return Arrays.copyOfRange(corpo, inicio, inicio + 32);
	}

	private static int inteiro(byte[] corpo, int inicio, String qual) {

		BigInteger valor = new BigInteger(1, palavra(corpo, inicio, qual));

		if (valor.bitLength() > 31) {
			throw new IllegalArgumentException("lote 7702 com " + qual + " absurdo: " + valor);
		}

		return valor.intValueExact();
	}
}
