package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Eventos;
import br.com.node.nucleo.Par;
import br.com.node.vo.Log;

// O PAREADOR: a tx que vimos anunciada na mempool casa com o efeito que o BLOCO mostrou, pelo id — e é
// isso que transforma o bloco fechado em gabarito de graça (X = calldata anunciada, Y = os tokens e o
// montante que a tx moveu). Sem esse par não existe aprendizado: não há rótulo.
//
// Sem rede: os logs são construídos aqui, no formato exato que a cadeia emite (`Swap` do V2 com quatro
// montantes, `Swap` do V3 com dois assinados).
class PareadorTest {

	private static final byte[] POOL_AB = endereco(9);
	private static final byte[] POOL_BC = endereco(8);
	private static final byte[] A = endereco(1);
	private static final byte[] B = endereco(2);
	private static final byte[] C = endereco(3);

	private static final Function<byte[], Optional<Par>> PARES = pool -> java.util.Arrays.equals(pool, POOL_AB)
			? Optional.of(new Par(A, B))
			: java.util.Arrays.equals(pool, POOL_BC) ? Optional.of(new Par(B, C)) : Optional.empty();

	@Test
	@DisplayName("a tx anunciada casa com o Swap do bloco: tokens movidos e o que entrou")
	void pareia() {

		List<ExemploDeMovimento> exemplos = Pareador.parear(Map.of("0xh1", anunciada(POOL_AB, 1000)),
				Map.of("0xh1", List.of(swapV2(POOL_AB, 1000, 0, 0, 990))), PARES);

		assertEquals(1, exemplos.size(), "uma anunciada com efeito é um exemplo");
		assertEquals(java.util.Set.of(hex(A), hex(B)),
				exemplos.get(0).tokensMovidos().stream().map(PareadorTest::hex).collect(java.util.stream.Collectors.toSet()),
				"o par vem ordenado por endereço, então a comparação é por conjunto");
		assertEquals(BigInteger.valueOf(1000), exemplos.get(0).entradaObservada());
	}

	@Test
	@DisplayName("anunciada e NÃO incluída não vira exemplo (sem rótulo, sem aprendizado)")
	void semEfeitoNaoViraExemplo() {

		assertTrue(Pareador.parear(Map.of("0xh1", anunciada(POOL_AB, 1000)), Map.of(), PARES).isEmpty());
	}

	@Test
	@DisplayName("incluída que não vimos anunciada não vira exemplo (sem calldata, sem X)")
	void semAnuncioNaoViraExemplo() {

		assertTrue(Pareador.parear(Map.of(), Map.of("0xh1", List.of(swapV2(POOL_AB, 1000, 0, 0, 990))), PARES)
				.isEmpty());
	}

	@Test
	@DisplayName("a entrada é a da PRIMEIRA troca; as seguintes são saída da anterior")
	void entradaEhADaPrimeiraTroca() {

		List<ExemploDeMovimento> exemplos = Pareador.parear(Map.of("0xh1", anunciada(POOL_AB, 1000)),
				Map.of("0xh1", List.of(swapV2(POOL_AB, 1000, 0, 0, 990), swapV2(POOL_BC, 990, 0, 0, 980))), PARES);

		assertEquals(BigInteger.valueOf(1000), exemplos.get(0).entradaObservada(),
				"a entrada da tx é a da primeira troca, não a da última");
		assertEquals(3, exemplos.get(0).tokensMovidos().size(), "e junta os tokens das DUAS pools (A, B, C)");
	}

	@Test
	@DisplayName("swap do V3 usa o lado POSITIVO, que é o que entrou na pool")
	void v3UsaOLadoPositivo() {

		List<ExemploDeMovimento> exemplos = Pareador.parear(Map.of("0xh1", anunciada(POOL_AB, 700)),
				Map.of("0xh1", List.of(swapV3(POOL_AB, BigInteger.valueOf(-700), BigInteger.valueOf(700)))), PARES);

		assertEquals(BigInteger.valueOf(700), exemplos.get(0).entradaObservada());
	}

	@Test
	@DisplayName("pool que o catálogo não conhece não inventa token — o exemplo inteiro fica de fora")
	void poolDesconhecidaNaoInventa() {

		assertTrue(Pareador.parear(Map.of("0xh1", anunciada(POOL_AB, 1000)),
				Map.of("0xh1", List.of(swapV2(endereco(77), 1000, 0, 0, 990))), PARES).isEmpty());
	}

	private static TransacaoAnunciada anunciada(byte[] destino, long entrada) {
		return new TransacaoAnunciada(destino, calldata(entrada));
	}

	// [seletor 4][montante 32] — o mínimo para exercitar o pareador; o formato do layout não importa aqui.
	private static byte[] calldata(long entrada) {

		byte[] dados = new byte[36];
		System.arraycopy(HexFormat.of().parseHex("38ed1739"), 0, dados, 0, 4);
		byte[] m = BigInteger.valueOf(entrada).toByteArray();
		System.arraycopy(m, 0, dados, 4 + 32 - m.length, m.length);

		return dados;
	}

	private static Log swapV2(byte[] pool, long a0in, long a1in, long a0out, long a1out) {
		return new Log(pool, List.of(Eventos.SWAP_V2),
				juntar(palavra(a0in), palavra(a1in), palavra(a0out), palavra(a1out)));
	}

	private static Log swapV3(byte[] pool, BigInteger a0, BigInteger a1) {
		return new Log(pool, List.of(Eventos.SWAP_V3), juntar(palavra(a0), palavra(a1)));
	}

	private static byte[] juntar(byte[]... partes) {

		byte[] junto = new byte[partes.length * 32];
		for (int i = 0; i < partes.length; i++) {
			System.arraycopy(partes[i], 0, junto, i * 32, 32);
		}

		return junto;
	}

	private static byte[] palavra(long valor) {
		return palavra(BigInteger.valueOf(valor));
	}

	private static byte[] palavra(BigInteger valor) {

		byte[] bytes = valor.toByteArray();
		byte[] palavra = new byte[32];

		// A EVM escreve o int256 com EXTENSÃO DE SINAL: -700 é 30 bytes 0xFF + 0xFD 0x44, não zeros à
		// esquerda. Sem isto, o helper do teste fabrica um log que a cadeia nunca emite — e o defeito
		// aparece como falha da produção, que estava certa.
		java.util.Arrays.fill(palavra, (byte) (valor.signum() < 0 ? 0xFF : 0x00));

		int copiar = Math.min(bytes.length, 32);
		System.arraycopy(bytes, bytes.length - copiar, palavra, 32 - copiar, copiar);

		return palavra;
	}

	// Endereço pseudo-aleatório DETERMINÍSTICO: com bytes repetidos, uma janela de 20 bytes casaria
	// deslizando (0x0a vinte vezes casa em todo offset de uma sequência de 0x0a) e a descoberta de
	// deslocamento viraria ruído.
	private static byte[] endereco(int semente) {
		byte[] endereco = new byte[20];
		new Random(semente).nextBytes(endereco);
		return endereco;
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
