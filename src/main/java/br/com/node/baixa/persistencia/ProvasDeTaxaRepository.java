package br.com.node.baixa.persistencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.vo.ProvaDeTaxa;

public final class ProvasDeTaxaRepository {

	private static final Logger LOG = Logger.getLogger(ProvasDeTaxaRepository.class.getName());

	private static final int BYTES_DE_ENDERECO = 20;

	private final Postgres banco;

	private final Map<String, ProvaDeTaxa> pendentes = new ConcurrentHashMap<>();

	public ProvasDeTaxaRepository(Postgres banco) {
		this.banco = banco;
	}

	public void anotar(ProvaDeTaxa prova) {

		if (prova.pool().length != BYTES_DE_ENDERECO || !enderecoEmHex(prova.chamador())) {
			LOG.warning(() -> "prova de taxa sem pool ou chamador de 20 bytes, descartada: " + prova.chamador());
			return;
		}

		pendentes.merge(chave(prova), prova, ProvasDeTaxaRepository::somar);
	}

	public int descarregar() {

		List<ProvaDeTaxa> lote = drenar();

		if (lote.isEmpty()) {
			return 0;
		}

		try {

			banco.executar(conexao -> gravar(conexao, lote));

		} catch (RuntimeException falhou) {

			lote.forEach(prova -> pendentes.merge(chave(prova), prova, ProvasDeTaxaRepository::somar));

			throw falhou;
		}

		return lote.size();
	}

	public List<ProvaDeTaxa> carregar() {

		return banco.executar(conexao -> {

			List<ProvaDeTaxa> provas = new ArrayList<>();

			try (PreparedStatement consulta = conexao
					.prepareStatement("select pool, taxa, chamador, fechamentos from provas_de_taxa");
					ResultSet linhas = consulta.executeQuery()) {

				while (linhas.next()) {
					provas.add(new ProvaDeTaxa(linhas.getBytes(1), linhas.getInt(2),
							HexFormat.of().formatHex(linhas.getBytes(3)), linhas.getLong(4)));
				}
			}

			return provas;
		});
	}

	private List<ProvaDeTaxa> drenar() {

		List<ProvaDeTaxa> lote = new ArrayList<>();

		for (String chave : pendentes.keySet()) {

			ProvaDeTaxa prova = pendentes.remove(chave);

			if (prova != null) {
				lote.add(prova);
			}
		}

		return lote;
	}

	private static Void gravar(Connection conexao, List<ProvaDeTaxa> lote) throws SQLException {

		try (PreparedStatement comando = conexao.prepareStatement(
				"insert into provas_de_taxa (pool, taxa, chamador, fechamentos) values (?, ?, ?, ?) on conflict "
						+ "(pool, taxa, chamador) do update set fechamentos = provas_de_taxa.fechamentos + excluded.fechamentos")) {

			for (ProvaDeTaxa prova : lote) {
				comando.setBytes(1, prova.pool());
				comando.setInt(2, prova.taxa());
				comando.setBytes(3, HexFormat.of().parseHex(prova.chamador()));
				comando.setLong(4, prova.fechamentos());
				comando.addBatch();
			}

			comando.executeBatch();
		}

		return null;
	}

	private static ProvaDeTaxa somar(ProvaDeTaxa ja, ProvaDeTaxa nova) {
		return new ProvaDeTaxa(ja.pool(), ja.taxa(), ja.chamador(), ja.fechamentos() + nova.fechamentos());
	}

	private static String chave(ProvaDeTaxa prova) {
		return HexFormat.of().formatHex(prova.pool()) + ":" + prova.taxa() + ":" + prova.chamador();
	}

	private static boolean enderecoEmHex(String texto) {
		return texto.length() == BYTES_DE_ENDERECO * 2 && texto.chars().allMatch(HexFormat::isHexDigit);
	}
}