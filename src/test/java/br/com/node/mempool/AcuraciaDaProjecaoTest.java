package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.cadeia.Crivo;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Fabricas;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.Tarifario;
import br.com.node.catalogo.Taxas;
import br.com.node.sincronizacao.Cabeca;
import br.com.node.sincronizacao.Cursor;
import br.com.node.sincronizacao.Frente;
import br.com.node.sondagem.Rpc;
import br.com.node.sondagem.Sondagem;
import br.com.node.util.Resources;

// Liveness: a projeção da mempool SÓ vale se ela acertar quais transações se concretizam. Segue a
// cabeça, registra os swaps que o modelo PROJETA (decodifica, mapeia para pool conhecida e projeta
// o efeito) e, no fim, confere na cadeia quantos de fato entraram num bloco. Passa com > 85% de
// acerto — abaixo disso, a projeção está prevendo fenda sobre swap que não consolida, e o trabalho
// é entender no fonte por que uma tx projeta e outra não, e por que uma consolida e outra não.
class AcuraciaDaProjecaoTest {

	private static final int ESPERA_DESCOBERTA = 120;
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");

	private static final int RECUO_DE_PARTIDA = 300;
	private static final int APRENDIZADO = 256;
	private static final int POR_LOTE = 16;
	private static final int PERTO_DA_CABECA = 3;

	private static final long DURACAO_MS = 10L * 60 * 1000;
	// Só se cobra inclusão de quem teve tempo de minerar: transação vista nos últimos segundos ainda
	// pode estar pendente, e contá-la como "não consolidou" seria injusto.
	private static final long CARENCIA_MS = 30_000;

	private static final double PISO = 0.85;

	private static final List<String> NOS = List.of("https://bsc-rpc.publicnode.com",
			"https://bsc-dataseed.bnbchain.org");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	// hash projetado -> instante em que o vimos.
	private final Map<String, Long> projetadas = new ConcurrentHashMap<>();

	@Test
	@DisplayName("mais de 85% dos swaps que projetamos consolidam em bloco")
	void projecaoAcertaAConsolidacao() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		Taxas taxas = new Taxas();
		Impostos impostos = new Impostos();
		Tarifario tarifario = new Tarifario(catalogo, taxas, new Fabricas());
		Cabeca cabeca = new Cabeca();
		Sondagem sondagem = new Sondagem(catalogo, impostos, new Rpc());

		ObservatorioMempool mempool = new ObservatorioMempool(catalogo, tarifario::taxa,
				(overlay, crua) -> TransacaoPendente.decodificar(crua)
						.ifPresent(tx -> projetadas.putIfAbsent(hex(tx.hash()), System.currentTimeMillis())));

		try {

			node.iniciar();
			node.lotar(Crivo.corteDeHoje()::aceita, cabeca::anunciar, mempool::observar, mempool::observar);
			System.out.println("pool: " + node.esperarPool(POOL_MINIMO, ESPERA_DESCOBERTA) + " ativas");

			if (!esperarAnuncio(cabeca)) {
				System.out.println("RELATORIO nenhum anúncio de cabeça — sem rede, nada a medir");
				return;
			}

			long partida = cabeca.alvo() - RECUO_DE_PARTIDA;

			try (Frente frente = new Frente(node.sessoes, POR_LOTE);
					Cursor cursor = new Cursor(frente, catalogo, transicao -> {
						taxas.observar(transicao);
						impostos.observar(transicao);
					})) {

				frente.abrir(cursor::montar, POOL_MINIMO);

				int aprendidos = cursor.percorrer(partida, APRENDIZADO, POR_LOTE);
				System.out.println("aprendizado: " + aprendidos + " blocos · " + catalogo.poolsV2() + " V2 · "
						+ taxas.quantasProvadas() + " taxas");

				sondagem.iniciar();
				seguir(cursor, cabeca, partida + aprendidos);
			}

		} finally {
			sondagem.close();
			mempool.close();
			node.close();
		}

		conferir();
	}

	private void seguir(Cursor cursor, Cabeca cabeca, long comecarEm) throws InterruptedException {

		long fim = System.currentTimeMillis() + DURACAO_MS;
		long ultima = comecarEm - 1;

		while (System.currentTimeMillis() < fim) {

			long alvo = cabeca.alvo() - PERTO_DA_CABECA;

			if (alvo > ultima) {
				ultima += cursor.percorrer(ultima + 1, (int) Math.min(alvo - ultima, 512), POR_LOTE);
			}

			Thread.sleep(1500);
		}
	}

	// Espera a carência (para os últimos verem bloco) e confere a inclusão de cada projetado que
	// teve tempo de minerar. Acerto = tem recibo / projetados conferíveis.
	private void conferir() throws InterruptedException {

		Thread.sleep(CARENCIA_MS);

		long corte = System.currentTimeMillis() - CARENCIA_MS;
		int conferiveis = 0;
		int consolidados = 0;

		for (Map.Entry<String, Long> entrada : projetadas.entrySet()) {

			if (entrada.getValue() > corte) {
				continue;
			}

			conferiveis++;

			if (consolidou(entrada.getKey())) {
				consolidados++;
			}
		}

		double acerto = conferiveis == 0 ? 0 : (double) consolidados / conferiveis;

		System.out.println();
		System.out.println("==== ACURÁCIA DA PROJEÇÃO ====");
		System.out.println("projetados conferíveis: " + conferiveis + " · consolidaram: " + consolidados
				+ " · acerto: " + Math.round(acerto * 1000) / 10.0 + "%");

		assertTrue(conferiveis >= 20, "amostra pequena demais para afirmar acerto: " + conferiveis);
		assertTrue(acerto >= PISO, "acerto " + Math.round(acerto * 1000) / 10.0 + "% abaixo do piso de "
				+ (PISO * 100) + "%: a projeção prevê fenda sobre swap que não consolida");
	}

	// Consolidou = tem recibo com bloco. Recibo nulo é pendente/derrubado/substituído.
	private boolean consolidou(String hash) {

		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getTransactionReceipt\",\"params\":[\"0x" + hash
				+ "\"]}";

		for (String no : NOS) {

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(15))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
						HttpResponse.BodyHandlers.ofString());

				if (resposta.statusCode() / 100 != 2) {
					continue;
				}

				JsonObject raiz = JsonParser.parseString(resposta.body()).getAsJsonObject();

				return raiz.has("result") && raiz.get("result").isJsonObject()
						&& raiz.getAsJsonObject("result").has("blockNumber");

			} catch (Exception transporte) {
				// próximo nó
			}
		}

		return false;
	}

	private static boolean esperarAnuncio(Cabeca cabeca) throws InterruptedException {

		long fim = System.currentTimeMillis() + ESPERA_DESCOBERTA * 1000L;

		while (cabeca.alvo() == 0 && System.currentTimeMillis() < fim) {
			Thread.sleep(500);
		}

		return cabeca.alvo() > 0;
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
