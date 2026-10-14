package br.com.node.ensaio;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.util.Amostras;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// O LANCE ao builder por ROTA, como FRAÇÃO DO LUCRO (por mille, 0–950), aprendida com o desfecho.
//
// Por que fração do lucro e não gwei/gás fixo: para arbs gordas o rival paga em BNB direto ao
// coinbase, não via taxa de gás. Uma fração do lucro escala automaticamente — se o rival paga 30%
// de um lucro de $5, nosso lance de 35% cobre $1,75; o mesmo alvo gwei/gás capado em 3 gwei pagaria
// no máximo $0,82, menos que o rival.
//
// Aprendizado:
//   - incluída → fração desce 10% (pagamos talvez mais que o preciso);
//   - perdeu no N+1 chegando antes, bid real do rival conhecido → fração vai a rival + 5%;
//   - perdeu no N+1 chegando antes, bid desconhecido → fração dobra (converge em ~5 derrotas);
//   - pool mexida sem coinbase identificado → não aprende (provável swap de usuário);
//   - chegou depois do bloco, ou ninguém levou → não mexe (é tempo, não lance).
public final class Lances {

	static final int FRACAO_MIN = 50;  // 5 %
	static final int FRACAO_MAX = 950; // 95 % — mantém margem mínima de 5 %
	private static final BigInteger MWEI = BigInteger.valueOf(1_000_000);

	public record Estado(int fracaoMilha, long vitorias, long derrotasNoPrazo, long derrotasAtrasado) {
	}

	private final Path arquivo;
	private final int inicial;
	private final Map<String, Estado> porRota = new ConcurrentHashMap<>();
	// Bid real do rival medido on-chain para diagnóstico — não mais usado para determinar o lance.
	private final Amostras bidRivalMwei = new Amostras(5_000);

	public Lances(Path arquivo, int fracaoInicial) {

		if (fracaoInicial < FRACAO_MIN || fracaoInicial > FRACAO_MAX) {
			throw new IllegalArgumentException("fracao inicial fora de [" + FRACAO_MIN + "; " + FRACAO_MAX + "]: " + fracaoInicial);
		}

		this.arquivo = arquivo;
		this.inicial = fracaoInicial;
		carregar();
	}

	// Lê `LANCE_FRACAO_MILHA` do ambiente (padrão 900 = 90 %).
	// `PAGAMENTO_BUILDER_BPS` estoura: foi substituído. `LANCE_GWEI` e `ALVO_GLOBAL_MWEI` são ignorados
	// (o lance deixou de ser em gwei/gás e passou a ser uma fração do lucro).
	public static int inicialDoAmbiente() {
		String bps = System.getenv("PAGAMENTO_BUILDER_BPS");
		if (bps != null && !bps.isBlank()) {
			throw new IllegalStateException("PAGAMENTO_BUILDER_BPS não existe mais: use LANCE_FRACAO_MILHA (padrão 900)");
		}
		String milha = System.getenv("LANCE_FRACAO_MILHA");
		if (milha == null || milha.isBlank()) {
			return 900;
		}
		try {
			int v = Integer.parseInt(milha.trim());
			if (v < FRACAO_MIN || v > FRACAO_MAX) {
				throw new IllegalStateException("LANCE_FRACAO_MILHA fora de [" + FRACAO_MIN + "; " + FRACAO_MAX + "]: " + v);
			}
			return v;
		} catch (NumberFormatException e) {
			throw new IllegalStateException("LANCE_FRACAO_MILHA ilegível: " + milha, e);
		}
	}

	public int fracaoPara(String rota) {
		Estado estado = porRota.get(rota);
		return estado == null ? inicial : estado.fracaoMilha();
	}

	// Sem o pagamento do rival medido: `naFrente` é PREÇO DE GÁS da tx dele, não o que ele pagou ao builder, então
	// não serve de lance. Quem não mediu passa só a derrota, e a fração dobra (ver `aprender`).
	public void aprender(Desfechos.Desfecho desfecho) {
		aprender(desfecho, null);
	}

