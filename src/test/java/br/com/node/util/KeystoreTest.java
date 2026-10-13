package br.com.node.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeystoreTest {

	private static final byte[] SEGREDO = HexFormat.of()
			.parseHex("4c0be6b1a2f3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7081920304050");

	@Test
	@DisplayName("cifrar e decifrar com a senha certa devolve o segredo intacto")
	void ida_e_volta() {

		String keystore = Keystore.cifrar(SEGREDO, "senha-do-operador".toCharArray());

		assertArrayEquals(SEGREDO, Keystore.decifrar(keystore, "senha-do-operador".toCharArray()));
	}

	@Test
	@DisplayName("senha errada não decifra — o tag do GCM barra, não devolve lixo")
	void senha_errada_barra() {

		String keystore = Keystore.cifrar(SEGREDO, "certa".toCharArray());

		assertThrows(IllegalStateException.class, () -> Keystore.decifrar(keystore, "errada".toCharArray()));
	}

	@Test
	@DisplayName("dois keystores da mesma chave e senha diferem (sal e iv aleatórios)")
	void nao_deterministico() {

		assertNotEquals(Keystore.cifrar(SEGREDO, "s".toCharArray()), Keystore.cifrar(SEGREDO, "s".toCharArray()));
	}
}
