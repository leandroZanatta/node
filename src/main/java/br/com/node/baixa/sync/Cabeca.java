package br.com.node.baixa.sync;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.node.util.Resources;
import br.com.node.vo.AnuncioBloco;

public final class Cabeca {

	private static final int SALTO_MAXIMO = Resources.inteiro("sincronizacao.cabeca.salto.maximo");

	private static final int AMOSTRAS = 4096;

	private final Map<Long, Long> anunciadaEmNanos = new HashMap<>();

	public record Momentos(long blocoMs, long anuncioMs, long aplicadoMs) {
	}

	private static final int ALTURAS_NA_LINHA = 512;
	private final Map<Long, Long> anunciadaEmMilis = new HashMap<>();
	private final Map<Long, Momentos> linha = new java.util.LinkedHashMap<>(ALTURAS_NA_LINHA, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Momentos> maisVelha) {
			return size() > ALTURAS_NA_LINHA;
		}
	};
	private final br.com.node.util.Amostras propagacao = new br.com.node.util.Amostras(256);
	private final br.com.node.util.Amostras blocoAteAplicado = new br.com.node.util.Amostras(256);

	private final br.com.node.util.Amostras recentes = new br.com.node.util.Amostras(256);

	private final br.com.node.util.Amostras atrasoEmBlocos = new br.com.node.util.Amostras(256);
	private final Deque<Long> latenciasEmMicros = new ArrayDeque<>();

	private long alvo;
	private long ignorados;

	private static final int SALTOS_PARA_REANCORAR = 8;
	private static final long JANELA_DE_SALTOS_NANOS = 5_000_000_000L;
	private final Deque<long[]> saltosRecentes = new ArrayDeque<>();
	private long reancoragens;

	private volatile Runnable aoAvancar = () -> {
	};

	public void aoAvancar(Runnable aoAvancar) {
		this.aoAvancar = aoAvancar;
	}

	public synchronized void anunciar(AnuncioBloco anuncio) {

		long numero = anuncio.numero();

		if (alvo > 0 && numero > alvo + SALTO_MAXIMO) {
			ignorados++;

			if (!reancorar(numero)) {
				return;
			}
		}

		anunciadaEmNanos.putIfAbsent(numero, System.nanoTime());
		anunciadaEmMilis.putIfAbsent(numero, System.currentTimeMillis());

		if (numero > alvo) {
			alvo = numero;
			aoAvancar.run();
		}
	}

	private boolean reancorar(long numero) {

		long agora = System.nanoTime();
		saltosRecentes.addLast(new long[] { agora, numero });

		while (!saltosRecentes.isEmpty() && agora - saltosRecentes.peekFirst()[0] > JANELA_DE_SALTOS_NANOS) {
			saltosRecentes.removeFirst();
		}

		if (saltosRecentes.size() < SALTOS_PARA_REANCORAR) {
			return false;
		}

		long menor = Long.MAX_VALUE;
		long maior = Long.MIN_VALUE;
		for (long[] salto : saltosRecentes) {
			menor = Math.min(menor, salto[1]);
			maior = Math.max(maior, salto[1]);
		}

		if (maior - menor > SALTO_MAXIMO) {
			return false;
		}

		long antes = alvo;
		alvo = menor;
		reancoragens++;
		saltosRecentes.clear();

		long depois = menor;
		java.util.logging.Logger.getLogger(Cabeca.class.getName()).warning(() -> "cabeça: reancorada de " + antes
				+ " para " + depois + " — a rede andou além do teto de salto (queda de conexão?)");

		return true;
	}

	public synchronized long reancoragens() {
		return reancoragens;
	}

	public synchronized long alvo() {
		return alvo;
	}

	public synchronized void aplicada(long numero) {
		aplicada(numero, 0);
	}

	public synchronized void aplicada(long numero, long blocoMs) {

		long agoraMs = System.currentTimeMillis();
		Long anunciadaMs = anunciadaEmMilis.remove(numero);
		anunciadaEmMilis.keySet().removeIf(altura -> altura < numero);

		if (blocoMs > 0) {
			linha.put(numero, new Momentos(blocoMs, anunciadaMs == null ? 0 : anunciadaMs, agoraMs));
			blocoAteAplicado.anotar(agoraMs - blocoMs);
			if (anunciadaMs != null) {
				propagacao.anotar(anunciadaMs - blocoMs);
			}
		}

		Long anunciada = anunciadaEmNanos.remove(numero);

		anunciadaEmNanos.keySet().removeIf(altura -> altura < numero);

		if (anunciada == null) {
			return;
		}

		latenciasEmMicros.addLast((System.nanoTime() - anunciada) / 1000);
		recentes.anotar((System.nanoTime() - anunciada) / 1_000_000);
		atrasoEmBlocos.anotar(Math.max(0, alvo - numero));

		if (latenciasEmMicros.size() > AMOSTRAS) {
			latenciasEmMicros.removeFirst();
		}
	}

	public synchronized java.util.Optional<Momentos> momentos(long numero) {
		return java.util.Optional.ofNullable(linha.get(numero));
	}

	public Map<String, Object> linhaDoTempo() {
		Map<String, Object> resumo = new java.util.LinkedHashMap<>();
		resumo.put("bloco_ate_anuncio_ms", propagacao.resumo());
		resumo.put("bloco_ate_aplicado_ms", blocoAteAplicado.resumo());
		return resumo;
	}

	public Map<String, Object> latenciaRecente() {
		return recentes.resumo();
	}

	public Map<String, Object> atrasoEmBlocosRecente() {
		return atrasoEmBlocos.resumo();
	}

	public synchronized List<Long> latenciasEmMilis() {
		return latenciasEmMicros.stream().map(micros -> micros / 1000).toList();
	}

	public synchronized long ignorados() {
		return ignorados;
	}
}