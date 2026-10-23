package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Disputa {

	static final int JANELA_POSICAO = 3;

	private static final BigInteger MIL = BigInteger.valueOf(1000);

	private Disputa() {
	}

	public static Map<String, Object> analisar(JsonObject despacho, List<JsonObject> pagamentos) {
		return analisar(despacho, pagamentos, null);
	}

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

		BigInteger nosso = big(
				envio.has("lance") && envio.get("lance").isJsonObject() ? envio.getAsJsonObject("lance") : null,
				"pagamento_direto_builder_bnb_wei");
		BigInteger lucro = big(envio, "lucro_previsto_bnb_wei");

		boolean perdemos = classe.startsWith("rival") || classe.startsWith("pool_mexida");
		boolean rival = classe.startsWith("rival");
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
				Integer posPag = inteiro(p, "posicao");
				if (inteiro(p, "altura") != null && inteiro(p, "altura") == blocoDoVencedor && posPag != null
						&& Math.abs(posPag - posicao) <= JANELA_POSICAO) {
					vizinhos.add(p);
				}
			}

			pagoPeloVencedor = maisProximo(vizinhos.stream()
					.filter(p -> quem != null && quem.equalsIgnoreCase(semPrefixo(texto(p, "de")))).toList(), posicao);
			if (pagoPeloVencedor != null) {
				casamento = "remetente";
			} else {
				pagoPeloVencedor = maisProximo(vizinhos, posicao);
				casamento = pagoPeloVencedor == null ? null : "posicao";
			}
		}

		BigInteger pagoVencedor = pagoPeloVencedor == null ? null
				: new BigInteger(texto(pagoPeloVencedor, "valor_wei"));
		r.put("vencedor_pagou_bnb_wei", pagoVencedor == null ? null : pagoVencedor.toString());
		r.put("pagamento_direto_builder_status",
				classe.startsWith("rival") ? pagoVencedor == null ? "nao_detectado" : "detectado" : "nao_aplicavel");
		r.put("pagamento_total_builder_wei", null);
		r.put("pagamento_total_builder_status", rival ? "nao_verificado_sem_trace" : "nao_aplicavel");
		r.put("lance_total_rival_wei", null);
		r.put("lance_total_rival_status", rival ? "desconhecido" : "nao_aplicavel");
		r.put("vencedor_pagou_para", pagoPeloVencedor == null ? null : texto(pagoPeloVencedor, "para"));
		r.put("casamento_do_pagamento", casamento);
		r.put("fracao_do_vencedor_milha", fracao(pagoVencedor, lucro));

		BigInteger faltou = rival && pagoVencedor != null && nosso != null && pagoVencedor.compareTo(nosso) > 0
				? pagoVencedor.subtract(nosso)
				: null;
		r.put("faltou_bnb_wei", faltou == null ? null : faltou.toString());

		r.put("veredito",
				!rival && classe.startsWith("pool_mexida")
						? "pool alterada por transacao sem arbitragem rival confirmada"
						: veredito(classe, perdemos, levamos, nosso, pagoVencedor, faltou, casamento));
		return r;
	}

	private static Map<String, Object> analisarComVencedor(JsonObject despacho, JsonObject vencedor) {

		JsonObject envio = despacho.getAsJsonObject("envio");
		JsonObject desfecho = despacho.getAsJsonObject("desfecho");
		String classe = texto(desfecho, "classe");
		long altura = envio.get("altura").getAsBigDecimal().longValue();
		BigInteger nosso = big(
				envio.has("lance") && envio.get("lance").isJsonObject() ? envio.getAsJsonObject("lance") : null,
				"pagamento_direto_builder_bnb_wei");
		BigInteger lucro = big(envio, "lucro_previsto_bnb_wei");
		boolean perdemos = classe.startsWith("rival") || classe.startsWith("pool_mexida");
		boolean rival = classe.startsWith("rival");
		boolean levamos = "incluida".equals(classe);

		BigInteger pagoDireto = big(vencedor, "pagamento_do_vencedor_wei");
		BigInteger gasPriceReceipt = big(vencedor, "gas_preco_efetivo_wei");
		BigInteger gasPriceTransaction = big(vencedor, "gas_price_wei");
		BigInteger gasPrice = gasPriceReceipt != null ? gasPriceReceipt : gasPriceTransaction;
		BigInteger gasUsado = big(vencedor, "gas_usado_vencedor");
		BigInteger taxa = gasUsado == null || gasPrice == null ? null : gasPrice.multiply(gasUsado);
		BigInteger contribuicaoGas48Club = Lance48Club.contribuicaoGas(taxa);
		BigInteger pagamento48Club = big(vencedor, "pagamento_48club_bnb_wei");
		BigInteger pontuacao48ClubMinima = taxa == null ? null
				: Lance48Club.pontuacaoMinima(taxa, pagamento48Club == null ? BigInteger.ZERO : pagamento48Club);
		String statusPagamento48Club = texto(vencedor, "pagamento_48club_status");
		String statusPagamentoDireto = texto(vencedor, "pagamento_direto_builder_status");
		if (!rival) {
			statusPagamentoDireto = "nao_aplicavel";
		} else if (statusPagamentoDireto == null) {
			statusPagamentoDireto = pagoDireto == null ? "nao_medido"
					: pagoDireto.signum() > 0 ? "detectado" : "nao_detectado";
		}

		Map<String, Object> r = new LinkedHashMap<>();
		r.put("altura", altura);
		r.put("classe", classe);

		r.put("tipo_do_vencedor", classe.startsWith("rival") ? "arbitragem_do_rival" : "transacao_que_moveu_a_pool");
		r.put("posicao_do_vencedor", inteiro(desfecho, "posicao"));
		r.put("nosso_lance_bnb_wei", nosso == null ? null : nosso.toString());
		r.put("nosso_lucro_bnb_wei", lucro == null ? null : lucro.toString());
		r.put("nossa_fracao_milha", fracao(nosso, lucro));
		r.put("hash_vencedor", texto(vencedor, "hash_vencedor"));
		r.put("vencedor_de", texto(vencedor, "de"));
		r.put("vencedor_para", texto(vencedor, "para"));
		r.put("vencedor_minerador_builder", texto(vencedor, "minerador"));
		r.put("vencedor_pagou_bnb_wei", pagoDireto == null ? null : pagoDireto.toString());
		r.put("pagamento_direto_builder_status", statusPagamentoDireto);
		r.put("pagamento_direto_builder_escopo", texto(vencedor, "pagamento_direto_builder_escopo"));
		r.put("pagamento_total_builder_wei", texto(vencedor, "pagamento_total_builder_wei"));
		r.put("pagamento_total_builder_status",
				!rival ? "nao_aplicavel"
						: texto(vencedor, "pagamento_total_builder_status") == null ? "nao_verificado_sem_trace"
								: texto(vencedor, "pagamento_total_builder_status"));
		r.put("lance_total_rival_wei", null);
		r.put("lance_total_rival_status", rival ? "desconhecido" : "nao_aplicavel");
		r.put("vencedor_gas_price_wei", gasPriceTransaction == null ? null : gasPriceTransaction.toString());
		r.put("vencedor_gas_preco_efetivo_wei", gasPriceReceipt == null ? null : gasPriceReceipt.toString());
		r.put("vencedor_gas_preco_usado_wei", gasPrice == null ? null : gasPrice.toString());
		r.put("vencedor_gas_fonte_preco", gasPriceReceipt != null ? "recibo_effectiveGasPrice"
				: gasPriceTransaction != null ? "transacao_gasPrice" : "indisponivel");
		r.put("vencedor_gas_limite", texto(vencedor, "gas_limit_vencedor"));
		r.put("vencedor_tipo_transacao", texto(vencedor, "tipo_transacao_vencedor"));
		r.put("vencedor_taxa_wei", taxa == null ? null : taxa.toString());
		r.put("vencedor_gas_usado", gasUsado == null ? null : gasUsado.toString());
		r.put("lance_48club_taxa_gas_arb_wei", taxa == null ? null : taxa.toString());
		r.put("lance_48club_contribuicao_gas_wei",
				contribuicaoGas48Club == null ? null : contribuicaoGas48Club.toString());
		r.put("lance_48club_bnb_direto_wei", pagamento48Club == null ? null : pagamento48Club.toString());
		r.put("lance_48club_pontuacao_minima_wei",
				pontuacao48ClubMinima == null ? null : pontuacao48ClubMinima.toString());
		r.put("lance_48club_pagamento_status", statusPagamento48Club);
		r.put("lance_48club_status",
				!rival ? "nao_aplicavel"
						: taxa == null ? "gas_da_arbitragem_nao_medido"
								: "limite_inferior_unicidade_e_builder_nao_confirmados");
		r.put("fracao_do_vencedor_milha", fracao(pagoDireto, lucro));
		r.put("comparacao_pagamento_status",
				rival ? "somente_componente_direto_lance_total_desconhecido" : "nao_aplicavel_sem_rival_confirmado");
		r.put("pagamentos_a_builder_no_bloco", vencedor.get("pagamentos_a_builder_no_bloco"));
		r.put("pagamentos_48club_no_bloco", vencedor.get("pagamentos_48club_no_bloco"));
		r.put("transferencias_de_valor_no_bloco", vencedor.get("transferencias_de_valor_no_bloco"));
		r.put("transferencias_internas", vencedor.get("transferencias_internas"));
		r.put("fonte_transferencias_internas", vencedor.get("fonte_transferencias_internas"));
		r.put("transferencias_internas_erro", vencedor.get("transferencias_internas_erro"));
		r.put("casamento_do_pagamento", "hash");

		BigInteger faltou = rival && nosso != null && pagoDireto != null && pagoDireto.compareTo(nosso) > 0
				? pagoDireto.subtract(nosso)
				: null;
		r.put("diferenca_pagamento_direto_bnb_wei", faltou == null ? null : faltou.toString());
		r.put("faltou_bnb_wei", faltou == null ? null : faltou.toString());

		String veredito;
		if (levamos) {
			veredito = "levamos";
		} else if (!perdemos) {
			veredito = veredito(classe, false, false, nosso, null, null, null);
		} else if (!rival) {
			veredito = "pool alterada por transacao sem arbitragem rival confirmada";
		} else if (pagoDireto == null || pagoDireto.signum() == 0) {
			veredito = "perdemos: pagamento direto ao builder "
					+ (pagoDireto == null ? "nao medido" : "nao detectado no escopo observado")
					+ "; pagamento total ao builder desconhecido; taxa de gas "
					+ (taxa == null ? "desconhecida" : taxa + " wei");
		} else if (faltou != null) {
			veredito = "pagamento direto do vencedor excedeu o nosso em " + faltou
					+ " wei; lance total do vencedor desconhecido";
		} else {
			veredito = "pagamento direto do vencedor nao excedeu o nosso; lance total e motivo da derrota desconhecidos";
		}
		r.put("veredito", veredito);
		return r;
	}

	static String veredito(String classe, boolean perdemos, boolean levamos, BigInteger nosso, BigInteger pagoVencedor,
			BigInteger faltou, String casamento) {

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
			return "perdemos por lance: vencedor pagou " + pagoVencedor + " wei, nós " + nosso + " wei; faltaram "
					+ faltou + " wei"
					+ ("posicao".equals(casamento) ? " (pagamento casado por posição, não por remetente)" : "");
		}
		return "perdemos com lance igual ou maior: a diferença não é preço, é ordem ou tempo";
	}

	public static Map<String, Object> resumir(List<Map<String, Object>> analises) {
		Map<String, Long> porVeredito = new LinkedHashMap<>();
		BigInteger faltouTotal = BigInteger.ZERO;
		long semVisivel = 0;
		long pagamentoDiretoDetectado = 0;
		long pagamentoDiretoNaoDetectado = 0;
		long pagamentoDiretoNaoMedido = 0;
		long pagamentoDiretoNaoAplicavel = 0;
		long lanceTotalDesconhecido = 0;
		long lance48ClubCalculavel = 0;
		long pagamento48ClubMedidoComTrace = 0;
		for (Map<String, Object> a : analises) {
			String v = (String) a.get("veredito");
			String pagamentoStatus = (String) a.get("pagamento_direto_builder_status");
			String tipo = (String) a.get("tipo_do_vencedor");
			String chave = "arbitragem_do_rival".equals(tipo) && !"detectado".equals(pagamentoStatus)
					? "rival: lance total desconhecido (pagamento direto "
							+ ("nao_medido".equals(pagamentoStatus) ? "nao medido)" : "nao detectado)")
					: v.startsWith("perdemos por lance") ? "perdemos por lance"
							: v.startsWith("perdemos com lance") ? "perdemos com lance igual ou maior"
									: v.startsWith("perdemos: pagamento")
											|| v.startsWith("pagamento direto do vencedor")
													? "pagamento direto insuficiente para provar o lance total"
													: v;
			porVeredito.merge(chave, 1L, Long::sum);
			if (a.get("faltou_bnb_wei") != null) {
				faltouTotal = faltouTotal.add(new BigInteger((String) a.get("faltou_bnb_wei")));
			}
			if ("detectado".equals(pagamentoStatus)) {
				pagamentoDiretoDetectado++;
			} else if ("nao_detectado".equals(pagamentoStatus)) {
				pagamentoDiretoNaoDetectado++;
			} else if ("nao_medido".equals(pagamentoStatus)) {
				pagamentoDiretoNaoMedido++;
			} else {
				pagamentoDiretoNaoAplicavel++;
			}
			if ("desconhecido".equals(a.get("lance_total_rival_status"))) {
				lanceTotalDesconhecido++;
			}
			if (a.get("lance_48club_pontuacao_minima_wei") != null) {
				lance48ClubCalculavel++;
			}
			if ("medido_no_escopo_com_trace".equals(a.get("lance_48club_pagamento_status"))) {
				pagamento48ClubMedidoComTrace++;
			}
			boolean rival = "arbitragem_do_rival".equals(tipo);
			if (rival && !"detectado".equals(pagamentoStatus)) {
				semVisivel++;
			}
		}
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("por_veredito", porVeredito);
		r.put("faltou_total_bnb_wei", faltouTotal.toString());
		r.put("diferenca_total_pagamento_direto_bnb_wei", faltouTotal.toString());
		r.put("perdas_sem_pagamento_visivel", semVisivel);
		r.put("perdas_sem_pagamento_direto_detectado", semVisivel);
		r.put("pagamentos_diretos_builder",
				Map.of("detectados", pagamentoDiretoDetectado, "nao_detectados", pagamentoDiretoNaoDetectado,
						"nao_medidos", pagamentoDiretoNaoMedido, "nao_aplicaveis", pagamentoDiretoNaoAplicavel));
		r.put("perdas_com_lance_total_desconhecido", lanceTotalDesconhecido);
		r.put("perdas_com_limite_inferior_48club_calculavel", lance48ClubCalculavel);
		r.put("pagamento_48club_medidos_com_trace", pagamento48ClubMedidoComTrace);
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