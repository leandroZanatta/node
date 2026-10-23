package br.com.node.auditoria;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.baixa.persistencia.postgres.Postgres;

public final class Consulta {

	private final Postgres banco;

	public Consulta(Postgres banco) {
		this.banco = banco;
	}

	public JsonObject despachos(int n) {
		String sql = """
				select e.hash, e.altura, e.registrado_em, e.caminho, e.rota_id, e.pools, e.detalhes,
					d.classe, d.folga_ms, d.posicao, d.quem, d.preco_gas_frente_wei, d.topo_wei, d.veredito,
					a.status, a.desfecho, a.veredito as veredito_autopsia, a.detalhes as autopsia,
					r.motivo, r.detalhes as recusa
				from auditoria_envio e
				left join auditoria_desfecho d on d.hash = e.hash
				left join auditoria_autopsia a on a.hash = e.hash
				left join auditoria_recusa r on r.hash = e.hash
				order by e.registrado_em desc
				limit ?""";
		JsonArray entradas = new JsonArray();
		banco.executar(conexao -> {
			try (PreparedStatement s = conexao.prepareStatement(sql)) {
				s.setInt(1, n);
				try (ResultSet linha = s.executeQuery()) {
					while (linha.next()) {
						entradas.add(despacho(linha));
					}
				}
			}
			return null;
		});
		JsonObject resposta = new JsonObject();
		resposta.add("entradas", entradas);
		resposta.addProperty("total", total("auditoria_envio"));
		return resposta;
	}

	public JsonObject resumo() {
		JsonObject resposta = new JsonObject();
		resposta.add("envios_por_classe", agrupado("select classe, count(*) from auditoria_desfecho group by classe"));
		resposta.add("recusas_por_motivo", agrupado("select motivo, count(*) from auditoria_recusa group by motivo"));
		resposta.add("autopsias_por_status", agrupado("select status, count(*) from auditoria_autopsia group by status"));
		resposta.add("envios_por_execucao", agrupado(
				"select execucao_id::text, count(*) from auditoria_envio group by execucao_id order by execucao_id"));
		resposta.addProperty("arbitragens_observadas", total("auditoria_arbitragem"));
		resposta.addProperty("envios", total("auditoria_envio"));
		resposta.addProperty("recusas", total("auditoria_recusa"));
		resposta.add("ultimo_snapshot", ultimoSnapshot());
		return resposta;
	}

	public JsonObject arbitragens(int n) {
		String sql = """
				select altura, indice, gatilho, token, sobra_wei::text, pools, v2, v3, observado_em
				from auditoria_arbitragem
				order by altura desc, indice desc
				limit ?""";
		JsonArray linhas = new JsonArray();
		banco.executar(conexao -> {
			try (PreparedStatement s = conexao.prepareStatement(sql)) {
				s.setInt(1, n);
				try (ResultSet r = s.executeQuery()) {
					while (r.next()) {
						JsonObject o = new JsonObject();
						o.addProperty("altura", r.getLong("altura"));
						o.addProperty("indice", r.getInt("indice"));
						o.addProperty("gatilho", r.getInt("gatilho"));
						o.addProperty("token", r.getString("token"));
						o.addProperty("sobra_wei", r.getString("sobra_wei"));
						o.add("pools", arrayDe(r.getArray("pools")));
						o.addProperty("v2", r.getInt("v2"));
						o.addProperty("v3", r.getInt("v3"));
						o.addProperty("observado_em", r.getString("observado_em"));
						linhas.add(o);
					}
				}
			}
			return null;
		});
		JsonObject resposta = new JsonObject();
		resposta.add("arbitragens", linhas);
		return resposta;
	}

	private JsonObject despacho(ResultSet linha) throws SQLException {
		JsonObject entrada = new JsonObject();
		entrada.addProperty("hash", linha.getString("hash"));
		entrada.addProperty("altura", linha.getLong("altura"));
		entrada.addProperty("registrado_em", linha.getString("registrado_em"));
		JsonObject envio = JsonParser.parseString(linha.getString("detalhes")).getAsJsonObject();
		envio.addProperty("caminho", linha.getString("caminho"));
		envio.addProperty("rota_id", linha.getString("rota_id"));
		envio.add("pools", arrayDe(linha.getArray("pools")));
		entrada.add("envio", envio);
		if (linha.getString("classe") != null) {
			JsonObject desfecho = new JsonObject();
			desfecho.addProperty("classe", linha.getString("classe"));
			desfecho.addProperty("folga_ms", linha.getObject("folga_ms") == null ? null : linha.getLong("folga_ms"));
			desfecho.addProperty("posicao", linha.getObject("posicao") == null ? null : linha.getInt("posicao"));
			desfecho.addProperty("quem", linha.getString("quem"));
			desfecho.addProperty("preco_gas_frente_wei", linha.getString("preco_gas_frente_wei"));
			desfecho.addProperty("topo_wei", linha.getString("topo_wei"));
			desfecho.addProperty("veredito", linha.getString("veredito"));
			entrada.add("desfecho", desfecho);
		}
		if (linha.getString("autopsia") != null) {
			entrada.add("autopsia", JsonParser.parseString(linha.getString("autopsia")).getAsJsonObject());
		}
		if (linha.getString("recusa") != null) {
			entrada.addProperty("motivo_recusa", linha.getString("motivo"));
			entrada.add("recusa", JsonParser.parseString(linha.getString("recusa")).getAsJsonObject());
		}
		return entrada;
	}

	private JsonArray agrupado(String sql) {
		JsonArray linhas = new JsonArray();
		banco.executar(conexao -> {
			try (PreparedStatement s = conexao.prepareStatement(sql); ResultSet r = s.executeQuery()) {
				while (r.next()) {
					JsonObject o = new JsonObject();
					o.addProperty("chave", r.getString(1));
					o.addProperty("total", r.getLong(2));
					linhas.add(o);
				}
			}
			return null;
		});
		return linhas;
	}

	private JsonObject ultimoSnapshot() {
		return banco.executar(conexao -> {
			try (PreparedStatement s = conexao.prepareStatement(
					"select instante, dados from auditoria_snapshot order by id desc limit 1");
					ResultSet r = s.executeQuery()) {
				if (!r.next()) {
					return null;
				}
				JsonObject o = new JsonObject();
				o.addProperty("instante", r.getString("instante"));
				o.add("dados", JsonParser.parseString(r.getString("dados")).getAsJsonObject());
				return o;
			}
		});
	}

	private long total(String tabela) {
		return banco.executar(conexao -> {
			try (PreparedStatement s = conexao.prepareStatement("select count(*) from " + tabela);
					ResultSet r = s.executeQuery()) {
				r.next();
				return r.getLong(1);
			}
		});
	}

	private static JsonArray arrayDe(java.sql.Array array) throws SQLException {
		JsonArray lista = new JsonArray();
		if (array == null) {
			return lista;
		}
		for (Object item : (Object[]) array.getArray()) {
			lista.add(String.valueOf(item));
		}
		return lista;
	}
}
