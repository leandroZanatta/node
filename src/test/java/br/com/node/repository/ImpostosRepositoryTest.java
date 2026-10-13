package br.com.node.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.exception.BancoException;
import br.com.node.repository.postgres.Postgres;
import br.com.node.vo.ProvaDeImposto;

// A prova de imposto que sobrevive ao restart, contra o Postgres de verdade — as regras que o BANCO
// impõe não se provam com banco de mentira.
//
//   1. o que se grava por (pool, token) volta igual, e o honeypot (-1) também;
//   2. a mesma (pool, token) regravada reafirma o valor, não duplica (imposto é imutável);
//   3. o banco recusa bps fora da faixa, e o lote recusado VOLTA para a memória em vez de sumir.
class ImpostosRepositoryTest {

	private final SecureRandom sorteio = new SecureRandom();

	@Test
	@DisplayName("prova de imposto por (pool, token) volta igual, honeypot persiste, e o lote recusado não se perde")
	void guardaProvaDeImposto() {

		try (Postgres banco = new Postgres()) {

			banco.migrar();

			ImpostosRepository repo = new ImpostosRepository(banco);

			byte[] pool = endereco();
			byte[] taxado = endereco();
			byte[] honeypot = endereco();

			try {

				repo.anotar(new ProvaDeImposto(pool, taxado, 1300, false));
				repo.anotar(new ProvaDeImposto(pool, honeypot, -1, false));

				assertEquals(2, repo.descarregar(), "as duas provas tinham de gravar");
				assertEquals(1300, bps(repo, pool, taxado), "o imposto medido não voltou igual");
				assertEquals(-1, bps(repo, pool, honeypot), "o honeypot (-1) não persistiu");

				// regravar a mesma (pool, token): reafirma, não duplica
				repo.anotar(new ProvaDeImposto(pool, taxado, 1300, false));
				assertEquals(1, repo.descarregar(), "regravar devia ser um upsert, não uma linha nova");
				assertEquals(1300, bps(repo, pool, taxado), "o valor mudou ao regravar");

				// a COMPRA do mesmo (pool, token) é outra prova, noutra tabela, e não apaga a venda
				repo.anotar(new ProvaDeImposto(pool, taxado, 300, true));
				assertEquals(1, repo.descarregar(), "a compra tinha de gravar");
				assertEquals(300, bps(repo, pool, taxado, true), "a compra não voltou igual");
				assertEquals(1300, bps(repo, pool, taxado, false), "a compra apagou a venda");

				assertEquals(0, repo.descarregar(), "descarregar sem nada anotado gravou algo");

				repo.anotar(new ProvaDeImposto(pool, endereco(), 1_000_000, false));
				assertThrows(BancoException.class, repo::descarregar, "o banco aceitou bps fora da faixa");
				assertThrows(BancoException.class, repo::descarregar, "o lote recusado sumiu em vez de voltar");

			} finally {
				apagar(banco, pool);
			}
		}
	}

	private int bps(ImpostosRepository repo, byte[] pool, byte[] token) {
		return bps(repo, pool, token, false);
	}

	private int bps(ImpostosRepository repo, byte[] pool, byte[] token, boolean compra) {

		return repo.carregar().stream()
				.filter(p -> Arrays.equals(p.pool(), pool) && Arrays.equals(p.token(), token) && p.compra() == compra)
				.mapToInt(ProvaDeImposto::bps).findFirst().orElseThrow();
	}

	private byte[] endereco() {

		byte[] e = new byte[20];

		sorteio.nextBytes(e);

		return e;
	}

	private static void apagar(Postgres banco, byte[] pool) {

		banco.executar(conexao -> {

			for (String tabela : new String[] { "impostos_provados", "impostos_de_compra" }) {
				try (PreparedStatement comando = conexao.prepareStatement("delete from " + tabela + " where pool = ?")) {
					comando.setBytes(1, pool);
					comando.executeUpdate();
				}
			}

			return null;
		});
	}
}
