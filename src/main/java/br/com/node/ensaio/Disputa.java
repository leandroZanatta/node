package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// A PERGUNTA de cada tentativa: se não levamos, foi por quanto? Quem levou, pagou quanto?
//
// Cruza três coisas que o nó já tem: a nossa tentativa (o lance que oferecemos e o lucro que víamos), o
// desfecho (quem levou a fenda, em que posição do bloco) e o pagamento MEDIDO no bloco do vencedor
// (`PagamentosDeBloco`: quanto BNB saiu de cada remetente para o endereço de uma builder).
//
// Frações são em milésimos do lucro em BNB: `fracaoRival = pagoRival × 1000 / lucro`.
// Se o vencedor não tem transferência visível para a builder, o veredito diz isso, em vez de inventar um valor:
// pagamento interno ao contrato não aparece sem trace, e é preciso dizer que não sabemos.
public final class Disputa {

	// Quantas posições ao redor da posição do vencedor olhamos para achar o pagamento dele (o mesmo critério
	// do `PagamentosDeBloco.pagamentoAoBuilder`: o pagamento pode vir numa tx vizinha).
	static final int JANELA_POSICAO = 3;

	private static final BigInteger MIL = BigInteger.valueOf(1000);

	private Disputa() {
	}

	// `despacho`: uma entrada de /despachos. `pagamentos`: as linhas de pagamentos.jsonl do bloco do vencedor
	// (quem chama filtra por altura). Função pura.
	public static Map<String, Object> analisar(JsonObject despacho, List<JsonObject> pagamentos) {
		return analisar(despacho, pagamentos, null);
	}

	// Com o registro do vencedor (`vencedores.jsonl`), o casamento é exato, pelo hash da transação vencedora.
	// Sem ele, cai no critério antigo por posição, que é só aproximação.
	public static Map<String, Object> analisar(JsonObject despacho, List<JsonObject> pagamentos, JsonObject vencedor) {
		if (vencedor != null && vencedor.has("hash_vencedor")) {
			return analisarComVencedor(despacho, vencedor);
		}

		JsonObject envio = despacho.getAsJsonObject("envio");
		JsonObject desfecho = despacho.getAsJsonObject("desfecho");

		String classe = texto(desfecho, "classe");
		long altura = envio.get("altura").getAsBigDecimal().longValue();
		Integer posicao = inteiro(desfecho, "posicao");
		String quem = semPrefixo(texto(desfecho, "quem"));

		BigInteger nosso = big(envio.has("lance") && envio.get("lance").isJsonObject()
				? envio.getAsJsonObject("lance") : null, "pagamento_direto_builder_bnb_wei");
		BigInteger lucro = big(envio, "lucro_previsto_bnb_wei");

		boolean perdemos = classe.startsWith("rival") || classe.startsWith("pool_mexida");
		boolean levamos = "incluida".equals(classe);
		long blocoDoVencedor = altura + (classe.endsWith("n2") ? 2 : 1);

		Map<String, Object> r = new LinkedHashMap<>();
		r.put("altura", altura);
		r.put("classe", classe);
		r.put("posicao_do_vencedor", posicao);
		r.put("quem_levou", quem);
		r.put("nosso_lance_bnb_wei", nosso == null ? null : nosso.toString());
		r.put("nosso_lucro_bnb_wei", lucro == null ? null : lucro.toString());
		r.put("nossa_fracao_milha", fracao(nosso, lucro));

		JsonObject pagoPeloVencedor = null;
		String casamento = null;

		if (perdemos && posicao != null) {
			List<JsonObject> vizinhos = new ArrayList<>();
			for (JsonObject p : pagamentos) {
				if (inteiro(p, "altura") != null && inteiro(p, "altura") == blocoDoVencedor
						&& Math.abs(inteiro(p, "posicao") - posicao) <= JANELA_POSICAO) {
					vizinhos.add(p);
				}
			}
			// Mesmo remetente que o vencedor, se soubermos quem é; senão, a transferência mais perto da posição.
			pagoPeloVencedor = maisProximo(vizinhos.stream()
					.filter(p -> quem != null && quem.equalsIgnoreCase(semPrefixo(texto(p, "de")))).toList(), posicao);
			if (pagoPeloVencedor != null) {
				casamento = "remetente";
			} else {
				pagoPeloVencedor = maisProximo(vizinhos, posicao);
				casamento = pagoPeloVencedor == null ? null : "posicao";
			}
		}

		BigInteger pagoVencedor = pagoPeloVencedor == null ? null : new BigInteger(texto(pagoPeloVencedor, "valor_wei"));
		r.put("vencedor_pagou_bnb_wei", pagoVencedor == null ? null : pagoVencedor.toString());
		r.put("vencedor_pagou_para", pagoPeloVencedor == null ? null : texto(pagoPeloVencedor, "para"));
		r.put("casamento_do_pagamento", casamento);
		r.put("fracao_do_vencedor_milha", fracao(pagoVencedor, lucro));

		BigInteger faltou = pagoVencedor != null && nosso != null && pagoVencedor.compareTo(nosso) > 0
				? pagoVencedor.subtract(nosso) : null;
		r.put("faltou_bnb_wei", faltou == null ? null : faltou.toString());

		r.put("veredito", veredito(classe, perdemos, levamos, nosso, pagoVencedor, faltou, casamento));
		return r;
	}

