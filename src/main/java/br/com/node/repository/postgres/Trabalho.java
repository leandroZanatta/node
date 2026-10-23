package br.com.node.repository.postgres;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface Trabalho<R> {

	R fazer(Connection conexao) throws SQLException;
}