package br.com.node.rede;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpItem;

class EthInstanteDoBlocoTest {

	private static final long SEGUNDOS = 1_790_314_952L;

	@Test
	@DisplayName("segundos do timestamp + milissegundo do mixHash")
	void segundosMaisMilissegundo() {
		assertEquals(SEGUNDOS * 1000 + 800, Eth.instanteMs(campos(SEGUNDOS, 800)));
	}

	@Test
	@DisplayName("mixHash fora de 0–999 (cadeia sem ms) dá o segundo cheio")
	void mixHashForaDaFaixa() {
		assertEquals(SEGUNDOS * 1000, Eth.instanteMs(campos(SEGUNDOS, 5_000)));
	}

	@Test
	@DisplayName("cabeçalho curto não tem instante")
	void cabecalhoCurto() {
		assertEquals(0, Eth.instanteMs(Rlp.paraLista(Rlp.lista(Rlp.inteiro(1), Rlp.inteiro(2)))));
	}

	private static List<RlpItem> campos(long segundos, long ms) {

		RlpItem[] campos = new RlpItem[15];

		for (int i = 0; i < campos.length; i++) {
			campos[i] = Rlp.bytes(new byte[0]);
		}

		byte[] mistura = new byte[32];
		mistura[30] = (byte) (ms >> 8);
		mistura[31] = (byte) ms;

		campos[11] = Rlp.inteiro(segundos);
		campos[13] = Rlp.bytes(mistura);

		return Rlp.paraLista(Rlp.lista(campos));
	}
}