package br.com.node.auditoria.registro;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

import com.google.gson.Gson;

import br.com.node.execucao.disparo.Desfechos;
import br.com.node.transformacao.nucleo.Arbitragem;

public sealed interface Evento permits Evento.EnvioRegistrado, Evento.RecusaRegistrada, Evento.AutopsiaRegistrada,
		Evento.DesfechoRegistrado, Evento.ArbitragemObservada, Evento.SnapshotRegistrado {

	void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException;

	static BigDecimal decimal(BigInteger valor) {
		return valor == null ? null : new BigDecimal(valor);
	}

	static String hex(byte[] bytes) {
		return bytes == null ? null : HexFormat.of().formatHex(bytes);
	}

	record EnvioRegistrado(Desfechos.Envio envio) implements Evento {

		@Override
		public void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException {
			try (PreparedStatement s = conexao.prepareStatement("""
					insert into auditoria_envio (hash, execucao_id, altura, caminho, rota_id, pools, chegada_ms,
						fracao_milha, lance_wei_por_gas, lucro_bnb_wei, gas_para_o_lance, detalhes)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
					on conflict (hash) do nothing""")) {
				s.setString(1, envio.hash());
				s.setLong(2, execucaoId);
				s.setLong(3, envio.altura());
				s.setString(4, envio.caminho());
				s.setString(5, envio.rota());
				s.setArray(6, conexao.createArrayOf("text", envio.pools().toArray()));
				s.setLong(7, envio.chegadaMs());
				s.setInt(8, envio.fracaoMilha());
				s.setBigDecimal(9, decimal(envio.ofertadoWeiPorGas()));
				s.setBigDecimal(10, decimal(envio.lucroBnb()));
				s.setBigDecimal(11, decimal(envio.gasParaOLance()));
				s.setString(12, json.toJson(envio.detalhes()));
				s.executeUpdate();
			}
		}
	}

	record RecusaRegistrada(String hash, Map<String, Object> detalhes) implements Evento {

		@Override
		public void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException {
			String motivo = detalhes.containsKey("motivo_nao_enviado") ? String.valueOf(detalhes.get("motivo_nao_enviado"))
					: "recusada_pelo_relay";
			try (PreparedStatement s = conexao.prepareStatement("""
					insert into auditoria_recusa (hash, execucao_id, motivo, detalhes)
					values (?, ?, ?, ?::jsonb)
					on conflict (hash) do nothing""")) {
				s.setString(1, hash);
				s.setLong(2, execucaoId);
				s.setString(3, motivo);
				s.setString(4, json.toJson(detalhes));
				s.executeUpdate();
			}
		}
	}

	record AutopsiaRegistrada(String hash, Map<String, Object> resultado) implements Evento {

		@Override
		public void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException {
			try (PreparedStatement s = conexao.prepareStatement("""
					insert into auditoria_autopsia (hash, status, desfecho, veredito, detalhes)
					values (?, ?, ?, ?, ?::jsonb)
					on conflict (hash) do nothing""")) {
				s.setString(1, hash);
				s.setString(2, texto(resultado.get("status")));
				s.setString(3, texto(resultado.get("desfecho")));
				s.setString(4, texto(resultado.get("veredito")));
				s.setString(5, json.toJson(resultado));
				s.executeUpdate();
			}
		}
	}

	record DesfechoRegistrado(Desfechos.Desfecho desfecho) implements Evento {

		@Override
		public void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException {
			try (PreparedStatement s = conexao.prepareStatement("""
					insert into auditoria_desfecho (hash, classe, folga_ms, posicao, quem, preco_gas_frente_wei,
						topo_wei, veredito)
					values (?, ?, ?, ?, ?, ?, ?, ?)
					on conflict (hash) do nothing""")) {
				s.setString(1, desfecho.envio().hash());
				s.setString(2, desfecho.classe());
				s.setObject(3, desfecho.folgaMs());
				s.setObject(4, desfecho.posicao());
				s.setString(5, desfecho.quem());
				s.setBigDecimal(6, decimal(desfecho.naFrente()));
				s.setBigDecimal(7, decimal(desfecho.topo()));
				s.setString(8, desfecho.veredito());
				s.executeUpdate();
			}
		}
	}

	record ArbitragemObservada(Arbitragem arbitragem, long altura, int indice, int gatilho) implements Evento {

		@Override
		public void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException {
			List<String> pools = arbitragem.pools().stream().map(Evento::hex).toList();
			try (PreparedStatement s = conexao.prepareStatement("""
					insert into auditoria_arbitragem (altura, indice, gatilho, token, sobra_wei, pools, v2, v3)
					values (?, ?, ?, ?, ?, ?, ?, ?)
					on conflict (altura, indice, gatilho) do nothing""")) {
				s.setLong(1, altura);
				s.setInt(2, indice);
				s.setInt(3, gatilho);
				s.setString(4, hex(arbitragem.token()));
				s.setBigDecimal(5, decimal(arbitragem.sobra()));
				s.setArray(6, conexao.createArrayOf("text", pools.toArray()));
				s.setInt(7, arbitragem.v2());
				s.setInt(8, arbitragem.v3());
				s.executeUpdate();
			}
		}
	}

	record SnapshotRegistrado(Map<String, Object> dados) implements Evento {

		@Override
		public void gravar(Connection conexao, long execucaoId, Gson json) throws SQLException {
			try (PreparedStatement s = conexao.prepareStatement(
					"insert into auditoria_snapshot (dados) values (?::jsonb)")) {
				s.setString(1, json.toJson(dados));
				s.executeUpdate();
			}
		}
	}

	private static String texto(Object valor) {
		return valor == null ? null : String.valueOf(valor);
	}
}
