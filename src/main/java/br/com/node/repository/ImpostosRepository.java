package br.com.node.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import br.com.node.repository.postgres.Postgres;
import br.com.node.vo.ProvaDeImposto;

public final class ImpostosRepository {

	private static final Logger LOG = Logger.getLogger(ImpostosRepository.class.getName());

	private static final int BYTES_DE_ENDERECO = 20;

	private final Postgres banco;

	private final ConcurrentMap<String, ProvaDeImposto> pendentes = new ConcurrentHashMap<>();

	public ImpostosRepository(Postgres banco) {
		this.banco = banco;
	}

	public void anotar(ProvaDeImposto prova) {

		if (prova.pool().length != BYTES_DE_ENDERECO || prova.token().length != BYTES_DE_ENDERECO) {
			LOG.warning(() -> "prova de imposto sem pool ou token de 20 bytes, descartada");
			return;
		}

		pendentes.put(chave(prova), prova);
	}

	public int descarregar() {

		List<ProvaDeImposto> lote = drenar();

		if (lote.isEmpty()) {
			return 0;
		}

		try {

			banco.executar(conexao -> gravar(conexao, lote));

		} catch (RuntimeException falhou) {

			lote.forEach(prova -> pendentes.putIfAbsent(chave(prova), prova));

			throw falhou;
		}

		return lote.size();
	}

	public List<ProvaDeImposto> carregar() {

		return banco.executar(conexao -> {

			List<ProvaDeImposto> provas = new ArrayList<>();

			for (boolean compra : new boolean[] { false, true }) {
				try (PreparedStatement consulta = conexao
						.prepareStatement("select pool, token, bps from " + tabela(compra));
						ResultSet linhas = consulta.executeQuery()) {

					while (linhas.next()) {
						provas.add(
								new ProvaDeImposto(linhas.getBytes(1), linhas.getBytes(2), linhas.getInt(3), compra));
					}
				}
			}

			return provas;
		});
	}

	private List<ProvaDeImposto> drenar() {

		List<ProvaDeImposto> lote = new ArrayList<>();

		for (String chave : pendentes.keySet()) {

			ProvaDeImposto prova = pendentes.remove(chave);

			if (prova != null) {
				lote.add(prova);
			}
		}

		return lote;
	}

	private static Void gravar(Connection conexao, List<ProvaDeImposto> lote) throws SQLException {

		for (boolean compra : new boolean[] { false, true }) {

			try (PreparedStatement comando = conexao.prepareStatement("insert into " + tabela(compra)
					+ " (pool, token, bps) values (?, ?, ?) on conflict (pool, token) do update set bps = excluded.bps")) {

				for (ProvaDeImposto prova : lote) {
					if (prova.compra() != compra) {
						continue;
					}
					comando.setBytes(1, prova.pool());
					comando.setBytes(2, prova.token());
					comando.setInt(3, prova.bps());
					comando.addBatch();
				}

				comando.executeBatch();
			}
		}

		return null;
	}

	private static String tabela(boolean compra) {
		return compra ? "impostos_de_compra" : "impostos_provados";
	}

	private static String chave(ProvaDeImposto prova) {
		return HexFormat.of().formatHex(prova.pool()) + ":" + HexFormat.of().formatHex(prova.token())
				+ (prova.compra() ? ":compra" : ":venda");
	}
}