	// O vencedor é a transação registrada: o que ele enviou direto à builder, a taxa dele e a lista de pagamentos do bloco.
	private static Map<String, Object> analisarComVencedor(JsonObject despacho, JsonObject vencedor) {

		JsonObject envio = despacho.getAsJsonObject("envio");
		JsonObject desfecho = despacho.getAsJsonObject("desfecho");
		String classe = texto(desfecho, "classe");
		long altura = envio.get("altura").getAsBigDecimal().longValue();
		BigInteger nosso = big(envio.has("lance") && envio.get("lance").isJsonObject()
				? envio.getAsJsonObject("lance") : null, "pagamento_direto_builder_bnb_wei");
		BigInteger lucro = big(envio, "lucro_previsto_bnb_wei");
		boolean perdemos = classe.startsWith("rival") || classe.startsWith("pool_mexida");
		boolean levamos = "incluida".equals(classe);

		BigInteger pagoDireto = "true".equals(String.valueOf(vencedor.get("direto_a_builder")))
				? new BigInteger(texto(vencedor, "valor_wei")) : BigInteger.ZERO;
		BigInteger gasPrice = new BigInteger(texto(vencedor, "gas_price_wei"));
		BigInteger gasUsado = vencedor.has("gas_usado_vencedor") ? new BigInteger(texto(vencedor, "gas_usado_vencedor")) : null;
		BigInteger taxa = gasUsado == null ? null : gasPrice.multiply(gasUsado);

		Map<String, Object> r = new LinkedHashMap<>();
		r.put("altura", altura);
		r.put("classe", classe);
		r.put("posicao_do_vencedor", inteiro(desfecho, "posicao"));
		r.put("nosso_lance_bnb_wei", nosso == null ? null : nosso.toString());
		r.put("nosso_lucro_bnb_wei", lucro == null ? null : lucro.toString());
		r.put("nossa_fracao_milha", fracao(nosso, lucro));
		r.put("hash_vencedor", texto(vencedor, "hash_vencedor"));
		r.put("vencedor_de", texto(vencedor, "de"));
		r.put("vencedor_para", texto(vencedor, "para"));
		r.put("vencedor_minerador_builder", texto(vencedor, "minerador"));
		r.put("vencedor_pagou_bnb_wei", pagoDireto.toString());
		r.put("vencedor_taxa_wei", taxa == null ? null : taxa.toString());
		r.put("vencedor_gas_usado", gasUsado == null ? null : gasUsado.toString());
		r.put("fracao_do_vencedor_milha", fracao(pagoDireto, lucro));
		r.put("pagamentos_a_builder_no_bloco", vencedor.get("pagamentos_a_builder_no_bloco"));
		r.put("casamento_do_pagamento", "hash");

		BigInteger faltou = nosso != null && pagoDireto.compareTo(nosso) > 0 ? pagoDireto.subtract(nosso) : null;
		r.put("faltou_bnb_wei", faltou == null ? null : faltou.toString());

		String veredito;
		if (levamos) {
			veredito = "levamos";
		} else if (!perdemos) {
			veredito = veredito(classe, false, false, nosso, null, null, null);
		} else if (pagoDireto.signum() == 0) {
			veredito = "perdemos: o vencedor não enviou BNB direto a builder (taxa de gás "
					+ (taxa == null ? "desconhecida" : taxa + " wei") + ")";
		} else if (faltou != null) {
			veredito = "perdemos por lance: vencedor enviou " + pagoDireto + " wei à builder, nós " + nosso
					+ " wei; faltaram " + faltou + " wei";
		} else {
			veredito = "perdemos com lance igual ou maior: a diferença é ordem ou tempo, não preço";
		}
		r.put("veredito", veredito);
		return r;
	}

