package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Eventos;
import br.com.node.nucleo.Passo;
import br.com.node.vo.Log;
import br.com.node.vo.SwapPendente;

class AprendizDeLayoutTest {

	private static final byte[] CONTRATO = endereco(70);
	private static final byte[] POOL = endereco(80);
	private static final byte[] A = endereco(1);
	private static final byte[] B = endereco(2);

	private static final byte[] SELETOR = HexFormat.of().parseHex("1a2b3c4d");

	@Test
	@DisplayName("aprende ONDE estão os tokens e o montante, e relê uma calldata nova do mesmo formato")
	void aprendeERele() {

		Optional<LayoutAprendido> layout = AprendizDeLayout
				.aprender(List.of(exemplo(1000, A, B), exemplo(2000, A, B), exemplo(3000, A, B)), 3);

		assertTrue(layout.isPresent(), "três amostras no mesmo formato têm de virar regra");
		assertEquals(List.of(36, 56), layout.get().deslocamentosDeToken(), "os dois tokens, em ordem de offset");
		assertEquals(4, layout.get().deslocamentoDaEntrada(), "o montante está logo depois do seletor");
		assertTrue(layout.get().projetaLocalmente(), "com o montante na calldata, projeta local");
		assertEquals(3, layout.get().confirmados(), "a regra reproduz as três amostras que a ensinaram");

		Optional<SwapPendente> lido = layout.get().ler(calldata(7777, A, B));

		assertTrue(lido.isPresent(), "calldata de mesmo formato tem de ser lida sem RPC");
		assertEquals(BigInteger.valueOf(7777), lido.get().entra());
		assertEquals(2, lido.get().caminho().size());
		assertEquals(hex(A), hex(lido.get().caminho().get(0)));
		assertEquals(hex(B), hex(lido.get().caminho().get(1)));
	}

	@Test
	@DisplayName("formato instável não vira regra (fail closed)")
	void instavelNaoViraRegra() {

		List<ExemploDeMovimento> exemplos = List.of(exemplo(1000, A, B), exemploDeslocado(2000, A, B),
				exemplo(3000, A, B));

		assertTrue(AprendizDeLayout.aprender(exemplos, 3).isEmpty(),
				"deslocamento que não se repete é ruído, não layout");
	}

	@Test
	@DisplayName("amostra pequena não vira regra")
	void amostraPequenaNaoViraRegra() {

		assertTrue(AprendizDeLayout.aprender(List.of(exemplo(1000, A, B)), 3).isEmpty());
	}

	@Test
	@DisplayName("sem o montante na calldata a regra existe, mas NÃO projeta local — vai para a simulação")
	void semMontanteNaoProjetaLocal() {

		Optional<LayoutAprendido> layout = AprendizDeLayout
				.aprender(List.of(exemploSemMontante(A, B), exemploSemMontante(A, B), exemploSemMontante(A, B)), 3);

		assertTrue(layout.isPresent(), "o CAMINHO foi aprendido mesmo sem montante");
		assertEquals(List.of(4, 24), layout.get().deslocamentosDeToken());
		assertFalse(layout.get().projetaLocalmente(), "sem montante não há projeção local");
		assertTrue(layout.get().ler(calldata(9, A, B)).isEmpty(), "e ler não inventa montante");
	}

	@Test
	@DisplayName("misturar contratos ou métodos é recusado — um layout é de UM contrato e UM método")
	void misturaEhRecusada() {

		List<ExemploDeMovimento> exemplos = List.of(exemplo(1000, A, B), outroContrato(2000, A, B),
				exemplo(3000, A, B));

		assertTrue(AprendizDeLayout.aprender(exemplos, 3).isEmpty(),
				"o mesmo formato em contratos diferentes não é um formato");
	}

	@Test
	@DisplayName("calldata curta demais devolve vazio, não estoura")
	void calldataCurtaNaoEstoura() {

		LayoutAprendido layout = AprendizDeLayout
				.aprender(List.of(exemplo(1, A, B), exemplo(2, A, B), exemplo(3, A, B)), 3).orElseThrow();

		assertTrue(layout.ler(new byte[0]).isEmpty());
		assertTrue(layout.ler(new byte[40]).isEmpty());
	}

