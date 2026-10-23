package br.com.node.baixa.persistencia.postgres;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface Trabalho<R> {

	R fazer(Connection conexao) throws SQLException;
}