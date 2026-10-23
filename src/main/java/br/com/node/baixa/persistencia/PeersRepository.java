package br.com.node.baixa.persistencia;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import br.com.node.exception.BancoException;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.vo.NoRemoto;

public final class PeersRepository {

	private final Postgres banco;

	public PeersRepository(Postgres banco) {
		this.banco = banco;
	}

	public void salvar(NoRemoto no) {

		banco.executar(conexao -> {

			try (PreparedStatement comando = conexao.prepareStatement(
					"insert into peers (id, host, porta_udp, porta_tcp, chain) values (?, ?, ?, ?, 0) "
							+ "on conflict (id) do update set host = excluded.host, "
							+ "porta_udp = excluded.porta_udp, porta_tcp = excluded.porta_tcp")) {

				comando.setBytes(1, no.id());
				comando.setString(2, no.ip().getHostAddress());
				comando.setInt(3, no.portaUdp());
				comando.setInt(4, no.portaTcp());

				comando.executeUpdate();
			}

			return null;
		});
	}

	public List<NoRemoto> carregar() {

		return banco.executar(conexao -> {

			List<NoRemoto> nos = new ArrayList<>();

			try (PreparedStatement consulta = conexao
					.prepareStatement("select id, host, porta_udp, porta_tcp from peers");
					ResultSet linhas = consulta.executeQuery()) {

				while (linhas.next()) {
					nos.add(new NoRemoto(linhas.getBytes(1), endereco(linhas.getString(2)), linhas.getInt(3),
							linhas.getInt(4)));
				}
			}

			return nos;
		});
	}

	private static InetAddress endereco(String host) {

		try {
			return InetAddress.getByName(host);
		} catch (UnknownHostException e) {
			throw new BancoException("Host inválido guardado em peers: " + host);
		}
	}
}