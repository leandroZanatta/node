package br.com.node.execucao.disparo;
import br.com.node.execucao.disparo.CustoDeGas;

import br.com.node.execucao.bundle.Salto;
import br.com.node.execucao.bundle.RotaDoModelo;
import br.com.node.execucao.bundle.AbiDoExecutor;
import br.com.node.execucao.bundle.MontadorDeBundle;


import java.io.Closeable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import br.com.node.transformacao.catalogo.Achado;
import br.com.node.transformacao.nucleo.Perna;

public final class Backrun implements Closeable {

	private static final long JANELA_DEDUP_MS = 60_000;

	private static final Logger LOG = Logger.getLogger(Backrun.class.getName());

	private final CustoDeGas custoDeGas;
	private final Disparo disparo;
	private final Gabarito gabarito;

	private final ExecutorService envio = Executors
			.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "backrun-envio"));

	private final ExecutorService envioN1 = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<String, Long> visto = new ConcurrentHashMap<>();

	private final AtomicLong recebidos = new AtomicLong();
	private final AtomicLong abaixoPiso = new AtomicLong();
	private final AtomicLong deduplicados = new AtomicLong();
	private final AtomicLong despachados = new AtomicLong();

	private final AtomicLong melhorFracaoBps = new AtomicLong();

	private static final long JANELA_DO_REGISTRO_MS = 5 * 60_000L;
	private long inicioDaJanela = System.currentTimeMillis();
	private long melhorDaJanelaBps;
	private final AtomicReference<BigInteger> melhorLucro = new AtomicReference<>(BigInteger.ZERO);
	private final AtomicReference<BigInteger> melhorPiso = new AtomicReference<>(BigInteger.ZERO);

	private final AtomicReference<String> melhorRaiz = new AtomicReference<>("");

	private final AtomicLong pagariaOGas = new AtomicLong();

	private static final long SOMBRA_INTERVALO_MS = 20_000;
	private static final long SOMBRA_JANELA_MS = 600_000;
	private final AtomicLong ultimaSombra = new AtomicLong();
	private final Map<String, Long> sombraVista = new ConcurrentHashMap<>();
	private final AtomicLong sombras = new AtomicLong();

	public Backrun(CustoDeGas custoDeGas, Disparo disparo, Gabarito gabarito) {
		this.custoDeGas = custoDeGas;
		this.disparo = disparo;
		this.gabarito = gabarito;
	}

	private volatile java.util.function.LongFunction<java.util.Optional<br.com.node.baixa.sync.Cabeca.Momentos>> momentos = altura -> java.util.Optional
			.empty();
	private final br.com.node.util.Amostras aplicadoAteBackrun = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras blocoAteBackrun = new br.com.node.util.Amostras(2_000);

	public void medirCom(
			java.util.function.LongFunction<java.util.Optional<br.com.node.baixa.sync.Cabeca.Momentos>> momentos) {
		this.momentos = momentos;
	}

	public void considerar(List<Achado> fendas, byte[] cruaPendente) {

		if (cruaPendente == null && !fendas.isEmpty()) {
			long agora = System.currentTimeMillis();
			momentos.apply(fendas.get(0).altura()).ifPresent(m -> {
				aplicadoAteBackrun.anotar(agora - m.aplicadoMs());
				blocoAteBackrun.anotar(agora - m.blocoMs());
			});
		}

		recebidos.addAndGet(fendas.size());
		Achado melhor = null;
		Achado melhorRecusada = null;

		for (Achado fenda : fendas) {

			gabarito.anotar(fenda, cruaPendente != null);

			BigInteger gasDaCauda = custoDeGas.estimarGasDasTravessias(fenda.travessias(), fenda.ciclo().pernas());
			byte[] raiz = fenda.ciclo().pernas().get(0).entra();
			Optional<BigInteger> piso = custoDeGas.piso(raiz, fenda.oportunidade().entrada(), gasDaCauda);

			BigInteger lucro = fenda.oportunidade().lucro();

			if (piso.isEmpty() || lucro.compareTo(piso.get()) < 0) {
				abaixoPiso.incrementAndGet();
				anotarRecusa(fenda, gasDaCauda, lucro, piso.orElse(null));
				boolean pagaOGas = piso.isPresent() && lucro.compareTo(custoDeGas.custoDoGas(raiz, gasDaCauda)) > 0;
				if (pagaOGas && (melhorRecusada == null || emBnb(fenda).compareTo(emBnb(melhorRecusada)) > 0)) {
					melhorRecusada = fenda;
				}
				continue;
			}

			if (melhor == null || emBnb(fenda).compareTo(emBnb(melhor)) > 0) {
				melhor = fenda;
			}
		}

		if (melhor == null) {
			if (melhorRecusada != null && cruaPendente == null) {
				sombra(melhorRecusada);
			}
			return;
		}

		if (repetida(melhor)) {
			return;
		}

		Achado escolhido = melhor;
		despachados.incrementAndGet();
		LOG.info(() -> "backrun: despacho lucro " + escolhido.oportunidade().lucro() + " wei, "
				+ escolhido.ciclo().pernas().size() + " pernas" + (cruaPendente == null ? " (cauda)" : " (n+1)"));
		envioN1.execute(() -> disparo.disparar(escolhido, cruaPendente));
	}

	private void anotarRecusa(Achado fenda, BigInteger gas, BigInteger lucro, BigInteger piso) {

		if (piso == null) {
			return;
		}

		BigInteger custo = custoDeGas.custoDoGas(fenda.ciclo().pernas().get(0).entra(), gas);
		BigInteger bps = lucro.multiply(BigInteger.valueOf(10_000)).divide(piso);

		if (lucro.compareTo(custo) > 0) {
			pagariaOGas.incrementAndGet();
		}

		synchronized (melhorFracaoBps) {

			long agora = System.currentTimeMillis();
			if (agora - inicioDaJanela >= JANELA_DO_REGISTRO_MS) {
				inicioDaJanela = agora;
				melhorDaJanelaBps = 0;
			}

			if (bps.longValue() > melhorFracaoBps.get()) {
				melhorFracaoBps.set(bps.longValue());
				melhorLucro.set(lucro);
				melhorPiso.set(piso);
			}

			if (bps.longValue() <= melhorDaJanelaBps) {
				return;
			}

			melhorDaJanelaBps = bps.longValue();
			melhorRaiz.set(HexFormat.of().formatHex(fenda.ciclo().pernas().get(0).entra()));

			List<String> pools = new ArrayList<>();
			for (Perna perna : fenda.ciclo().pernas()) {
				pools.add(HexFormat.of().formatHex(perna.pool()));
			}

			LOG.info(() -> "piso: melhor recusa em " + bps + " bps (gás " + gas + " · custo " + custo + " · piso "
					+ piso + " · lucro " + lucro + " · líquido " + lucro.subtract(custo)
					+ " — custo, piso, lucro e líquido em wei DO TOKEN DA RAIZ) · raiz "
					+ HexFormat.of().formatHex(fenda.ciclo().pernas().get(0).entra()) + " · "
					+ fenda.ciclo().pernas().size() + " pernas · entrada " + fenda.oportunidade().entrada()
					+ (lucro.compareTo(custo) > 0 ? " · cobre o gás com sobra" : " · não cobre o gás com sobra")
					+ " · pools " + String.join(",", pools));
		}
	}

	private void sombra(Achado achado) {

		long agora = System.currentTimeMillis();
		long ultima = ultimaSombra.get();

		if (agora - ultima < SOMBRA_INTERVALO_MS) {
			return;
		}

		if (sombraVista.size() > 10_000) {
			sombraVista.values().removeIf(quando -> agora - quando > SOMBRA_JANELA_MS);
		}

		String assinatura = assinatura(achado);
		Long vista = sombraVista.get(assinatura);

		if (vista != null && agora - vista < SOMBRA_JANELA_MS) {
			return;
		}

		if (!ultimaSombra.compareAndSet(ultima, agora)) {
			return;
		}

		sombraVista.put(assinatura, agora);
		sombras.incrementAndGet();
		envio.execute(() -> disparo.examinarSemEnviar(achado));
	}

	private BigInteger emBnb(Achado fenda) {
		byte[] raiz = fenda.ciclo().pernas().get(0).entra();
		return custoDeGas.paraBnb(raiz, fenda.oportunidade().lucro()).orElseThrow(() -> new IllegalStateException(
				"fenda com piso e sem câmbio BNB para a raiz " + HexFormat.of().formatHex(raiz)));
	}

	private static String assinatura(Achado achado) {

		List<String> pools = new ArrayList<>();
		for (Perna perna : achado.ciclo().pernas()) {
			pools.add(HexFormat.of().formatHex(perna.pool()));
		}
		pools.sort(null);

		return String.join("-", pools);
	}

	private boolean repetida(Achado achado) {

		long agora = System.currentTimeMillis();

		if (visto.size() > 10_000) {
			visto.values().removeIf(quando -> agora - quando > JANELA_DEDUP_MS);
		}

		String assinatura = assinatura(achado) + "@" + achado.oportunidade().entrada() + "/"
				+ achado.oportunidade().lucro();

		Long ultimo = visto.get(assinatura);

		if (ultimo != null && agora - ultimo < JANELA_DEDUP_MS) {
			deduplicados.incrementAndGet();
			return true;
		}

		visto.put(assinatura, agora);
		return false;
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("recebidos", recebidos.get());
		resumo.put("cauda_aplicado_ate_backrun_ms", aplicadoAteBackrun.resumo());
		resumo.put("cauda_bloco_ate_backrun_ms", blocoAteBackrun.resumo());
		resumo.put("abaixo_piso", abaixoPiso.get());
		resumo.put("pagaria_o_gas", pagariaOGas.get());
		resumo.put("melhor_fracao_do_piso_bps", melhorFracaoBps.get());
		resumo.put("melhor_lucro", melhorLucro.get().toString());
		resumo.put("melhor_piso", melhorPiso.get().toString());
		resumo.put("melhor_piso_raiz", melhorRaiz.get());
		resumo.put("cambio_usdt_bnb", custoDeGas.cambioUsdtPorBnb().map(BigInteger::toString).orElse("nunca medido"));
		resumo.put("deduplicados", deduplicados.get());
		resumo.put("despachados", despachados.get());
		resumo.put("autopsias_sombra", sombras.get());
		return resumo;
	}

	@Override
	public void close() {
		envio.shutdownNow();
		envioN1.shutdownNow();
	}
}