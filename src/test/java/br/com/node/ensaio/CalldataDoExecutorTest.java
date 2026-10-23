package br.com.node.ensaio;
import br.com.node.execucao.disparo.*;
import br.com.node.execucao.bundle.*;
import br.com.node.auditoria.registro.*;
import br.com.node.baixa.rpc.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalldataDoExecutorTest {

	private static final String P1 = "11".repeat(20);
	private static final String P2 = "44".repeat(20);
	private static final String A = "22".repeat(20);
	private static final String B = "33".repeat(20);

	/** Byte a byte o que o `cast` produziu para a mesma entrada. */
	private static final String REFERENCIA = "a10f8882"
			+ "0000000000000000000000000000000000000000000000000000000000000060"
			+ "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
			+ "0000000000000000000000000000000000000000000000000000000000001388"
			+ "0000000000000000000000000000000000000000000000000000000000000002"
			+ "0000000000000000000000001111111111111111111111111111111111111111"
			+ "0000000000000000000000002222222222222222222222222222222222222222"
			+ "0000000000000000000000003333333333333333333333333333333333333333"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "0000000000000000000000000000000000000000000000000000000000000019"
			+ "0000000000000000000000004444444444444444444444444444444444444444"
			+ "0000000000000000000000003333333333333333333333333333333333333333"
			+ "0000000000000000000000002222222222222222222222222222222222222222"
			+ "0000000000000000000000000000000000000000000000000000000000000001"
			+ "000000000000000000000000000000000000000000000000000000000000001e";

	private static List<Salto> rota() {
		return List.of(new Salto(P1, A, B, false, 25), new Salto(P2, B, A, true, 30));
	}

	@Test
	@DisplayName("a calldata bate byte a byte com a do cast")
	void bateComOCast() {

		byte[] nossa = AbiDoExecutor.executar(rota(), new BigInteger("1000000000000000000"), BigInteger.valueOf(5_000));

		assertEquals(REFERENCIA, java.util.HexFormat.of().formatHex(nossa),
				"ABI divergente: o contrato leria a rota do lugar errado e reverteria sem motivo aparente");
	}

	@Test
	@DisplayName("o seletor é o da assinatura com a tupla expandida, e as duas entradas diferem")
	void oSeletorEhOCerto() {

		assertEquals("a10f8882", java.util.HexFormat.of().formatHex(AbiDoExecutor.EXECUTAR));

		assertTrue(!java.util.Arrays.equals(AbiDoExecutor.EXECUTAR, AbiDoExecutor.EXECUTAR_COM_EMPRESTIMO),
				"as duas entradas do contrato têm de ser distinguíveis");
	}

	@Test
	@DisplayName("o deslocamento da rota aponta para depois dos três parâmetros")
	void oDeslocamentoEhDepoisDaCabeca() {

		byte[] c = AbiDoExecutor.executar(rota(), BigInteger.ONE, BigInteger.ZERO);

		assertEquals(0x60, new BigInteger(1, java.util.Arrays.copyOfRange(c, 4, 36)).intValue());
		assertEquals(2, new BigInteger(1, java.util.Arrays.copyOfRange(c, 4 + 96, 4 + 128)).intValue(),
				"logo depois da cabeça vem o comprimento da rota");
	}

	@Test
	@DisplayName("o tamanho cresce cinco palavras por perna")
	void cadaPernaOcupaCincoPalavras() {

		int umaPerna = AbiDoExecutor.executar(List.of(new Salto(P1, A, B, false, 25)), BigInteger.ONE,
				BigInteger.ZERO).length;

		assertEquals(4 + 32 * 3 + 32 + 32 * 5, umaPerna);
		assertEquals(umaPerna + 32 * 5, AbiDoExecutor.executar(rota(), BigInteger.ONE, BigInteger.ZERO).length);
	}

	@Test
	@DisplayName("o maior uint256 possível cabe sem truncar, e acima dele se recusa")
	void uint256NoTalo() {

		BigInteger maximo = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

		byte[] c = AbiDoExecutor.executar(List.of(new Salto(P1, A, B, false, 25)), maximo, BigInteger.ZERO);

		assertEquals(maximo, new BigInteger(1, java.util.Arrays.copyOfRange(c, 36, 68)));

		assertThrows(IllegalArgumentException.class,
				() -> AbiDoExecutor.executar(rota(), BigInteger.TWO.pow(256), BigInteger.ZERO),
				"acima de 256 bits não se trunca, se recusa");
	}

	@Test
	@DisplayName("rota vazia não vira chamada")
	void rotaVaziaNaoVira() {
		assertThrows(IllegalArgumentException.class,
				() -> AbiDoExecutor.executar(List.of(), BigInteger.ONE, BigInteger.ZERO));
	}

	@Test
	@DisplayName("inverter entra e sai muda a calldata: o sentido está nos bytes")
	void oSentidoEstaNosBytes() {

		byte[] ida = AbiDoExecutor.executar(List.of(new Salto(P1, A, B, false, 25)), BigInteger.ONE, BigInteger.ZERO);
		byte[] volta = AbiDoExecutor.executar(List.of(new Salto(P1, B, A, false, 25)), BigInteger.ONE, BigInteger.ZERO);

		assertTrue(!java.util.Arrays.equals(ida, volta));
	}

	@Test
	@DisplayName("taxa fora do uint16 e mesma ponta dos dois lados não constroem travessia")
	void validacoesDaTravessia() {

		assertThrows(IllegalArgumentException.class, () -> new Salto(P1, A, B, false, 65_536));
		assertThrows(IllegalArgumentException.class, () -> new Salto(P1, A, B, false, -1));
		assertThrows(IllegalArgumentException.class, () -> new Salto(P1, A, A, false, 25));
	}

	
	
	
	
	
	
	
	
	
	private static final String REFERENCIA_V2 = "8478b251"
			+ "0000000000000000000000000000000000000000000000000000000000000060"
			+ "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
			+ "0000000000000000000000000000000000000000000000000000000000003039"
			+ "0000000000000000000000000000000000000000000000000000000000000002"
			+ "0000000000000000000000000000000000000000000000000000000000000040"
			+ "0000000000000000000000000000000000000000000000000000000000000120"
			+ "00000000000000000000000016b9a82891338f9ba80e2d6970fdda79d1eb0dae"
			+ "000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"
			+ "00000000000000000000000055d398326f99059ff775485246999027b3197955"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "0000000000000000000000000000000000000000000000000000000000000019"
			+ "00000000000000000000000000000000000000000000000000000000000000c0"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "000000000000000000000000a0ffb9c1ce1fe56963b0321b32e7a0302114058b"
			+ "00000000000000000000000055d398326f99059ff775485246999027b3197955"
			+ "000000000000000000000000bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"
			+ "0000000000000000000000000000000000000000000000000000000000000003"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000000000000000000000000000000000000000000000c0"
			+ "00000000000000000000000000000000000000000000000000000000000000c0"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000000055d398326f99059ff775485246999027b3197955"
			+ "0000000000000000000000000000000000000000000000000000000000000000"
			+ "000000000000000000000000a0ffb9c1ce1fe56963b0321b32e7a0302114058b"
			+ "0000000000000000000000000000000000000000000000000000000000000043"
			+ "0000000000000000000000000000000000000000000000000000000000010000";

	@Test
	@DisplayName("a calldata da versão 2, com a chave de uma pool de gerente, bate byte a byte com a do cast")
	void versao2BateComOCast() {

		br.com.node.transformacao.catalogo.ChaveDaPool chave = new br.com.node.transformacao.catalogo.ChaveDaPool(
				br.com.node.transformacao.catalogo.Eventos.GERENTE_INFINITY_CL,
				java.util.HexFormat.of().parseHex("d37aa0f0d66ad670279f6b89325c88bdff17d0265144762fb01f54fca9779944"),
				new byte[20], java.util.HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955"), 67, 1,
				new byte[20], 0, 1L);

		assertTrue(chave.confere(), "a chave reconstruída tem de dar o poolId real");

		String wbnb = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
		String usdt = "55d398326f99059ff775485246999027b3197955";
		List<Salto> rota = List.of(new Salto("16b9a82891338f9ba80e2d6970fdda79d1eb0dae", wbnb, usdt, false, 25),
				new Salto("a0ffb9c1ce1fe56963b0321b32e7a0302114058b", usdt, wbnb, Salto.TIPO_INFINITY_CL, 0,
						chave.abi()));

		byte[] nossa = AbiDoExecutor.executarComEmprestimoV2(rota, new BigInteger("1000000000000000000"),
				BigInteger.valueOf(12_345));

		assertEquals(REFERENCIA_V2, java.util.HexFormat.of().formatHex(nossa));
	}
}