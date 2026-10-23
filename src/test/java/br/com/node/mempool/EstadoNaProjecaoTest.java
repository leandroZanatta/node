package br.com.node.mempool;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.node.NodeDeTeste;
import br.com.node.cadeia.Crivo;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Fabricas;
import br.com.node.catalogo.Impostos;
import br.com.node.catalogo.Tarifario;
import br.com.node.catalogo.Taxas;
import br.com.node.nucleo.ReservasV2;
import br.com.node.sincronizacao.Cabeca;
import br.com.node.sincronizacao.Cursor;
import br.com.node.sincronizacao.Frente;
import br.com.node.sondagem.Rpc;
import br.com.node.sondagem.Sondagem;
import br.com.node.util.Resources;

class EstadoNaProjecaoTest {

	private static final int ESPERA_DESCOBERTA = 120;
	private static final int POOL_MINIMO = Resources.inteiro("sincronizacao.pedidos.em.voo");
	private static final int RECUO_DE_PARTIDA = 300;
	private static final int APRENDIZADO = 256;
	private static final int POR_LOTE = 16;
	private static final int PERTO_DA_CABECA = 3;
	private static final long DURACAO_MS = 8L * 60 * 1000;
	private static final int TETO_AMOSTRA = 200;

	private static final String GET_RESERVES = "0902f1ac";
	private static final List<String> NOS = List.of("https://bsc-rpc.publicnode.com",
			"https://bsc-dataseed.bnbchain.org");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final Set<String> vistas = ConcurrentHashMap.newKeySet();
	private final List<Double> divergencias = new ArrayList<>();

	@Test
	@DisplayName("a reserva que projetamos bate com a do nó no latest, ou a fenda é defasagem")
	void mediaADivergenciaDeReserva() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		Catalogo catalogo = new Catalogo();
		Taxas taxas = new Taxas();
		Impostos impostos = new Impostos();
		Tarifario tarifario = new Tarifario(catalogo, taxas, new Fabricas());
		Cabeca cabeca = new Cabeca();
		Sondagem sondagem = new Sondagem(catalogo, impostos, new Rpc());

		ObservatorioMempool mempool = new ObservatorioMempool(catalogo, tarifario::taxa,
				(overlay, crua) -> overlay.keySet().forEach(pool -> conferir(pool, catalogo)));

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
				sondagem.iniciar();
				seguir(cursor, cabeca, partida + aprendidos);
			}

		} finally {
			sondagem.close();
			mempool.close();
			node.close();
		}

		relatar();
	}

	
	
	
	
	private void conferir(String pool, Catalogo catalogo) {

		if (vistas.size() >= TETO_AMOSTRA || !vistas.add(pool)) {
			return;
		}

		ReservasV2 nossa = catalogo.reservas(HexFormat.of().parseHex(pool)).orElse(null);
		BigInteger doNo = reservaDoNo(pool);

		if (nossa == null || doNo == null || doNo.signum() <= 0) {
			return;
		}

		double divergencia = nossa.reserva0().subtract(doNo).abs().doubleValue() / doNo.doubleValue();

		synchronized (divergencias) {
			divergencias.add(divergencia);
		}
	}

	private BigInteger reservaDoNo(String pool) {

		String corpo = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_call\",\"params\":[{\"to\":\"0x" + pool
				+ "\",\"data\":\"0x" + GET_RESERVES + "\"},\"latest\"]}";

		for (String no : NOS) {

			try {

				HttpResponse<String> resposta = http.send(
						HttpRequest.newBuilder(URI.create(no)).timeout(Duration.ofSeconds(15))
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
						HttpResponse.BodyHandlers.ofString());

				JsonObject raiz = JsonParser.parseString(resposta.body()).getAsJsonObject();

				if (!raiz.has("result")) {
					continue;
				}

				byte[] bytes = HexFormat.of().parseHex(raiz.get("result").getAsString().substring(2));

				if (bytes.length < 32) {
					continue;
				}

				return new BigInteger(1, Arrays.copyOfRange(bytes, 0, 32));

			} catch (Exception transporte) {
				
			}
		}

		return null;
	}

	private void relatar() {

		List<Double> ordenadas;

		synchronized (divergencias) {
			ordenadas = divergencias.stream().sorted().toList();
		}

		long batem = ordenadas.stream().filter(d -> d < 0.0005).count(); 
		long proximas = ordenadas.stream().filter(d -> d < 0.005).count(); 
		long longe = ordenadas.stream().filter(d -> d >= 0.05).count(); 
		double mediana = ordenadas.isEmpty() ? 0 : ordenadas.get(ordenadas.size() / 2);

		System.out.println();
		System.out.println("==== ANCORAGEM: nossa reserva vs. o nó no latest ====");
		System.out.println("pools conferidas: " + ordenadas.size());
		System.out.println("  divergência mediana: " + Math.round(mediana * 1_000_000) / 10_000.0 + "%");
		System.out.println(
				"  batem (<0,05%): " + batem + " · próximas (<0,5%): " + proximas + " · longe (>=5%): " + longe);
		System.out.println();
		System.out.println(batem * 2 >= ordenadas.size()
				? "MAIORIA BATE -> a fenda de N+1 não some por ancoragem; o 0 corretas é fenda micro."
				: "MAIORIA DIVERGE -> detectamos sobre reserva defasada; a fenda some ao simular no latest (ancoragem).");

		assertTrue(ordenadas.size() >= 20, "amostra pequena demais para concluir: " + ordenadas.size());
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

	private static boolean esperarAnuncio(Cabeca cabeca) throws InterruptedException {

		long fim = System.currentTimeMillis() + ESPERA_DESCOBERTA * 1000L;

		while (cabeca.alvo() == 0 && System.currentTimeMillis() < fim) {
			Thread.sleep(500);
		}

		return cabeca.alvo() > 0;
	}
}