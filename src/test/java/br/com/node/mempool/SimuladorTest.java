package br.com.node.mempool;
import br.com.node.transformacao.mempool.*;
import br.com.node.transformacao.nucleo.*;


import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.vo.Log;

class SimuladorTest {

	private static boolean mesmasPoolsEReservas(Set<String> simulados, Set<String> reais) {

		java.util.Map<String, String> sim = new java.util.HashMap<>();
		java.util.Map<String, String> real = new java.util.HashMap<>();
		simulados.forEach(x -> sim.put(x.split(":")[0], x.split(":")[1]));
		reais.forEach(x -> real.put(x.split(":")[0], x.split(":")[1]));

		if (!sim.keySet().equals(real.keySet())) {
			return false;
		}

		for (String pool : sim.keySet()) {
			for (int palavra = 0; palavra < 2; palavra++) {
				java.math.BigInteger a = new java.math.BigInteger(
						sim.get(pool).substring(2 + 64 * palavra, 66 + 64 * palavra), 16);
				java.math.BigInteger b = new java.math.BigInteger(
						real.get(pool).substring(2 + 64 * palavra, 66 + 64 * palavra), 16);
				if (b.signum() > 0
						&& a.subtract(b).abs().multiply(java.math.BigInteger.valueOf(10_000)).compareTo(b) > 0) {
					return false;
				}
			}
		}

		return true;
	}

	private static final String SYNC = "0x1c411e9a96e071241c2f21f7726b17ae89e3cab4c78be50e062b03a9fffbbad1";

	@Test
	void simulacaoEmiteOsMesmosSyncDoRecibo() {

		RpcDoEnsaio rpc = new RpcDoEnsaio();
		long altura = rpc.cabeca() - 2;

		JsonArray txs = rpc.bloco(altura).orElseThrow().getAsJsonArray("transactions");
		JsonArray parametros = new JsonArray();
		parametros.add("0x" + Long.toHexString(altura));
		JsonArray recibos = JsonParser.parseString(rpc.chamar("eth_getBlockReceipts", parametros).orElseThrow())
				.getAsJsonObject().getAsJsonArray("result");

		Set<String> tocadasAntes = new HashSet<>();
		int conferidas = 0;

		for (int i = 0; i < txs.size() && conferidas < 3; i++) {

			JsonObject tx = txs.get(i).getAsJsonObject();
			Set<String> syncsReais = new HashSet<>();
			Set<String> poolsDaTx = new HashSet<>();

			for (JsonElement l : recibos.get(i).getAsJsonObject().getAsJsonArray("logs")) {
				JsonObject log = l.getAsJsonObject();
				String pool = log.get("address").getAsString().toLowerCase();
				if (log.getAsJsonArray("topics").size() > 0
						&& SYNC.equals(log.getAsJsonArray("topics").get(0).getAsString())) {
					poolsDaTx.add(pool);
					syncsReais.add(pool + ":" + log.get("data").getAsString().toLowerCase());
				}
			}

			boolean primeira = poolsDaTx.stream().noneMatch(tocadasAntes::contains);
			tocadasAntes.addAll(poolsDaTx);

			if (syncsReais.isEmpty() || !primeira
					|| !"0x1".equals(recibos.get(i).getAsJsonObject().get("status").getAsString())) {
				continue;
			}

			JsonObject chamada = new JsonObject();
			chamada.addProperty("from", tx.get("from").getAsString());
			chamada.addProperty("to", tx.get("to").getAsString());
			chamada.addProperty("input", tx.get("input").getAsString());
			chamada.addProperty("value", tx.get("value").getAsString());
			JsonArray chamadas = new JsonArray();
			chamadas.add(chamada);
			JsonObject estado = new JsonObject();
			estado.add("calls", chamadas);
			JsonArray blocos = new JsonArray();
			blocos.add(estado);
			JsonObject pedido = new JsonObject();
			pedido.add("blockStateCalls", blocos);
			pedido.addProperty("validation", false);
			JsonArray p = new JsonArray();
			p.add(pedido);
			p.add("0x" + Long.toHexString(altura - 1));

			
			
			
			
			Optional<List<Log>> logs = Simulador.logs(rpc.chamar("eth_simulateV1", p).orElseThrow());
			if (logs.isEmpty()) {
				continue;
			}

			Set<String> syncsSimulados = new HashSet<>();
			for (Log log : logs.get()) {
				if (!log.topicos().isEmpty() && SYNC.equals("0x" + HexFormat.of().formatHex(log.topicos().get(0)))) {
					syncsSimulados.add("0x" + HexFormat.of().formatHex(log.endereco()) + ":0x"
							+ HexFormat.of().formatHex(log.dados()));
				}
			}

			
			
			
			
			
			assertTrue(mesmasPoolsEReservas(syncsSimulados, syncsReais),
					"tx " + tx.get("hash") + ": simulado " + syncsSimulados + " × real " + syncsReais);
			conferidas++;
		}

		assertTrue(conferidas > 0, "nenhuma tx com Sync conferível no bloco " + altura);
	}
}