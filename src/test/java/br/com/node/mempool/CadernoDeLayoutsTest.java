package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.vo.SwapPendente;

class CadernoDeLayoutsTest {

	private static final byte[] SELETOR = HexFormat.of().parseHex("1a2b3c4d");

	private static final byte[] CONTRATO = endereco(70);
	private static final byte[] OUTRO = endereco(71);
	private static final byte[] A = endereco(1);
	private static final byte[] B = endereco(2);
	private static final byte[] C = endereco(3);

	private static byte[] calldata(long entrada, byte[]... tokens) {

		byte[] dados = new byte[4 + 32 + 20 * tokens.length];
		System.arraycopy(SELETOR, 0, dados, 0, 4);

		byte[] m = BigInteger.valueOf(entrada).toByteArray();
		System.arraycopy(m, 0, dados, 4 + 32 - m.length, m.length);

		for (int i = 0; i < tokens.length; i++) {
			System.arraycopy(tokens[i], 0, dados, 4 + 32 + 20 * i, 20);
		}

		return dados;
	}

	private static byte[] endereco(int semente) {
		byte[] endereco = new byte[20];
		new Random(semente).nextBytes(endereco);
		return endereco;
	}

	private static ExemploDeMovimento exemplo(long entrada, byte[]... tokens) {
		return new ExemploDeMovimento(new TransacaoAnunciada(CONTRATO, calldata(entrada, tokens)), List.of(tokens),
				BigInteger.valueOf(entrada));
	}

	@Test
	@DisplayName("contrato novo entra cego; após amostras mínimas, lê local — sem rede")
	void aprendeDepoisDasAmostras() {

		CadernoDeLayouts caderno = new CadernoDeLayouts(3, 24);

		assertTrue(caderno.ler(CONTRATO, calldata(1, A, B)).isEmpty(), "sem amostra não há layout");

		caderno.anotar(exemplo(10, A, B));
		caderno.anotar(exemplo(20, A, B));
		caderno.revisar();

		assertTrue(caderno.ler(CONTRATO, calldata(2, A, B)).isEmpty(), "duas amostras não fazem consenso de três");

		caderno.anotar(exemplo(30, A, B));
		caderno.revisar();

		Optional<SwapPendente> lido = caderno.ler(CONTRATO, calldata(999, A, B));

		assertTrue(lido.isPresent(), "atingido o consenso, a leitura é local");
		assertEquals(BigInteger.valueOf(999), lido.get().entra());
		assertTrue(caderno.ler(OUTRO, calldata(999, A, B)).isEmpty(), "contrato sem amostra continua cego");
	}

	@Test
	@DisplayName("acurácia viva: regra que erra contra o bloco EXPIRA — e o caderno volta a aprender")
	void expiraQuandoParaDeAcertar() {

		CadernoDeLayouts caderno = new CadernoDeLayouts(3, 24);

		for (long v : new long[] { 10, 20, 30 }) {
			caderno.anotar(exemplo(v, A, B));
		}
		caderno.revisar();

		assertTrue(caderno.ler(CONTRATO, calldata(999, A, B)).isPresent(), "a regra nasce lendo");

		caderno.conferir(CONTRATO, calldata(999, A, B), List.of(A, C));
		assertTrue(caderno.ler(CONTRATO, calldata(999, A, B)).isPresent(), "um erro não derruba a regra");

		caderno.conferir(CONTRATO, calldata(999, A, B), List.of(A, C));
		assertTrue(caderno.ler(CONTRATO, calldata(999, A, B)).isEmpty(),
				"dois erros contra o gabarito expiram — o bloco é quem julga");
	}

	@Test
	@DisplayName("acerto confirmado mantém a regra viva e conta no placar")
	void acertoMantemViva() {

		CadernoDeLayouts caderno = new CadernoDeLayouts(3, 24);

		for (long v : new long[] { 10, 20, 30 }) {
			caderno.anotar(exemplo(v, A, B));
		}
		caderno.revisar();

		for (int i = 0; i < 5; i++) {
			caderno.conferir(CONTRATO, calldata(9 + i, A, B), List.of(A, B));
		}

		assertTrue(caderno.ler(CONTRATO, calldata(9, A, B)).isPresent(), "cinco acertos não expiram nada");

		Map<String, long[]> placar = caderno.placar();
		assertEquals(1, placar.size(), "um contrato aprendido, um placar");
		long[] conta = placar.values().iterator().next();
		assertEquals(5, conta[0], "cinco declarações");
		assertEquals(5, conta[1], "cinco confirmações");
	}

	@Test
	@DisplayName("conferir sem layout não inventa placar")
	void conferirSemLayoutNaoInventa() {

		CadernoDeLayouts caderno = new CadernoDeLayouts(3, 24);
		assertTrue(caderno.placar().isEmpty());
		caderno.conferir(CONTRATO, calldata(1, A, B), List.of(A, B));
		assertTrue(caderno.placar().isEmpty(), "sem regra não há o que conferir");
	}

	@Test
	@DisplayName("layout sem montante: não projeta local — mas a acurácia conta os tokens")
	void layoutSemMontanteNaoProjetaLocal() {

		/*
		 * Token carregado mas amountIn não aparece como palavra de 32 bytes. O caderno
		 * aprende o caminho, mas `ler` volta vazio → vai para simulação. A contagem de
		 * acertos continua, comparando só os tokens.
		 */

		CadernoDeLayouts caderno = new CadernoDeLayouts(3, 24);
		byte[] semMontante = endereco(88);

		byte[] dados = new byte[4 + 32 + 20 * 2];
		System.arraycopy(SELETOR, 0, dados, 0, 4); 
		System.arraycopy(A, 0, dados, 36, 20);
		System.arraycopy(B, 0, dados, 56, 20);

		TransacaoAnunciada anunciada = new TransacaoAnunciada(semMontante, dados);
		caderno.anotar(new ExemploDeMovimento(anunciada, List.of(A, B), BigInteger.valueOf(42)));
		caderno.anotar(new ExemploDeMovimento(anunciada, List.of(A, B), BigInteger.valueOf(42)));
		caderno.anotar(new ExemploDeMovimento(anunciada, List.of(A, B), BigInteger.valueOf(42)));
		caderno.revisar();

		assertTrue(caderno.ler(semMontante, dados).isEmpty(),
				"layout sem montante não projeta local — vai para simulação");

		caderno.conferir(semMontante, dados, List.of(A, B));
		Map<String, long[]> placar = caderno.placar();
		assertEquals(1, placar.size(), "acurácia conta mesmo sem montante");
		assertEquals(1, placar.values().iterator().next()[0], "uma declaração registrada");
	}
}