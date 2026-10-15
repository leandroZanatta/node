package br.com.node.ensaio;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// QUEM GANHOU cada leilão que perdemos, e QUANTO levou.
//
// Para uma tentativa perdida, o desfecho diz a posição do vencedor no bloco dele. Aqui lemos esse bloco e
// registramos: a transação vencedora (hash, remetente, destinatário, valor, preço do gás, gás usado), o minerador
// do bloco (a builder que o montou) e todo pagamento direto às builders naquele bloco. A linha é ligada à NOSSA
// tentativa pelo hash, então a junção não depende de posição nem de endereço inferido dos logs.
//
// `valor_wei` é o BNB que a transação vencedora enviou direto a uma builder. Se for zero, o vencedor não pagou
// em BNB direto: pagou pela taxa de gás (`taxa_wei`) ou por um contrato, que só um trace mostraria.
public final class VencedorDoLeilao {

	private static final Logger LOG = Logger.getLogger(VencedorDoLeilao.class.getName());
	private static final BigInteger SEM_VALOR = BigInteger.ZERO;
	// Quantas posições ao redor da transação vencedora olhamos para achar o pagamento dela.
	static final int JANELA_POSICAO = 3;

	private VencedorDoLeilao() {
	}

	// Função pura: o bloco do vencedor (com transações completas) e a posição dele. `gasUsado` entra depois, pelo
	// recibo, e fica nulo se o recibo não chegou.
	public static JsonObject extrair(String nossoHash, String classe, long alturaDoVencedor, int posicao,
			JsonObject bloco, Set<String> enderecosDeBuilder) {

		JsonObject r = new JsonObject();
		r.addProperty("nosso_hash", nossoHash);
		r.addProperty("classe", classe);
		r.addProperty("altura_do_vencedor", alturaDoVencedor);
		r.addProperty("posicao", posicao);
		r.addProperty("minerador", semPrefixo(texto(bloco, "miner")));

		JsonElement txs = bloco.get("transactions");
		if (txs == null || !txs.isJsonArray() || posicao < 0 || posicao >= txs.getAsJsonArray().size()) {
			r.addProperty("erro", "posição fora do bloco");
			return r;
		}

		JsonObject tx = txs.getAsJsonArray().get(posicao).getAsJsonObject();
		BigInteger valor = hexParaInteiro(texto(tx, "value"));
		BigInteger precoDoGas = hexParaInteiro(texto(tx, "gasPrice"));

		r.addProperty("hash_vencedor", texto(tx, "hash"));
		r.addProperty("de", semPrefixo(texto(tx, "from")));
		r.addProperty("para", semPrefixo(texto(tx, "to")));
		r.addProperty("valor_wei", valor.toString());
		r.addProperty("gas_price_wei", precoDoGas.toString());
		r.addProperty("direto_a_builder", enderecosDeBuilder.contains(semPrefixo(texto(tx, "to"))));

		JsonArray pagamentos = new JsonArray();
		for (PagamentosDeBloco.Pagamento p : PagamentosDeBloco.interpretar(bloco, enderecosDeBuilder)) {
			JsonObject linha = new JsonObject();
			linha.addProperty("posicao", p.posicao());
			linha.addProperty("de", p.de());
			linha.addProperty("para", p.para());
			linha.addProperty("valor_wei", p.valorWei().toString());
			linha.addProperty("hash", p.hash());
			pagamentos.add(linha);
		}
		r.add("pagamentos_a_builder_no_bloco", pagamentos);

		// O pagamento do VENCEDOR: BNB que o remetente dele mandou a uma builder, na própria tx ou numa tx vizinha
		// (o lance costuma ser uma transferência separada, do mesmo remetente, logo depois da arbitragem).
		String de = semPrefixo(texto(tx, "from"));
		BigInteger pagoPeloVencedor = BigInteger.ZERO;
		for (PagamentosDeBloco.Pagamento p : PagamentosDeBloco.interpretar(bloco, enderecosDeBuilder)) {
			if (de != null && de.equals(p.de()) && Math.abs(p.posicao() - posicao) <= JANELA_POSICAO) {
				pagoPeloVencedor = pagoPeloVencedor.add(p.valorWei());
			}
		}
		r.addProperty("pagamento_do_vencedor_wei", pagoPeloVencedor.toString());

		// TODAS as transferências de valor do bloco, sem filtro de destino nem de posição. O filtro acima só olha
		// o remetente exato e três builders conhecidas: se o lance foi para outro endereço (payout, coinbase,
		// contrato), ele aparece aqui, e a conclusão não fica presa à nossa suposição de forma de busca.
		JsonArray valores = new JsonArray();
		JsonArray todas = txs.getAsJsonArray();
		for (int i = 0; i < todas.size(); i++) {
			JsonObject t = todas.get(i).getAsJsonObject();
			BigInteger v = hexParaInteiro(texto(t, "value"));
			if (v.signum() > 0) {
				JsonObject linha = new JsonObject();
				linha.addProperty("posicao", i);
				linha.addProperty("de", semPrefixo(texto(t, "from")));
				linha.addProperty("para", semPrefixo(texto(t, "to")));
				linha.addProperty("valor_wei", v.toString());
				linha.addProperty("hash", texto(t, "hash"));
				valores.add(linha);
			}
		}
		r.add("transferencias_de_valor_no_bloco", valores);
		return r;
	}

	// Acrescenta uma linha. Chamado de uma thread de fundo: a escrita é serializada para não misturar linhas.
	public static synchronized void gravar(Path arquivo, JsonObject linha) {
		try {
			Files.write(arquivo, (linha.toString() + "\n").getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warning(() -> "vencedor do leilão não gravado: " + e.getMessage());
		}
	}

	// Pagamentos que a própria transação vencedora fez a uma builder, pela lista do bloco. Usado pelo /disputa.
	public static List<PagamentosDeBloco.Pagamento> doBloco(JsonObject bloco, Set<String> enderecosDeBuilder) {
		return PagamentosDeBloco.interpretar(bloco, enderecosDeBuilder);
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
			return SEM_VALOR;
		}
		return new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
	}
}