	// `pagamentoDoRivalBnb`: o total em BNB (wei) que o rival enviou ao builder, lido on-chain no bloco dele
	// (`PagamentosDeBloco`). Nulo quando não se mediu.
	public void aprender(Desfechos.Desfecho desfecho, BigInteger pagamentoDoRivalBnb) {

		Desfechos.Envio envio = desfecho.envio();
		String rota = envio.rota();
		Estado antes = porRota.getOrDefault(rota, new Estado(envio.fracaoMilha(), 0, 0, 0));
		Long folga = desfecho.folgaMs();
		boolean perdeuNoN1 = desfecho.classe().equals("rival_n1") || desfecho.classe().equals("pool_mexida_n1");

		Estado depois;

		if (desfecho.classe().equals("incluida")) {
			int nova = Math.max(FRACAO_MIN, antes.fracaoMilha() * 9 / 10);
			depois = new Estado(nova, antes.vitorias() + 1, antes.derrotasNoPrazo(), antes.derrotasAtrasado());

		} else if (perdeuNoN1 && folga != null && folga > 0) {
			int nova;
			if (pagamentoDoRivalBnb != null && envio.lucroBnb().signum() > 0) {
				// Pagamento real do rival (BNB total): a fração do lucro dele, e mira 5% acima.
				bidRivalMwei.anotar(pagamentoDoRivalBnb.divide(MWEI).longValue());
				int rivalFracao = pagamentoDoRivalBnb.multiply(BigInteger.valueOf(1000))
						.divide(envio.lucroBnb())
						.min(BigInteger.valueOf(2000))
						.intValue();
				nova = Math.min(FRACAO_MAX, rivalFracao + 50);
			} else if (desfecho.classe().equals("pool_mexida_n1")) {
				// Provável swap de usuário sem coinbase — não aprende.
				nova = antes.fracaoMilha();
			} else {
				// Bid desconhecido: dobra a fração para convergir em ~5 derrotas.
				nova = Math.min(FRACAO_MAX, antes.fracaoMilha() * 2);
			}
			depois = new Estado(nova, antes.vitorias(), antes.derrotasNoPrazo() + 1, antes.derrotasAtrasado());

		} else if (perdeuNoN1) {
			depois = new Estado(antes.fracaoMilha(), antes.vitorias(), antes.derrotasNoPrazo(), antes.derrotasAtrasado() + 1);
		} else {
			return;
		}

		porRota.put(rota, depois);
		salvar();
	}

	private void carregar() {

		if (!Files.exists(arquivo)) {
			return;
		}

		try {
			JsonObject raiz = JsonParser.parseString(Files.readString(arquivo, StandardCharsets.UTF_8)).getAsJsonObject();
			raiz.entrySet().forEach(e -> {
				if (e.getKey().startsWith("_")) return;
				JsonObject o = e.getValue().getAsJsonObject();
				// Migração: arquivo antigo tinha `alvoWeiPorGas` (gwei/gás). Descarta e usa o inicial.
				int fracao = o.has("fracaoMilha") ? o.get("fracaoMilha").getAsInt() : inicial;
				porRota.put(e.getKey(), new Estado(fracao, o.get("vitorias").getAsLong(),
						o.get("derrotasNoPrazo").getAsLong(), o.get("derrotasAtrasado").getAsLong()));
			});
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("lances: " + arquivo + " ilegível — apague-o de propósito para recomeçar", e);
		}
	}

	private synchronized void salvar() {

		Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
		try {
			JsonObject raiz = new JsonObject();
			porRota.forEach((rota, estado) -> {
				JsonObject o = new JsonObject();
				o.addProperty("fracaoMilha", estado.fracaoMilha());
				o.addProperty("vitorias", estado.vitorias());
				o.addProperty("derrotasNoPrazo", estado.derrotasNoPrazo());
				o.addProperty("derrotasAtrasado", estado.derrotasAtrasado());
				raiz.add(rota, o);
			});
			Files.writeString(temporario, new GsonBuilder().setPrettyPrinting().create().toJson(raiz), StandardCharsets.UTF_8);
			Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			br.com.node.util.Falhas.anotar("lances: gravar " + arquivo, e);
		}
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("rotas", porRota.size());
		resumo.put("fracao_inicial_milha", inicial);
		resumo.put("vitorias", porRota.values().stream().mapToLong(Estado::vitorias).sum());
		resumo.put("derrotas_no_prazo", porRota.values().stream().mapToLong(Estado::derrotasNoPrazo).sum());
		resumo.put("derrotas_atrasado", porRota.values().stream().mapToLong(Estado::derrotasAtrasado).sum());
		resumo.put("bid_rival_mwei_por_gas", bidRivalMwei.resumo());
		resumo.put("arquivo", arquivo.toString());
		return resumo;
	}
}
