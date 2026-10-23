package br.com.node.execucao.bundle;

import java.io.Closeable;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import br.com.node.baixa.rpc.RpcDoEnsaio;
import br.com.node.transformacao.catalogo.Impostos;
import br.com.node.transformacao.catalogo.Quarentena;
import br.com.node.util.Falhas;
import br.com.node.util.Keccak;

public final class VereditorDoContrato implements Closeable {

	private static final Logger LOG = Logger.getLogger(VereditorDoContrato.class.getName());

	private static final Map<String, String> NOMES = Map.of("7978d88b", "EmprestimoNaoV2", "54c59343", "NaoPagou",
			"f2beb76b", "SemLucro", "7ad3ddd8", "TokenForaDaPool", "b53161a2", "RotaDescontinua", "e7eb56ec",
			"NaoAutorizado", "5212cba1", "CurrencyNotSettled");

	private static final java.time.Duration PENA_DO_TOKEN_QUE_NAO_SE_MOVE = java.time.Duration.ofHours(3);
	private static final String WRAPPED_ERROR = "90bfb865";
	private static final String FALHA_NA_CHAMADA = "033d7ade";
	private static final String SAFE_CAST_OVERFLOW = "93dafdf1";
	private static final String CURRENCY_NOT_SETTLED = "5212cba1";

	private final RpcDoEnsaio rpc;
	private final Quarentena quarentena;
	private final Impostos impostos;
	private final ExecutorService thread = Executors.newSingleThreadExecutor(t -> new Thread(t, "autopsia"));
	private final Map<String, AtomicLong> motivos = new ConcurrentHashMap<>();
	private final AtomicLong vereditoSemEstadoDoBloco = new AtomicLong();

	private volatile RpcDoEnsaio rpcAlternativo;

	public VereditorDoContrato(RpcDoEnsaio rpc, Quarentena quarentena, Impostos impostos) {
		this.rpc = rpc;
		this.quarentena = quarentena;
		this.impostos = impostos;
	}

	public void rpcAlternativo(RpcDoEnsaio alternativo) {
		this.rpcAlternativo = alternativo;
	}

	public java.util.Optional<com.google.gson.JsonObject> rastrearChamada(String endereco, String contrato, String dadosHex,
			long altura) {
		RpcDoEnsaio fonte = rpcAlternativo != null ? rpcAlternativo : rpc;
		return fonte.rastrearChamada(endereco, contrato, dadosHex, "0x" + Long.toHexString(altura));
	}

	public String anotar(String endereco, String contrato, List<Salto> rota, String dadosHex, long altura) {
		try {
			RpcDoEnsaio fonte = rpcAlternativo != null ? rpcAlternativo : rpc;
			String bloco = "0x" + Long.toHexString(altura);
			if (!fonte.esperarBloco(altura, 15, 200)) {
				vereditoSemEstadoDoBloco.incrementAndGet();
				return "sem_veredito";
			}
			RpcDoEnsaio.Veredicto veredicto = fonte.veredito(endereco, contrato, dadosHex, bloco);
			for (int i = 0; i < 3 && VereditorDeRelay.blocoAindaNaoChegou(veredicto.texto()); i++) {
				Thread.sleep(700);
				veredicto = fonte.veredito(endereco, contrato, dadosHex, bloco);
			}
			if (VereditorDeRelay.blocoAindaNaoChegou(veredicto.texto()) || VereditorDeRelay.estadoIndisponivel(veredicto.texto())) {
				vereditoSemEstadoDoBloco.incrementAndGet();
				return "sem_veredito";
			}
			String nome = veredicto.texto().isBlank() ? NOMES.getOrDefault(veredicto.seletor(), veredicto.seletor())
					: veredicto.texto();
			motivos.computeIfAbsent(nome, k -> new AtomicLong()).incrementAndGet();
			if (naoSeMove(veredicto.texto())) {
				suspeitarDaRota(rota, veredicto.texto());
			}
			condenarGerentesSeNaoQuitado(rota, veredicto.seletor(), nome);
			return nome;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "sem_veredito";
		} catch (RuntimeException e) {
			Falhas.anotar("veredito do contrato", e);
			return "sem_veredito";
		}
	}

