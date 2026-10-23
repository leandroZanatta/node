package br.com.node.repository.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

import br.com.node.exception.BancoException;
import br.com.node.util.Resources;

public final class Postgres implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(Postgres.class.getName());

	private static final int MAXIMO_CONEXOES = 8;

	private final String url;
	private final String usuario;
	private final String senha;

	private final Deque<Connection> livres = new ArrayDeque<>();

	private volatile boolean fechado;

	public Postgres() {

		this(String.format("jdbc:postgresql://%s:%d/%s", Resources.texto("postgres.host"),
				Resources.inteiro("postgres.porta"), Resources.texto("postgres.banco")),
				Resources.texto("postgres.usuario"), Resources.texto("postgres.senha"));
	}

	public Postgres(String url, String usuario, String senha) {

		this.url = url;
		this.usuario = usuario;
		this.senha = senha;
	}

	public void migrar() {

		LOG.info(() -> "aplicando esquema em " + url + " (lock_timeout de 10s)");

		try (InputStream in = Postgres.class.getResourceAsStream("/esquema.sql")) {

			if (in == null) {
				throw new BancoException("esquema.sql ausente nos resources");
			}

			String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);

			executar(conexao -> {

				try (Statement comando = conexao.createStatement()) {

					comando.execute(ddl);

					return null;
				}
			});

			LOG.info(() -> "esquema aplicado em " + url);

		} catch (IOException e) {
			throw new BancoException("Falha lendo esquema.sql: " + e.getMessage());
		}
	}

	public <R> R executar(Trabalho<R> trabalho) {

		Connection conexao = pegar();

		try {

			return trabalho.fazer(conexao);

		} catch (SQLException e) {

			descartar(conexao);

			conexao = null;

			throw new BancoException("Falha no Postgres: " + e.getMessage());

		} finally {

			if (conexao != null) {
				devolver(conexao);
			}
		}
	}

	private synchronized Connection pegar() {

		if (fechado) {
			throw new BancoException("Pool do Postgres já fechado");
		}

		while (!livres.isEmpty()) {

			Connection conexao = livres.pop();

			if (viva(conexao)) {
				return conexao;
			}
		}

		return abrir();
	}

	private synchronized void devolver(Connection conexao) {

		if (fechado || livres.size() >= MAXIMO_CONEXOES) {

			descartar(conexao);

			return;
		}

		livres.push(conexao);
	}

	private Connection abrir() {

		try {

			Connection conexao = DriverManager.getConnection(url, usuario, senha);

			conexao.setAutoCommit(true);

			return conexao;

		} catch (SQLException e) {
			throw new BancoException("Não conectou em " + url + ": " + e.getMessage());
		}
	}

	private static boolean viva(Connection conexao) {

		try {
			return !conexao.isClosed() && conexao.isValid(1);
		} catch (SQLException e) {
			return false;
		}
	}

	private static void restaurar(Connection conexao) {

		try {
			conexao.setAutoCommit(true);
		} catch (SQLException e) {

		}
	}

	private static void desfazer(Connection conexao) {

		try {
			conexao.rollback();
		} catch (SQLException e) {

		}
	}

	private static void descartar(Connection conexao) {

		try {
			conexao.close();
		} catch (SQLException e) {
			LOG.log(Level.FINE, e, () -> "falha fechando conexão");
		}
	}

	@Override
	public synchronized void close() {

		fechado = true;

		while (!livres.isEmpty()) {
			descartar(livres.pop());
		}
	}
}