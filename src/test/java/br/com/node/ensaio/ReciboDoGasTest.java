package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReciboDoGasTest {

	@Test
	@DisplayName("status 0x1 é sucesso, e o gasUsed é hex de gás")
	void sucesso() {

		Optional<RpcDoEnsaio.Recibo> recibo = RpcDoEnsaio.interpretarRecibo(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"0x1\",\"gasUsed\":\"0x493e0\"}}");

		assertTrue(recibo.isPresent());
		assertTrue(recibo.get().sucesso());
		assertEquals(BigInteger.valueOf(300_000), recibo.get().gasUsado());
	}

	@Test
	@DisplayName("preserva o preço efetivo do recibo, inclusive zero")
	void precoEfetivoDoGas() {
		Optional<RpcDoEnsaio.Recibo> recibo = RpcDoEnsaio.interpretarRecibo(
				"{\"result\":{\"status\":\"0x1\",\"gasUsed\":\"0x493e0\",\"effectiveGasPrice\":\"0x0\"}}");

		assertTrue(recibo.isPresent());
		assertEquals(BigInteger.ZERO, recibo.get().precoEfetivoGas());
	}

	@Test
	@DisplayName("status 0x0 é revert — e o gás gasto vem junto, porque ele foi pago")
	void revert() {

		Optional<RpcDoEnsaio.Recibo> recibo = RpcDoEnsaio.interpretarRecibo(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"0x0\",\"gasUsed\":\"0x186a0\"}}");

		assertTrue(recibo.isPresent());
		assertFalse(recibo.get().sucesso(), "revert lido como sucesso esconderia o caso que paga gás sem lucro");
		assertEquals(BigInteger.valueOf(100_000), recibo.get().gasUsado());
	}

	@Test
	@DisplayName("result null (pendente ou bundle não incluído) e erro do nó NÃO são revert: vazio")
	void semRecibo() {

		assertTrue(RpcDoEnsaio.interpretarRecibo("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}").isEmpty());
		assertTrue(
				RpcDoEnsaio.interpretarRecibo("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602}}").isEmpty(),
				"erro do nó não é resposta da cadeia");
		assertTrue(RpcDoEnsaio.interpretarRecibo("isto não é json").isEmpty());
	}
}