	public void submeterAsync(Runnable tarefa) {
		thread.submit(tarefa);
	}

	public static boolean naoSeMove(String texto) {
		String min = texto == null ? "" : texto.toLowerCase(java.util.Locale.ROOT);
		return min.contains("transfer_failed") || min.contains("transfer_from_failed");
	}

	public static boolean falhaNaChamada(String veredito) {
		String texto = veredito == null ? "" : veredito.toLowerCase(java.util.Locale.ROOT);
		return texto.equals("0x" + FALHA_NA_CHAMADA) || texto.contains("0x" + FALHA_NA_CHAMADA);
	}

	public void suspeitarDaRota(List<Salto> rota, String motivo) {
		for (Salto salto : rota) {
			if (salto.deGerente()) {
				continue;
			}
			byte[] pool = HexFormat.of().parseHex(semPrefixo(salto.pool()));
			impostos.suspeitar(pool, HexFormat.of().parseHex(semPrefixo(salto.entra())));
			impostos.suspeitar(pool, HexFormat.of().parseHex(semPrefixo(salto.sai())));
		}
		LOG.warning(() -> "vereditor: token não se move (" + motivo + ") — " + rota.size()
				+ " pools sob suspeita; sonda remede");
	}

	public void condenarGerentesSeNaoQuitado(List<Salto> rota, String seletor, String veredito) {
		String texto = veredito == null ? "" : veredito.toLowerCase(java.util.Locale.ROOT);
		boolean embrulhado = WRAPPED_ERROR.equals(seletor) || texto.startsWith("0x" + WRAPPED_ERROR);
		boolean safeCast = SAFE_CAST_OVERFLOW.equals(seletor) || texto.contains(SAFE_CAST_OVERFLOW);
		boolean naoQuitado = CURRENCY_NOT_SETTLED.equals(seletor) || texto.contains(CURRENCY_NOT_SETTLED);

		if ((!embrulhado && !safeCast && !naoQuitado) || rota.stream().noneMatch(Salto::deGerente)) {
			return;
		}

		String culpado = embrulhado && texto.length() >= 2 + 8 + 64 ? texto.substring(2 + 8 + 24, 2 + 8 + 64) : null;
		int condenadas = 0;

		for (Salto salto : rota) {
			if (!salto.deGerente()) {
				continue;
			}
			String chave = HexFormat.of().formatHex(salto.chave());
			if (culpado != null && !chave.contains(culpado)) {
				continue;
			}
			String razao = embrulhado ? "WrappedError do token 0x" + culpado
					: safeCast ? "SafeCastOverflow (token taxado em pool V4)"
							: naoQuitado ? "CurrencyNotSettled (token com fee-on-transfer no settle V4)"
									: "FalhaNaChamada";
			quarentena.condenar(Keccak.hash(salto.chave()), PENA_DO_TOKEN_QUE_NAO_SE_MOVE,
					razao + " em rota por gerente");
			condenadas++;
		}

		int total = condenadas;
		if (total > 0) {
			LOG.warning(() -> "vereditor: token não se move em rota por gerente — " + total
					+ " pool(s) de gerente em quarentena" + (culpado != null ? " (token 0x" + culpado + ")" : ""));
		}
	}

	public Map<String, Long> motivos() {
		Map<String, Long> copia = new LinkedHashMap<>();
		motivos.forEach((nome, n) -> copia.put(nome, n.get()));
		return copia;
	}

	public long vereditoSemEstadoDoBloco() {
		return vereditoSemEstadoDoBloco.get();
	}

	private static String semPrefixo(String hex) {
		return hex.startsWith("0x") ? hex.substring(2) : hex;
	}

	@Override
	public void close() {
		thread.shutdownNow();
	}
}