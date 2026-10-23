package br.com.node.auditoria;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.google.gson.Gson;

import br.com.node.auditoria.registro.Evento;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.exception.BancoException;

public final class Auditoria implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(Auditoria.class.getName());
	private static final Gson JSON = new Gson();
	private static final int LOTE = 256;
	private static final int CAPACIDADE = 50_000;

	private final Postgres banco;
	private final long execucaoId;
	private final BlockingQueue<Evento> fila = new ArrayBlockingQueue<>(CAPACIDADE);
	private final Thread gravador;
	private final AtomicLong descartados = new AtomicLong();
	private final AtomicLong gravados = new AtomicLong();
	private final AtomicLong falhas = new AtomicLong();
	private volatile boolean fechando;

	public Auditoria(Postgres banco, int versaoDoExecutor, Map<String, Object> configuracao) {
		this.banco = banco;
		aplicarEsquema();
		this.execucaoId = registrarExecucao(versaoDoExecutor, configuracao);
		this.gravador = new Thread(this::laco, "auditoria-gravador");
		this.gravador.setDaemon(true);
		this.gravador.start();
	}

	public long execucaoId() {
		return execucaoId;
	}

	public void registrar(Evento evento) {
		if (fechando) {
			return;
		}
		if (!fila.offer(evento)) {
			long total = descartados.incrementAndGet();
			if (total == 1 || total % 1000 == 0) {
				LOG.warning(() -> "auditoria: fila cheia, " + total + " eventos descartados até agora");
			}
		}
	}

	public Map<String, Object> estado() {
		Map<String, Object> estado = new LinkedHashMap<>();
		estado.put("na_fila", fila.size());
		estado.put("gravados", gravados.get());
		estado.put("falhas", falhas.get());
		estado.put("descartados", descartados.get());
		return estado;
	}

	@Override
	public void close() {
		fechando = true;
		try {
			gravador.join(TimeUnit.SECONDS.toMillis(15));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void laco() {
		List<Evento> lote = new ArrayList<>(LOTE);
		while (!fechando || !fila.isEmpty()) {
			try {
				Evento primeiro = fila.poll(500, TimeUnit.MILLISECONDS);
				if (primeiro == null) {
					continue;
				}
				lote.add(primeiro);
				fila.drainTo(lote, LOTE - 1);
				gravar(lote);
				lote.clear();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void gravar(List<Evento> lote) {
		try {
			banco.executar(conexao -> {
				boolean automatico = conexao.getAutoCommit();
				conexao.setAutoCommit(false);
				try {
					for (Evento evento : lote) {
						evento.gravar(conexao, execucaoId, JSON);
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
			gravados.addAndGet(lote.size());
		} catch (RuntimeException falhou) {
			falhas.addAndGet(lote.size());
			LOG.warning(() -> "auditoria: lote de " + lote.size() + " eventos não gravou: " + falhou.getMessage());
		}
	}

	private void aplicarEsquema() {
		String ddl = recurso("/auditoria.sql");
		banco.executar(conexao -> {
			try (Statement comando = conexao.createStatement()) {
				comando.execute(ddl);
			}
			return null;
		});
	}

	private long registrarExecucao(int versaoDoExecutor, Map<String, Object> configuracao) {
		return banco.executar(conexao -> {
			try (PreparedStatement s = conexao.prepareStatement(
					"insert into auditoria_execucao (versao_executor, configuracao) values (?, ?::jsonb) returning id")) {
				s.setInt(1, versaoDoExecutor);
				s.setString(2, JSON.toJson(configuracao));
				try (ResultSet linha = s.executeQuery()) {
					linha.next();
					return linha.getLong(1);
				}
			}
		});
	}

	private static String recurso(String caminho) {
		try (InputStream in = Auditoria.class.getResourceAsStream(caminho)) {
			if (in == null) {
				throw new BancoException("recurso ausente: " + caminho);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BancoException("não leu " + caminho + ": " + e.getMessage());
		}
	}
}
