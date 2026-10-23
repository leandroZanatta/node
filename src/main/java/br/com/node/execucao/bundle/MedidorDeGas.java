package br.com.node.execucao.bundle;
import br.com.node.baixa.rpc.RpcDoEnsaio;


import java.io.Closeable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class MedidorDeGas implements Closeable {

	private static final Logger LOG = Logger.getLogger(MedidorDeGas.class.getName());

	private static final int TENTATIVAS = 5;
	private static final long ESPERA_MS = 3_000;

	static final int AMOSTRAS_PARA_CALIBRAR = 5;

	private record Amostra(BigInteger estimado, BigInteger medido, boolean sucesso) {
	}

	private final RpcDoEnsaio rpc;
	private final ExecutorService medicao = Executors.newSingleThreadExecutor(t -> new Thread(t, "gas-medido"));

	private final List<Amostra> amostras = Collections.synchronizedList(new ArrayList<>());
	private final AtomicLong semRecibo = new AtomicLong();

	public MedidorDeGas(RpcDoEnsaio rpc) {
		this.rpc = rpc;
	}

	public void anotar(BigInteger estimado, String hash) {

		if (rpc == null || estimado == null || estimado.signum() <= 0 || hash == null || hash.isBlank()) {
			return;
		}

		medicao.execute(() -> {
			try {
				buscar(estimado, hash);
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("medição de gás pelo recibo", e);
			}
		});
	}

	private void buscar(BigInteger estimado, String hash) {

		for (int tentativa = 0; tentativa < TENTATIVAS; tentativa++) {

			Optional<RpcDoEnsaio.Recibo> recibo = rpc.recibo(hash);

			if (recibo.isPresent()) {

				BigInteger medido = recibo.get().gasUsado();

				registrar(estimado, medido, recibo.get().sucesso());

				LOG.info(() -> "gas: medido " + medido + " · estimado " + estimado + " · desvio "
						+ desvioPercentual(estimado, medido) + "%"
						+ (recibo.get().sucesso() ? "" : " (REVERTEU — gás pago mesmo assim)"));

				return;
			}

			dormir();
		}

		semRecibo.incrementAndGet();
		LOG.fine(() -> "gas: sem recibo para " + hash + " (bundle não incluído ou tx pendente)");
	}

	public void registrar(BigInteger estimado, BigInteger medido, boolean sucesso) {
		amostras.add(new Amostra(estimado, medido, sucesso));
		int fator = fatorMilesimos(amostrasCopia());
		if (fator > 0) {
			aoCalibrar.accept(fator);
		}
	}

	private volatile java.util.function.IntConsumer aoCalibrar = fator -> {
	};

	public void aoCalibrar(java.util.function.IntConsumer ouvinte) {
		this.aoCalibrar = ouvinte;
	}

	private List<Amostra> amostrasCopia() {
		synchronized (amostras) {
			return List.copyOf(amostras);
		}
	}

	static int fatorMilesimos(List<Amostra> copia) {
		List<BigInteger> razoes = copia.stream().filter(a -> a.estimado().signum() > 0)
				.map(a -> a.medido().multiply(BigInteger.valueOf(1000)).divide(a.estimado())).sorted().toList();
		if (razoes.size() < AMOSTRAS_PARA_CALIBRAR) {
			return 0;
		}
		return razoes.get(razoes.size() / 2).intValue();
	}

	private static long desvioPercentual(BigInteger estimado, BigInteger medido) {

		if (estimado.signum() <= 0) {
			return 0;
		}

		return medido.subtract(estimado).multiply(BigInteger.valueOf(100)).divide(estimado).longValueExact();
	}

	private static void dormir() {

		try {
			Thread.sleep(ESPERA_MS);
		} catch (InterruptedException interrompida) {
			Thread.currentThread().interrupt();
		}
	}

	public Map<String, Object> resumo() {

		List<Amostra> copia;
		synchronized (amostras) {
			copia = List.copyOf(amostras);
		}

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("amostras", (long) copia.size());
		resumo.put("sem_recibo", semRecibo.get());

		if (copia.isEmpty()) {
			return resumo;
		}

		List<BigInteger> medidos = copia.stream().map(Amostra::medido).sorted().toList();
		List<BigInteger> estimados = copia.stream().map(Amostra::estimado).sorted().toList();
		List<Long> desvios = copia.stream().map(a -> desvioPercentual(a.estimado(), a.medido())).sorted().toList();

		resumo.put("medido_mediana", medidos.get(medidos.size() / 2).toString());
		resumo.put("estimado_mediana", estimados.get(estimados.size() / 2).toString());
		resumo.put("desvio_mediano_pct", desvios.get(desvios.size() / 2));
		resumo.put("desvio_pior_pct", desvios.get(desvios.size() - 1));
		resumo.put("revertidas", copia.stream().filter(a -> !a.sucesso()).count());

		return resumo;
	}

	@Override
	public void close() {
		medicao.shutdownNow();
	}
}