package br.com.node.sincronizacao;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.node.util.Resources;
import br.com.node.vo.AnuncioBloco;

// Registra a cabeça que a rede anuncia, para o cursor saber aonde ir e quanto demorou a chegar.
//
// A cabeça-alvo só anda por anúncio. Não existe consulta "qual é a cabeça?" que vá à rede, e
// não existe caminho frio e quente: o cursor persegue este número, venha o bloco pedido ou
// empurrado, e não tem como saber qual dos dois foi.
//
// A LATÊNCIA é contada do PRIMEIRO anúncio de uma altura até o recibo dela aplicado. É o
// terceiro número que nunca tinha sido entregue, e o projeto anterior mostrou por que ele é o
// que importa: a via de anúncio buscava o recibo em 276 ms e decidia em 4.281 ms, contra um
// bloco de 750 ms — o recibo era rápido e a decisão era de outra pessoa.
//
// Todos os métodos sincronizados: escrevem as threads leitoras da lotação (um anúncio por par
// por bloco) e lê a thread do cursor. Volume de poucos por segundo não justifica mais que isso.
public final class Cabeca {

	// Um par que anuncia uma altura muito à frente da que os outros anunciam mente ou está em
	// outra cadeia. Sem teto, um anúncio só mandaria o cursor pedir blocos que não existem, e
	// cada pedido vazio desanima um par bom.
	private static final int SALTO_MAXIMO = Resources.inteiro("sincronizacao.cabeca.salto.maximo");

	// Janela de amostras, não histórico: o relatório quer a distribuição recente.
	private static final int AMOSTRAS = 4096;

	private final Map<Long, Long> anunciadaEmNanos = new HashMap<>();

	// A LINHA DO TEMPO de cada altura, em ms de relógio de parede: quando o validador fez o bloco (do
	// cabeçalho), quando o primeiro anúncio chegou, quando o cursor o aplicou. É o que diz ONDE vai o
	// tempo entre a fenda nascer e o nosso envio — o `Disparo` completa com decisão e relay. Janela
	// curta: só a cabeça interessa, e quem pergunta pergunta logo.
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

	// As mesmas medidas numa janela CURTA. CICATRIZ de 24/09: a janela de 4.096 amostras (~30 min de
	// blocos) guardava a partida — blocos de 15 a 37 s enquanto o node alcança a cadeia —, e a mediana
	// "2,1 s" de uma corrida de 17 min era quase toda partida. Uma hipótese foi testada contra esse
	// número e o teste não valia. O que decide é o regime: os últimos ~2 min.
	private final br.com.node.util.Amostras recentes = new br.com.node.util.Amostras(256);

	// Quantos blocos o cursor estava ATRÁS da cabeça anunciada quando aplicou cada um: é o atraso do
	// estado com que o garimpo e a projeção trabalham, em blocos de ~450 ms.
	private final br.com.node.util.Amostras atrasoEmBlocos = new br.com.node.util.Amostras(256);
	private final Deque<Long> latenciasEmMicros = new ArrayDeque<>();

	private long alvo;
	private long ignorados;

	// REANCORAGEM. O teto de salto protege de UM par mentindo, mas sem saída ele vira armadilha: depois
	// de uma queda de rede a cadeia andou mais que SALTO_MAXIMO, TODO anúncio verdadeiro passa a ser
	// "salto", e a cabeça fica presa para sempre. MEDIDO em 24/09 às 06:24: sessões caíram a 0–5, e na
	// volta a cabeça ficou parada em 123.732.270 por mais de uma hora, com 137 mil anúncios ignorados
	// (~33/s). Salto isolado segue ignorado; muitos saltos em pouco tempo e COERENTES entre si são a
	// rede — e a cabeça reancora no menor deles, o lado conservador.
	private static final int SALTOS_PARA_REANCORAR = 8;
	private static final long JANELA_DE_SALTOS_NANOS = 5_000_000_000L;
	private final Deque<long[]> saltosRecentes = new ArrayDeque<>();
	private long reancoragens;

	// Quem persegue a cabeça quer saber NA HORA que ela andou (o cursor acorda e pede o bloco).
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

	// Anota o salto e diz se a rede, e não um par, andou: SALTOS_PARA_REANCORAR saltos na janela, todos
	// dentro de SALTO_MAXIMO uns dos outros. Reancorando, o alvo vai para o menor deles.
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

	// Chamado pelo cursor quando a altura foi aplicada. Altura aplicada sem anúncio próprio não
	// vira amostra: ela veio junto de outra, e contar daria latência de um anúncio que não houve.
	public synchronized void aplicada(long numero) {
		aplicada(numero, 0);
	}

	// `blocoMs` é o instante do cabeçalho (0 se desconhecido): com ele, a propagação (validador → nosso
	// primeiro anúncio) e o bloco → aplicado entram na linha do tempo.
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

	// Validador → nosso primeiro anúncio, e validador → bloco aplicado, em ms. Relógio do validador
	// contra o nosso: com NTP a diferença é de poucos ms; negativo seria relógio nosso adiantado.
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