	static String veredito(String classe, boolean perdemos, boolean levamos, BigInteger nosso,
			BigInteger pagoVencedor, BigInteger faltou, String casamento) {

		if (levamos) {
			return "levamos";
		}
		if (!perdemos) {
			return switch (classe) {
				case "recusada_pelo_relay" -> "não entrou: o relay recusou o bundle";
				case "ninguem" -> "ninguém levou a fenda";
				case "invalida" -> "bundle inválido, não entrou";
				default -> classe;
			};
		}
		if (pagoVencedor == null) {
			return "perdemos: pagamento do vencedor não visível (interno ao contrato ou sem transferência direta)";
		}
		if (nosso == null) {
			return "perdemos: vencedor pagou " + pagoVencedor + " wei, e nós não tínhamos lance registrado";
		}
		if (faltou != null) {
			return "perdemos por lance: vencedor pagou " + pagoVencedor + " wei, nós " + nosso + " wei; faltaram " + faltou
					+ " wei" + ("posicao".equals(casamento) ? " (pagamento casado por posição, não por remetente)" : "");
		}
		return "perdemos com lance igual ou maior: a diferença não é preço, é ordem ou tempo";
	}

	// Resumo para o endpoint: quantas caíram em cada veredito, e o quanto faltou no total.
	public static Map<String, Object> resumir(List<Map<String, Object>> analises) {
		Map<String, Long> porVeredito = new LinkedHashMap<>();
		BigInteger faltouTotal = BigInteger.ZERO;
		long semVisivel = 0;
		for (Map<String, Object> a : analises) {
			String v = (String) a.get("veredito");
			String chave = v.startsWith("perdemos por lance") ? "perdemos por lance"
					: v.startsWith("perdemos com lance") ? "perdemos com lance igual ou maior"
					: v.startsWith("perdemos: pagamento") ? "perdemos: pagamento não visível"
					: v;
			porVeredito.merge(chave, 1L, Long::sum);
			if (a.get("faltou_bnb_wei") != null) {
				faltouTotal = faltouTotal.add(new BigInteger((String) a.get("faltou_bnb_wei")));
			}
			if (v.startsWith("perdemos: pagamento")) {
				semVisivel++;
			}
		}
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("por_veredito", porVeredito);
		r.put("faltou_total_bnb_wei", faltouTotal.toString());
		r.put("perdas_sem_pagamento_visivel", semVisivel);
		return r;
	}

	private static JsonObject maisProximo(List<JsonObject> candidatos, int posicao) {
		JsonObject melhor = null;
		int melhorDist = Integer.MAX_VALUE;
		for (JsonObject p : candidatos) {
			int dist = Math.abs(inteiro(p, "posicao") - posicao);
			if (dist < melhorDist) {
				melhor = p;
				melhorDist = dist;
			}
		}
		return melhor;
	}

	private static String fracao(BigInteger pago, BigInteger lucro) {
		if (pago == null || lucro == null || lucro.signum() <= 0) {
			return null;
		}
		return pago.multiply(MIL).divide(lucro).toString();
	}

	private static String texto(JsonObject o, String campo) {
		if (o == null || !o.has(campo) || o.get(campo).isJsonNull()) {
			return null;
		}
		return o.get(campo).getAsString();
	}

	private static Integer inteiro(JsonObject o, String campo) {
		JsonElement e = o == null ? null : o.get(campo);
		return e == null || e.isJsonNull() ? null : e.getAsBigDecimal().intValue();
	}

	private static BigInteger big(JsonObject o, String campo) {
		if (o == null || !o.has(campo) || o.get(campo).isJsonNull()) {
			return null;
		}
		return new BigInteger(o.get(campo).getAsString());
	}

	private static String semPrefixo(String endereco) {
		if (endereco == null) {
			return null;
		}
		String s = endereco.startsWith("0x") || endereco.startsWith("0X") ? endereco.substring(2) : endereco;
		return s.toLowerCase(Locale.ROOT);
	}
}
