package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// O veredicto do revert, sem rede — a leitura que o `run-armado` provou estar jogando fora.
//
// MEDIDO: 5 dos 7 envios do run armado caíram em `0x08c379a0`, que é `Error(string)`, o revert COM
// mensagem — e a mensagem (`Pancake: TRANSFER_FAILED`) era descartada, deixando cinco reverts
// idênticos reduzidos a um seletor opaco. Sem a frase não há como saber que o token não se move; e
// sem sabê-lo a MESMA rota de token travado voltou 76 s depois, porque nada do que a cadeia
// respondeu virou conhecimento.
class VeredictoDoRevertTest {

	private static final String TRANSFER_FAILED = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,"
			+ "\"message\":\"[0xe534..345a] execution reverted: Pancake: TRANSFER_FAILED\"}}";

	@Test
	@DisplayName("relay não manda data, mas a frase do message ainda é lida — e nomeia o token travado")
	void fraseDoRelay() {

		RpcDoEnsaio.Veredicto veredicto = RpcDoEnsaio.interpretar(TRANSFER_FAILED);

		assertEquals("revert_sem_dado", veredicto.seletor(), "sem `data` no erro do relay não há seletor a ler");
		assertEquals("Pancake: TRANSFER_FAILED", veredicto.texto());
		assertTrue(Disparo.naoSeMove(veredicto.texto()),
				"a frase sozinha basta para saber que o token não se move");
	}

	@Test
	@DisplayName("Error(string) codificado no data também vira frase (nó que não põe no message)")
	void fraseNoData() {

		String data = erroComTexto("UniSaveV2: TRANSFER_FAILED");

		RpcDoEnsaio.Veredicto veredicto = RpcDoEnsaio.interpretar("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{"
				+ "\"code\":3,\"message\":\"execution reverted\",\"data\":\"0x" + data + "\"}}");

		assertEquals("08c379a0", veredicto.seletor());
		assertEquals("UniSaveV2: TRANSFER_FAILED", veredicto.texto());
		assertTrue(Disparo.naoSeMove(veredicto.texto()));
	}

	@Test
	@DisplayName("EmprestimoNaoV2 é defeito NOSSO — a rota não condena token nenhum")
	void emprestimoNaoV2NaoCondenaToken() {

		RpcDoEnsaio.Veredicto veredicto = RpcDoEnsaio
				.interpretar("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,"
						+ "\"message\":\"[0x56d5..7205] execution reverted\",\"data\":\"0x7978d88b\"}}");

		assertEquals("7978d88b", veredicto.seletor());
		assertFalse(Disparo.naoSeMove(veredicto.texto()),
				"a ponta V3 é conta nossa; condenar token por ela seria jogar a culpa no inocente");
	}

	@Test
	@DisplayName("revert sem dado não inventa frase nem condena token")
	void revertSemDado() {

		RpcDoEnsaio.Veredicto veredicto = RpcDoEnsaio.interpretar("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
				+ "{\"code\":-32000,\"message\":\"[0xd835..51b7] execution reverted\"}}");

		assertEquals("revert_sem_dado", veredicto.seletor());
		assertEquals("", veredicto.texto());
		assertFalse(Disparo.naoSeMove(veredicto.texto()));
	}

	@Test
	@DisplayName("aceitação é ok, e não vira suspeita")
	void aceitacao() {

		RpcDoEnsaio.Veredicto veredicto = RpcDoEnsaio.interpretar("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}");

		assertEquals("ok", veredicto.seletor());
		assertEquals("", veredicto.texto());
	}

	@Test
	@DisplayName("comprimento mentiroso no data não vira frase")
	void truncadoNaoViraFrase() {

		assertTrue(RpcDoEnsaio.textoAbi("08c379a0" + "0".repeat(60)).isEmpty(), "curto demais para ter argumento");
		assertTrue(RpcDoEnsaio.textoAbi("08c379a0" + palavra(32) + palavra(999)).isEmpty(),
				"declara 999 bytes de frase e não tem nenhum");
	}

	// Monta o `data` de um `Error(string)`: seletor + offset + comprimento + bytes com padding.
	private static String erroComTexto(String frase) {

		byte[] bytes = frase.getBytes(StandardCharsets.UTF_8);
		String corpo = HexFormat.of().formatHex(bytes);
		corpo = corpo + "0".repeat((64 - corpo.length() % 64) % 64);

		return "08c379a0" + palavra(32) + palavra(bytes.length) + corpo;
	}

	// Palavra de 32 bytes em hex, sem depender de locale nem de formatar número grande.
	private static String palavra(long valor) {
		return "0".repeat(64 - Long.toHexString(valor).length()) + Long.toHexString(valor);
	}
}
