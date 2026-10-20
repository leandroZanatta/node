package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class MetricasDoDisparo {

	private final Map<String, br.com.node.util.Amostras> milisPorCaminho = new ConcurrentHashMap<>();
	private final Map<String, br.com.node.util.Amostras> etapasDoEnvio = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> recusasDoRelay = new ConcurrentHashMap<>();

	private final AtomicLong lanceLimitadoPeloSaldo = new AtomicLong();
	private final AtomicLong lanceLimitadoPeloLucro = new AtomicLong();
	private final AtomicLong pagamentosOferecidos = new AtomicLong();

	private BigInteger pagamentoOferecidoTotal = BigInteger.ZERO;
	private long enviadas;
	private long recusadas;
	private String ultimoEnvio;

	synchronized void registrarEnvio(String caminho, long milis, String hash) {
		enviadas++;
		ultimoEnvio = hash;
		milisPorCaminho.computeIfAbsent(caminho, k -> new br.com.node.util.Amostras(2_000)).anotar(milis);
	}

	synchronized void registrarRecusa(String caminho, long milis) {
		recusadas++;
		milisPorCaminho.computeIfAbsent(caminho, k -> new br.com.node.util.Amostras(2_000)).anotar(milis);
	}

	void registrarRecusaDoRelay(String builder, String motivo) {
		recusasDoRelay.computeIfAbsent(builder + ": " + motivo, k -> new AtomicLong()).incrementAndGet();
	}

	void contarSaldoLimitado() {
		lanceLimitadoPeloSaldo.incrementAndGet();
	}

	void contarLucroLimitado() {
		lanceLimitadoPeloLucro.incrementAndGet();
	}

	synchronized void registrarPagamento(BigInteger bnb) {
		pagamentosOferecidos.incrementAndGet();
		pagamentoOferecidoTotal = pagamentoOferecidoTotal.add(bnb);
	}

	void etapa(String nome, long ms) {
		if (ms >= 0) {
			etapasDoEnvio.computeIfAbsent(nome, k -> new br.com.node.util.Amostras(2_000)).anotar(ms);
		}
	}

	synchronized long enviadas() {
		return enviadas;
	}

	synchronized long recusadas() {
		return recusadas;
	}

	synchronized String ultimoEnvio() {
		return ultimoEnvio == null ? "" : ultimoEnvio;
	}

	Map<String, Long> recusasDoRelay() {
		Map<String, Long> copia = new LinkedHashMap<>();
		recusasDoRelay.forEach((motivo, n) -> copia.put(motivo, n.get()));
		return copia;
	}

	Map<String, Object> resumo(BigInteger saldoOperador, BigInteger saldoExecutorWBNB,
			long vereditoSemEstadoDoBloco) {
		Map<String, Object> r = new LinkedHashMap<>();
		milisPorCaminho.forEach((caminho, amostras) -> r.put(caminho, amostras.resumo()));
		Map<String, Object> etapas = new LinkedHashMap<>();
		new TreeMap<>(etapasDoEnvio).forEach((etapa, amostras) -> etapas.put(etapa, amostras.resumo()));
		r.put("linha_do_tempo", etapas);
		synchronized (this) {
			r.put("pagamentos_ao_builder_oferecidos", pagamentosOferecidos.get());
			r.put("pagamento_oferecido_total_bnb", bnb(pagamentoOferecidoTotal));
			r.put("lance_limitado_pelo_saldo", lanceLimitadoPeloSaldo.get());
			r.put("veredito_sem_estado_do_bloco", vereditoSemEstadoDoBloco);
			r.put("lance_limitado_pelo_lucro", lanceLimitadoPeloLucro.get());
			r.put("saldo_operador_bnb", saldoOperador == null ? "nunca lido" : bnb(saldoOperador));
			r.put("saldo_executor_wbnb", bnb(saldoExecutorWBNB));
		}
		return r;
	}

	private static String bnb(BigInteger wei) {
		return new java.math.BigDecimal(wei).divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)))
				.setScale(6, java.math.RoundingMode.DOWN).toPlainString();
	}
}
