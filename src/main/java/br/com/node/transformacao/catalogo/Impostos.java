package br.com.node.transformacao.catalogo;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.vo.Log;

public final class Impostos {

	private static final BigInteger PONTOS_BASE = BigInteger.valueOf(10_000);

	private final Map<String, Long> maiorRetido = new ConcurrentHashMap<>();

	private final Map<String, Log> repasses = new HashMap<>();

	private final Map<String, Integer> provado = new ConcurrentHashMap<>();

	private static final int HONEYPOT = -1;

	private static final int SUSPEITO = -2;

	private final Map<String, Integer> compra = new ConcurrentHashMap<>();

	private volatile java.util.function.Consumer<br.com.node.vo.ProvaDeImposto> aoProvar = prova -> {
	};

	private String transacaoCorrente = "";

	public void observar(Transicao transicao) {

		String transacao = transicao.altura() + ":" + transicao.transacao();

		if (!transacao.equals(transacaoCorrente)) {
			repasses.clear();
			transacaoCorrente = transacao;
		}

		Log log = transicao.log();

		if (log.assinadoPor(Eventos.TRANSFER) && log.topicos().size() >= 3) {
			repasses.put(rota(Eventos.enderecoDoTopico(log, 1), Eventos.enderecoDoTopico(log, 2)), log);
			return;
		}

		if (log.assinadoPor(Eventos.SWAP_V2) && log.topicos().size() >= 3) {
			medirCompra(log);
		}
	}

	public java.util.OptionalInt venda(byte[] pool, byte[] token) {

		Integer forte = provado.get(chave(pool, token));

		if (forte != null) {
			return forte >= 0 ? java.util.OptionalInt.of(forte) : java.util.OptionalInt.empty();
		}

		Long fraco = maiorRetido.get(HexFormat.of().formatHex(token));

		if (fraco != null && fraco > 0) {
			vendaConservadora.incrementAndGet();
			return java.util.OptionalInt.of(Math.toIntExact(fraco));
		}

		vendaAssumida.incrementAndGet();
		return java.util.OptionalInt.of(0);
	}

	private final java.util.concurrent.atomic.AtomicLong vendaAssumida = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong vendaConservadora = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong compraAssumida = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong compraConservadora = new java.util.concurrent.atomic.AtomicLong();

	public Map<String, Long> assumidos() {
		return Map.of("venda_assumida_zero", vendaAssumida.get(), "compra_assumida_zero", compraAssumida.get(),
				"venda_pelo_maior_visto", vendaConservadora.get(), "compra_pelo_maior_visto", compraConservadora.get());
	}

	public java.util.OptionalInt compra(byte[] pool, byte[] token) {

		Integer estado = provado.get(chave(pool, token));

		if (estado != null && estado < 0) {
			return java.util.OptionalInt.empty();
		}

		Integer forte = compra.get(chave(pool, token));

		if (forte != null) {
			return java.util.OptionalInt.of(forte);
		}

		Long fraco = maiorRetido.get(HexFormat.of().formatHex(token));

		if (estado != null) {
			return java.util.OptionalInt.of(Math.max(fraco == null ? 0 : Math.toIntExact(fraco), estado));
		}

		if (fraco != null && fraco > 0) {
			compraConservadora.incrementAndGet();
			return java.util.OptionalInt.of(Math.toIntExact(fraco));
		}

		compraAssumida.incrementAndGet();
		return java.util.OptionalInt.of(0);
	}

	public boolean jaProvado(byte[] pool, byte[] token) {

		Integer estado = provado.get(chave(pool, token));

		return estado != null && estado != SUSPEITO;
	}

	public boolean suspeito(byte[] pool, byte[] token) {

		Integer estado = provado.get(chave(pool, token));

		return estado != null && estado == SUSPEITO;
	}

	public void suspeitar(byte[] pool, byte[] token) {
		provado.put(chave(pool, token), SUSPEITO);
	}

	public void medidaVenda(byte[] pool, byte[] token, int bps) {
		provado.put(chave(pool, token), bps);
		aoProvar.accept(new br.com.node.vo.ProvaDeImposto(pool, token, bps, false));
	}

	public void medidaCompra(byte[] pool, byte[] token, int bps) {

		if (bps < 0) {
			throw new IllegalArgumentException("imposto de compra negativo para " + chave(pool, token) + ": " + bps);
		}

		compra.put(chave(pool, token), bps);
		aoProvar.accept(new br.com.node.vo.ProvaDeImposto(pool, token, bps, true));
	}

	public void honeypot(byte[] pool, byte[] token) {
		provado.put(chave(pool, token), HONEYPOT);
		aoProvar.accept(new br.com.node.vo.ProvaDeImposto(pool, token, HONEYPOT, false));
	}

	public void aoProvar(java.util.function.Consumer<br.com.node.vo.ProvaDeImposto> ouvinte) {
		this.aoProvar = ouvinte;
	}

	public void semear(java.util.List<br.com.node.vo.ProvaDeImposto> provas) {
		provas.forEach(
				prova -> (prova.compra() ? compra : provado).put(chave(prova.pool(), prova.token()), prova.bps()));
	}

	private static String chave(byte[] pool, byte[] token) {
		return HexFormat.of().formatHex(pool) + ":" + HexFormat.of().formatHex(token);
	}

	public long quantosProvados() {
		return provado.size();
	}

	public long quantosSuspeitos() {
		return provado.values().stream().filter(bps -> bps == SUSPEITO).count();
	}

	public long quantosLimpos() {
		return maiorRetido.values().stream().filter(retido -> retido == 0).count();
	}

	public long quantosTributados() {
		return maiorRetido.values().stream().filter(retido -> retido > 0).count();
	}

	private void medirCompra(Log swap) {

		BigInteger saida0 = Eventos.palavra(swap, 2);
		BigInteger saida1 = Eventos.palavra(swap, 3);

		if (saida0.signum() > 0 == saida1.signum() > 0) {
			return;
		}

		Log recebimento = repasses.get(rota(swap.endereco(), Eventos.enderecoDoTopico(swap, 2)));

		if (recebimento == null) {
			return;
		}

		BigInteger enviado = saida0.max(saida1);
		BigInteger recebido = Eventos.palavra(recebimento, 0);

		if (recebido.compareTo(enviado) > 0) {
			return;
		}

		long retido = enviado.subtract(recebido).multiply(PONTOS_BASE).add(enviado).subtract(BigInteger.ONE)
				.divide(enviado).longValueExact();

		maiorRetido.merge(HexFormat.of().formatHex(recebimento.endereco()), retido, Math::max);
	}

	private static String rota(byte[] de, byte[] para) {
		return HexFormat.of().formatHex(de) + "|" + HexFormat.of().formatHex(para);
	}
}