package br.com.node.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import br.com.node.catalogo.ChaveDaPool;
import br.com.node.repository.postgres.Postgres;

public final class PoolsNoGerenteRepository {

	private final Postgres banco;

	public PoolsNoGerenteRepository(Postgres banco) {
		this.banco = banco;
	}

	public void gravar(List<ChaveDaPool> lote) {

		if (lote.isEmpty()) {
			return;
		}

		banco.executar(conexao -> gravar(conexao, lote));
	}

	public Optional<ChaveDaPool> buscar(byte[] gerente, byte[] poolId) {

		return banco.executar(conexao -> {

			try (PreparedStatement consulta = conexao.prepareStatement(
					"select token0, token1, taxa_lp, espacamento, hooks, permissoes, altura from pools_no_gerente "
							+ "where gerente = ? and pool_id = ?")) {

				consulta.setBytes(1, gerente);
				consulta.setBytes(2, poolId);

				try (ResultSet linha = consulta.executeQuery()) {

					if (!linha.next()) {
						return Optional.empty();
					}

					return Optional.of(new ChaveDaPool(gerente, poolId, linha.getBytes(1), linha.getBytes(2),
							linha.getInt(3), linha.getInt(4), linha.getBytes(5), linha.getInt(6), linha.getLong(7)));
				}
			}
		});
	}

	public OptionalLong varridoAte(byte[] gerente) {

		return banco.executar(conexao -> {

			try (PreparedStatement consulta = conexao
					.prepareStatement("select ate from varredura_de_gerente where gerente = ?")) {

				consulta.setBytes(1, gerente);

				try (ResultSet linha = consulta.executeQuery()) {
					return linha.next() ? OptionalLong.of(linha.getLong(1)) : OptionalLong.empty();
				}
			}
		});
	}

	public void gravarFaixa(byte[] gerente, List<ChaveDaPool> lote, long ate) {

		banco.executar(conexao -> {

			boolean automatico = conexao.getAutoCommit();
			conexao.setAutoCommit(false);

			try {

				gravar(conexao, lote);

				try (PreparedStatement comando = conexao
						.prepareStatement("insert into varredura_de_gerente (gerente, ate) values (?, ?) "
								+ "on conflict (gerente) do update set ate = greatest(varredura_de_gerente.ate, excluded.ate)")) {
					comando.setBytes(1, gerente);
					comando.setLong(2, ate);
					comando.executeUpdate();
				}

				conexao.commit();

			} catch (SQLException | RuntimeException falhou) {
				conexao.rollback();
				throw falhou;
			} finally {
				conexao.setAutoCommit(automatico);
			}

			return null;
		});
	}

	private static Void gravar(Connection conexao, List<ChaveDaPool> lote) throws SQLException {

		if (lote.isEmpty()) {
			return null;
		}

		try (PreparedStatement comando = conexao.prepareStatement(
				"insert into pools_no_gerente (gerente, pool_id, token0, token1, taxa_lp, espacamento, hooks, permissoes, altura) "
						+ "values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (gerente, pool_id) do nothing")) {

			for (ChaveDaPool chave : lote) {
				comando.setBytes(1, chave.gerente());
				comando.setBytes(2, chave.poolId());
				comando.setBytes(3, chave.token0());
				comando.setBytes(4, chave.token1());
				comando.setInt(5, chave.taxaLp());
				comando.setInt(6, chave.espacamento());
				comando.setBytes(7, chave.hooks());
				comando.setInt(8, chave.permissoes());
				comando.setLong(9, chave.altura());
				comando.addBatch();
			}

			comando.executeBatch();
		}

		return null;
	}
}