	@Test
	@DisplayName("ponta a ponta: o layout aprendido alimenta a MESMA matemática da projeção por ABI")
	void alimentaAMesmaMatematica() {

		LayoutAprendido layout = AprendizDeLayout
				.aprender(List.of(exemplo(10, A, B), exemplo(20, A, B), exemplo(30, A, B)), 3).orElseThrow();

		SwapPendente lido = layout.ler(calldata(1000, A, B)).orElseThrow();

		Catalogo catalogo = new Catalogo();
		catalogo.aprenderPar(POOL, List.of(A, B));
		catalogo.aplicarSync(new Log(POOL, List.of(Eventos.SYNC_V2), juntar(palavra(100_000), palavra(200_000))), 1);

		Optional<List<Passo>> passos = Roteamento.projetar(lido, catalogo, pool -> Optional.of(2500));

		assertTrue(passos.isPresent(), "o que o aprendiz leu tem de projetar pelo caminho que já existe");
		assertTrue(passos.get().get(0).saida().signum() > 0, "e a curva tem de devolver saída");
	}

	private static ExemploDeMovimento exemplo(long entrada, byte[]... tokens) {
		return new ExemploDeMovimento(new TransacaoAnunciada(CONTRATO, calldata(entrada, tokens)), List.of(tokens),
				BigInteger.valueOf(entrada));
	}

	
	
	private static ExemploDeMovimento exemploDeslocado(long entrada, byte[]... tokens) {
		return new ExemploDeMovimento(new TransacaoAnunciada(CONTRATO, montar(32, entrada, tokens)), List.of(tokens),
				BigInteger.valueOf(entrada));
	}

	
	
	private static ExemploDeMovimento exemploSemMontante(byte[]... tokens) {
		return new ExemploDeMovimento(new TransacaoAnunciada(CONTRATO, caminhoPuro(tokens)), List.of(tokens),
				BigInteger.valueOf(987_654_321_123L));
	}

	private static ExemploDeMovimento outroContrato(long entrada, byte[]... tokens) {
		return new ExemploDeMovimento(new TransacaoAnunciada(endereco(71), calldata(entrada, tokens)), List.of(tokens),
				BigInteger.valueOf(entrada));
	}

	private static byte[] calldata(long entrada, byte[]... tokens) {
		return montar(0, entrada, tokens);
	}

	
	private static byte[] montar(int enchimento, long entrada, byte[]... tokens) {

		byte[] dados = new byte[4 + enchimento + 32 + 20 * tokens.length];
		System.arraycopy(SELETOR, 0, dados, 0, 4);

		byte[] m = BigInteger.valueOf(entrada).toByteArray();
		System.arraycopy(m, 0, dados, 4 + enchimento + 32 - m.length, m.length);

		for (int i = 0; i < tokens.length; i++) {
			System.arraycopy(tokens[i], 0, dados, 4 + enchimento + 32 + 20 * i, 20);
		}

		return dados;
	}

	
	private static byte[] caminhoPuro(byte[]... tokens) {

		byte[] dados = new byte[4 + 20 * tokens.length];
		System.arraycopy(SELETOR, 0, dados, 0, 4);

		for (int i = 0; i < tokens.length; i++) {
			System.arraycopy(tokens[i], 0, dados, 4 + 20 * i, 20);
		}

		return dados;
	}

	private static byte[] juntar(byte[]... partes) {

		byte[] junto = new byte[partes.length * 32];

		for (int i = 0; i < partes.length; i++) {
			System.arraycopy(partes[i], 0, junto, i * 32, 32);
		}

		return junto;
	}

	private static byte[] palavra(long valor) {

		byte[] bytes = BigInteger.valueOf(valor).toByteArray();
		byte[] palavra = new byte[32];
		System.arraycopy(bytes, 0, palavra, 32 - bytes.length, bytes.length);

		return palavra;
	}

	
	
	
	private static byte[] endereco(int semente) {
		byte[] endereco = new byte[20];
		new Random(semente).nextBytes(endereco);
		return endereco;
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}