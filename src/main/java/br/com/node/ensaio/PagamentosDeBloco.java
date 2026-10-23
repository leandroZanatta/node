package br.com.node.ensaio;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class PagamentosDeBloco implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(PagamentosDeBloco.class.getName());
	private static final int TENTATIVAS = 10;
	private static final long ESPERA_MS = 500;

	public record Pagamento(long altura, String minerador, String de, String para, BigInteger valorWei, String hash,
			int posicao) {
	}

	private final RpcDoEnsaio rpc;
	private final Path arquivo;
	private final Set<String> alvos;
	private final ExecutorService fila = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "pagamentos-bloco");
		t.setDaemon(true);
		return t;
	});

	public PagamentosDeBloco(RpcDoEnsaio rpc, Path arquivo, Set<String> alvos) {
		this.rpc = rpc;
		this.arquivo = arquivo;
		this.alvos = Set.copyOf(alvos);
	}

	public void aoFecharBloco(long altura) {
		fila.submit(() -> registrar(altura));
	}

	private void registrar(long altura) {
		try {
			Optional<JsonObject> bloco = Optional.empty();
			for (int i = 0; i < TENTATIVAS && bloco.isEmpty(); i++) {
				bloco = rpc.bloco(altura);
				if (bloco.isEmpty()) {
					Thread.sleep(ESPERA_MS);
				}
			}
			if (bloco.isEmpty()) {
				LOG.warning(() -> "pagamentos: bloco " + altura + " não chegou ao RPC");
				return;
			}
			List<Pagamento> pagamentos = interpretar(bloco.get(), alvos);
			if (pagamentos.isEmpty()) {
				return;
			}
			List<String> linhas = new ArrayList<>();
			pagamentos.forEach(p -> linhas.add(linha(p)));
			Files.write(arquivo, (String.join("\n", linhas) + "\n").getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException | RuntimeException e) {
			LOG.warning(() -> "pagamentos: bloco " + altura + " falhou: " + e);
		}
	}

	static List<Pagamento> interpretar(JsonObject bloco, Set<String> alvos) {

		List<Pagamento> achados = new ArrayList<>();
		long altura = Long.decode(texto(bloco, "number"));
		String minerador = texto(bloco, "miner");

		JsonElement txs = bloco.get("transactions");
		if (txs == null || !txs.isJsonArray()) {
			return achados;
		}

		for (JsonElement elemento : txs.getAsJsonArray()) {
			if (!elemento.isJsonObject()) {
				continue;
			}
			JsonObject tx = elemento.getAsJsonObject();
			String para = semPrefixo(texto(tx, "to"));
			if (para == null || !alvos.contains(para)) {
				continue;
			}
			BigInteger valor = hexParaInteiro(texto(tx, "value"));
			if (valor.signum() <= 0) {
				continue;
			}
			achados.add(new Pagamento(altura, semPrefixo(minerador), semPrefixo(texto(tx, "from")), para, valor,
					texto(tx, "hash"), Math.toIntExact(Long.decode(texto(tx, "transactionIndex")))));
		}
		return achados;
	}

	public static Optional<BigInteger> pagamentoAoBuilder(JsonObject bloco, int posArb, Set<String> alvos) {

		JsonElement txs = bloco.get("transactions");
		if (txs == null || !txs.isJsonArray() || posArb < 0 || posArb >= txs.getAsJsonArray().size()) {
			return Optional.empty();
		}
		List<JsonElement> lista = new ArrayList<>();
		txs.getAsJsonArray().forEach(lista::add);

		String de = semPrefixo(texto(lista.get(posArb).getAsJsonObject(), "from"));
		for (int delta : new int[] { 0, 1, -1, 2, -2, 3 }) {
			int pos = posArb + delta;
			if (pos < 0 || pos >= lista.size() || !lista.get(pos).isJsonObject()) {
				continue;
			}
			JsonObject tx = lista.get(pos).getAsJsonObject();
			if (!de.equals(semPrefixo(texto(tx, "from"))) || !alvos.contains(semPrefixo(texto(tx, "to")))) {
				continue;
			}
			BigInteger valor = hexParaInteiro(texto(tx, "value"));
			if (valor.signum() > 0) {
				return Optional.of(valor);
			}
		}
		return Optional.empty();
	}

	public static Set<String> enderecosPadrao() {
		return Set.of(HexFormat.of().formatHex(RelayPrivado.PAGAMENTO_48CLUB),
				HexFormat.of().formatHex(RelayPrivado.COLATERAL_48CLUB_BID),
				HexFormat.of().formatHex(RelayPrivado.PAGAMENTO_BLOCKRAZOR));
	}

	private static String linha(Pagamento p) {
		JsonObject o = new JsonObject();
		o.addProperty("altura", p.altura());
		o.addProperty("minerador", p.minerador());
		o.addProperty("de", p.de());
		o.addProperty("para", p.para());
		o.addProperty("valor_wei", p.valorWei().toString());
		o.addProperty("hash", p.hash());
		o.addProperty("posicao", p.posicao());
		return o.toString();
	}

	private static String texto(JsonObject o, String campo) {
		JsonElement e = o.get(campo);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static String semPrefixo(String endereco) {
		if (endereco == null) {
			return null;
		}
		String s = endereco.startsWith("0x") || endereco.startsWith("0X") ? endereco.substring(2) : endereco;
		return s.toLowerCase(Locale.ROOT);
	}

	private static BigInteger hexParaInteiro(String hex) {
		if (hex == null) {
			return BigInteger.ZERO;
		}
		return new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
	}

	@Override
	public void close() {
		fila.shutdownNow();
	}
}