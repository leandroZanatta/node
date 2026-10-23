package br.com.node.ensaio;
import br.com.node.execucao.disparo.*;
import br.com.node.execucao.bundle.*;
import br.com.node.auditoria.registro.*;
import br.com.node.baixa.rpc.*;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.transformacao.mempool.TransacaoPendente;
import br.com.node.util.Keccak;
import br.com.node.util.Secp256k1;

class AssinadorDeTransacaoTest {

	
	
	private static final BigInteger PRIVADA = new BigInteger(
			"4c0be6b1a2f3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7081920304050", 16);

	@Test
	@DisplayName("assinar e recuperar devolve o endereço da chave que assinou")
	void ida_e_volta() {

		byte[] esperado = Arrays.copyOfRange(Keccak.hash(Secp256k1.id(PRIVADA)), 12, 32);

		byte[] para = HexFormat.of().parseHex("cdf83fc9c7c62e3545c4891b7e26e4d6a27c97e5");
		byte[] dados = HexFormat.of().parseHex("a9059cbb" + "0".repeat(64));

		byte[] raw = AssinadorDeTransacao.assinar(7, BigInteger.valueOf(1_000_000_000L),
				BigInteger.valueOf(5_000_000_000L), 300_000, para, BigInteger.ZERO, dados, PRIVADA);

		Optional<byte[]> remetente = TransacaoPendente.remetente(raw);

		assertTrue(remetente.isPresent(), "a tx assinada tem de ser recuperável");
		assertArrayEquals(esperado, remetente.get(), "o remetente recuperado tem de ser quem assinou");
	}
}