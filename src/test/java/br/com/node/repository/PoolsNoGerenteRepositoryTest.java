package br.com.node.repository;
import br.com.node.baixa.persistencia.*;
import br.com.node.baixa.persistencia.postgres.*;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.transformacao.catalogo.ChaveDaPool;
import br.com.node.baixa.persistencia.postgres.Postgres;

class PoolsNoGerenteRepositoryTest {

	private final SecureRandom sorteio = new SecureRandom();

	@Test
	@DisplayName("a faixa grava chaves e marco juntos, a chave volta igual, e o marco só anda para frente")
	void guardaChaveEMarco() {

		try (Postgres banco = new Postgres()) {

			banco.migrar();

			PoolsNoGerenteRepository repo = new PoolsNoGerenteRepository(banco);
			byte[] gerente = bytes(20);
			ChaveDaPool chave = new ChaveDaPool(gerente, bytes(32), bytes(20), bytes(20), 2500, 10, new byte[20], 0,
					123L);

			try {

				assertTrue(repo.varridoAte(gerente).isEmpty(), "gerente novo não tem marco");

				repo.gravarFaixa(gerente, List.of(chave), 1_000L);

				ChaveDaPool lida = repo.buscar(gerente, chave.poolId()).orElseThrow();
				assertArrayEquals(chave.token0(), lida.token0());
				assertArrayEquals(chave.token1(), lida.token1());
				assertEquals(2500, lida.taxaLp());
				assertEquals(10, lida.espacamento());
				assertEquals(123L, lida.altura());
				assertEquals(1_000L, repo.varridoAte(gerente).getAsLong());

				
				
				repo.gravarFaixa(gerente, List.of(chave), 500L);
				assertEquals(1_000L, repo.varridoAte(gerente).getAsLong());

				assertTrue(repo.buscar(gerente, bytes(32)).isEmpty(), "pool que não nasceu não tem chave");

			} finally {
				apagar(banco, gerente);
			}
		}
	}

	private byte[] bytes(int n) {

		byte[] b = new byte[n];
		sorteio.nextBytes(b);

		return b;
	}

	private static void apagar(Postgres banco, byte[] gerente) {

		banco.executar(conexao -> {
			for (String tabela : List.of("pools_no_gerente", "varredura_de_gerente")) {
				try (PreparedStatement comando = conexao
						.prepareStatement("delete from " + tabela + " where gerente = ?")) {
					comando.setBytes(1, gerente);
					comando.executeUpdate();
				}
			}
			return null;
		});
	}
}