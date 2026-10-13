package br.com.node.sessao;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

import br.com.node.util.Resources;
import br.com.node.vo.AnuncioBloco;
import br.com.node.vo.Desconhecida;
import br.com.node.vo.ForkId;
import br.com.node.vo.MensagemRlpx;
import br.com.node.vo.NewBlock;
import br.com.node.vo.NewBlockHashes;
import br.com.node.vo.NewPooledTransactionHashes;
import br.com.node.vo.Status;
import br.com.node.vo.Transactions;

public final class Lotacao implements Closeable {

	private static final Logger LOG = Logger.getLogger(Lotacao.class.getName());

	private static final int ALVO = Resources.inteiro("rlpx.sessoes.ativas");

	private static final int TENTATIVAS = Resources.inteiro("rlpx.tentativas.simultaneas");

	private static final Duration RODADA = Duration.ofSeconds(Resources.inteiro("rlpx.rodada.segundos"));

	// Penalidade CURTA e NÃO persistida. O projeto anterior gravava backoff
	// exponencial de 30 s a 30 min na tabela de peers para qualquer queda, e uma
	// rodada ruim barrava um par bom por meia hora — com o pool vazio, o disjuntor
	// era o que impedia a recuperação.
	private static final Duration PENALIDADE = Duration.ofSeconds(Resources.inteiro("rlpx.penalidade.segundos"));

	// Com ~20% de aceitação medida (10 de 12 recusas por "peers demais"), tentar
	// exatamente o que falta nunca preenche. O excesso é a taxa de recusa, não
	// otimismo.
	private static final int EXCESSO = 4;

	private final Sessoes sessoes;
	private final Predicate<ForkId> criterio;

	private final Set<String> tentando = ConcurrentHashMap.newKeySet();
	private final Map<String, Instant> penalizados = new ConcurrentHashMap<>();

	// O roteiro: qual código a rede manda que ainda não sabemos ler, e quantas vezes.
	private final Map<Integer, Long> naoTratadas = new ConcurrentHashMap<>();

	// Quem aproveita o bloco inteiro: com a header na mão, a frente pede só o recibo, e ao par que o
	// mandou (ver `Frente.adiantar`). Escrito uma vez na montagem, lido pelas threads de atendimento.
	private volatile BiConsumer<Sessao, NewBlock> aoReceberBloco = (sessao, bloco) -> {
	};

	// O mesmo anúncio, com QUEM anunciou: quem anuncia tem o bloco, e é a ele que se pede o recibo.
	private volatile BiConsumer<Sessao, AnuncioBloco> aoAnunciarDe = (sessao, anuncio) -> {
	};

	public void aoAnunciarDe(BiConsumer<Sessao, AnuncioBloco> aoAnunciarDe) {
		this.aoAnunciarDe = aoAnunciarDe;
	}

	public void aoReceberBloco(BiConsumer<Sessao, NewBlock> aoReceberBloco) {
		this.aoReceberBloco = aoReceberBloco;
	}

	// POR QUE cada conversa caiu, separado por fase. Existe para responder uma pergunta antes
	// de mudar o `Status`: o pool para em 8 a 11 de 25 porque parecemos leech, ou por outra
	// coisa? "Peers demais" na abertura, "peer inútil" na conversa e mudez pedem três
	// consertos diferentes — e mentir no protocolo para consertar o motivo errado seria o pior
	// dos três.
	private final Map<String, Long> quedas = new ConcurrentHashMap<>();

	// O orçamento de threads é explícito: uma de rodada, TENTATIVAS de abertura (que
	// bloqueiam no connect), e uma de leitura por conversa ativa. Nada de pool sem
	// teto, e nada de thread por par conhecido — só por par ATIVO.
	private final ScheduledExecutorService laco = Executors
			.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "lotacao"));

	private final ExecutorService aberturas = Executors.newFixedThreadPool(TENTATIVAS,
			tarefa -> new Thread(tarefa, "lotacao-abre"));

	private final ExecutorService leitores = Executors.newFixedThreadPool(ALVO,
			tarefa -> new Thread(tarefa, "lotacao-le"));

	public Lotacao(Sessoes sessoes, Predicate<ForkId> criterio) {
		this.sessoes = sessoes;
		this.criterio = criterio;
	}

	// O ouvinte do anúncio entra aqui e desce por parâmetro até o leitor: quem lê a conversa é
	// quem vê o `NewBlockHashes` chegar, e ele não pode segurar o anúncio até alguém perguntar.
	// A cabeça chega empurrada, de graça, no instante em que o bloco existe.
	public void iniciar(Consumer<AnuncioBloco> aoAnunciar) {
		iniciar(aoAnunciar, mensagem -> {
		});
	}

	public void iniciar(Consumer<AnuncioBloco> aoAnunciar,
			Consumer<NewPooledTransactionHashes> aoAnunciarTransacoes) {
		iniciar(aoAnunciar, (sessao, mensagem) -> aoAnunciarTransacoes.accept(mensagem), (sessao, mensagem) -> {
		});
	}

	public void iniciar(Consumer<AnuncioBloco> aoAnunciar,
			BiConsumer<Sessao, NewPooledTransactionHashes> aoAnunciarTransacoes,
			BiConsumer<Sessao, Transactions> aoReceberTransacoes) {
		laco.scheduleWithFixedDelay(() -> rodada(aoAnunciar, aoAnunciarTransacoes, aoReceberTransacoes), 0, RODADA.toMillis(),
				TimeUnit.MILLISECONDS);
	}

	public long ativas() {
		return sessoes.todas().stream().filter(Sessao::ativa).count();
	}

	public Map<String, Long> quedas() {
		return Map.copyOf(quedas);
	}

	public Map<Integer, Long> naoTratadas() {
		return Map.copyOf(naoTratadas);
	}

	@Override
	public void close() {

		laco.shutdownNow();
		aberturas.shutdownNow();
		leitores.shutdownNow();

		sessoes.todas().forEach(Sessao::desativar);
	}

	// A reposição é do MESMO laço que detecta a morte. Não existe caminho em que a
	// lotação caia e ninguém tenha a tarefa de recompô-la — foi o que congelou o
	// pool anterior em 25 zumbis.
	private void rodada(Consumer<AnuncioBloco> aoAnunciar,
			BiConsumer<Sessao, NewPooledTransactionHashes> aoAnunciarTransacoes,
			BiConsumer<Sessao, Transactions> aoReceberTransacoes) {

		try {

			trocarOMaisLento(Instant.now());

			int faltam = ALVO - (int) ativas() - tentando.size();

			if (faltam > 0) {
				candidatos().stream().limit((long) faltam * EXCESSO)
						.forEach(sessao -> abrir(sessao, aoAnunciar, aoAnunciarTransacoes, aoReceberTransacoes));
			}

			LOG.info(() -> "lotação: " + ativas() + "/" + ALVO + " ativas, " + tentando.size() + " abrindo, "
					+ penalizados.size() + " penalizados, quedas: " + quedas + ", não tratados: " + naoTratadas);

		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("rodada de lotação", e);
		}
	}

	private List<Sessao> candidatos() {

		Instant agora = Instant.now();

		// PERTO PRIMEIRO. Antes era a ordem em que o node conheceu o nó — um peer do outro lado do mundo
		// entrava tão cedo quanto um vizinho. A ordem agora é a latência já medida desse peer (conversas
		// anteriores); sem ela, o ping UDP da descoberta; sem nenhum dos dois, por último.
		return sessoes.todas().stream().filter(sessao -> !sessao.ativa())
				.filter(sessao -> !tentando.contains(sessao.no().idHex()))
				.filter(sessao -> livreDePenalidade(sessao, agora)).filter(Sessao::pronta)
				.filter(this::naoDescartadoPeloEnr).filter(sessao -> sessao.no().portaTcp() > 0)
				.sorted(java.util.Comparator.comparingDouble(Sessao::distancia)).toList();
	}

	// RODÍZIO DOS LENTOS. Com as vagas cheias, um peer lento ocupava a vaga para sempre (só saía caindo).
	// A cada TROCA_A_CADA, se o ativo mais lento está acima de LENTO_X da mediana dos ativos E acima de
	// LENTO_MINIMO_MS, ele sai para um candidato mais perto entrar. Um por vez: trocar muitos de uma vez
	// derruba a cobertura enquanto os novos abrem.
	private static final int AMOSTRAS_PARA_JULGAR = Sessao.AMOSTRAS_PARA_JULGAR;
	private static final double LENTO_X = 2.0;
	private static final double LENTO_MINIMO_MS = 400;
	private static final Duration TROCA_A_CADA = Duration.ofSeconds(30);
	private static final Duration PENALIDADE_DE_LENTO = Duration.ofMinutes(10);
	private volatile Instant ultimaTroca = Instant.EPOCH;
	private final java.util.concurrent.atomic.AtomicLong trocadosPorLentidao = new java.util.concurrent.atomic.AtomicLong();

	private void trocarOMaisLento(Instant agora) {

		if (ativas() < ALVO || ultimaTroca.plus(TROCA_A_CADA).isAfter(agora)) {
			return;
		}

		List<Sessao> julgaveis = sessoes.todas().stream().filter(Sessao::ativa)
				.filter(s -> s.amostrasDeLatencia() >= AMOSTRAS_PARA_JULGAR)
				.sorted(java.util.Comparator.comparingDouble(Sessao::latenciaMs)).toList();

		if (julgaveis.size() < ALVO / 2) {
			return;
		}

		double mediana = julgaveis.get(julgaveis.size() / 2).latenciaMs();
		Sessao maisLento = julgaveis.get(julgaveis.size() - 1);

		if (maisLento.latenciaMs() < Math.max(LENTO_MINIMO_MS, mediana * LENTO_X)) {
			return;
		}

		ultimaTroca = agora;
		trocadosPorLentidao.incrementAndGet();
		penalizados.put(maisLento.no().idHex(), agora.plus(PENALIDADE_DE_LENTO));
		LOG.info(() -> String.format("lotação: troco o peer mais lento (%.0f ms, mediana %.0f ms): %s",
				maisLento.latenciaMs(), mediana, maisLento.no()));
		maisLento.desativar();
	}

	// A distância dos peers, para o relatório: latência dos ativos (o que usamos) e ping dos candidatos.
	public Map<String, Object> resumoDePeers() {

		List<Double> ativos = sessoes.todas().stream().filter(Sessao::ativa)
				.filter(s -> s.amostrasDeLatencia() >= AMOSTRAS_PARA_JULGAR).map(Sessao::latenciaMs).sorted().toList();
		List<Long> udp = sessoes.todas().stream().map(Sessao::rttUdpMs).filter(r -> r >= 0).sorted().toList();

		Map<String, Object> resumo = new java.util.LinkedHashMap<>();
		resumo.put("ativos_com_latencia", ativos.size());
		resumo.put("latencia_ativos_mediana_ms", ativos.isEmpty() ? null : Math.round(ativos.get(ativos.size() / 2)));
		resumo.put("latencia_ativos_max_ms", ativos.isEmpty() ? null : Math.round(ativos.get(ativos.size() - 1)));
		resumo.put("ping_udp_conhecidos", udp.size());
		resumo.put("ping_udp_mediana_ms", udp.isEmpty() ? null : udp.get(udp.size() / 2));
		resumo.put("ping_udp_p10_ms", udp.isEmpty() ? null : udp.get(udp.size() / 10));
		resumo.put("trocados_por_lentidao", trocadosPorLentidao.get());
		return resumo;
	}

	// O ENR é DICA, não critério. Exigir rede declarada no ENR para discar deixava o pool
	// em 3 de 25: medido, 40 dos 444 pares provados declaram rede. Quem não declarou não é
	// de outra cadeia — é só quem não publicou registro, e nós também não publicamos.
	//
	// Descarta-se só quem declarou OUTRA rede. O resto se confirma onde a resposta é
	// autoritativa: o `Status` do aperto de mão.
	private boolean naoDescartadoPeloEnr(Sessao sessao) {
		return sessao.rede().map(criterio::test).orElse(true);
	}

	// O `Status` é a resposta autoritativa para "de que cadeia você é": ele vem do par
	// sobre a cabeça DELE, não de um registro que pode estar velho. Par de outra cadeia
	// sai aqui, antes de ocupar um leitor.
	private boolean daNossaCadeia(Sessao sessao) {
		return sessao.statusDoPar().map(Status::fork).filter(criterio).isPresent();
	}

	private boolean livreDePenalidade(Sessao sessao, Instant agora) {

		Instant ate = penalizados.get(sessao.no().idHex());

		return ate == null || ate.isBefore(agora);
	}

	private void abrir(Sessao sessao, Consumer<AnuncioBloco> aoAnunciar,
			BiConsumer<Sessao, NewPooledTransactionHashes> aoAnunciarTransacoes,
			BiConsumer<Sessao, Transactions> aoReceberTransacoes) {

		String id = sessao.no().idHex();

		if (!tentando.add(id)) {
			return;
		}

		aberturas.submit(() -> {

			try {

				sessao.ativar();

				if (!daNossaCadeia(sessao)) {

					sessao.desativar();

					penalizados.put(id, Instant.now().plus(PENALIDADE));

					anotarQueda("abertura", "outra cadeia no Status");

					return;
				}

				leitores.submit(() -> atender(sessao, aoAnunciar, aoAnunciarTransacoes, aoReceberTransacoes));

			} catch (RuntimeException e) {

				penalizados.put(id, Instant.now().plus(PENALIDADE));

				anotarQueda("abertura", motivoDe(e));

			} finally {
				tentando.remove(id);
			}
		});
	}

	// O leitor é o que mantém a conversa viva: ele responde o Ping do par e é o que
	// percebe a morte, porque `atender` estoura no timeout do socket.
	private void atender(Sessao sessao, Consumer<AnuncioBloco> aoAnunciar,
			BiConsumer<Sessao, NewPooledTransactionHashes> aoAnunciarTransacoes,
			BiConsumer<Sessao, Transactions> aoReceberTransacoes) {

		// Sair do laço sem exceção é a tolerância de mudez vencendo: o par ficou calado.
		String motivo = "mudez";

		try {

			while (sessao.ativa()) {

				MensagemRlpx mensagem = sessao.atender();

				if (mensagem instanceof NewBlockHashes anuncio) {
					anuncio.anuncios().forEach(aoAnunciar);
					anuncio.anuncios().forEach(um -> aoAnunciarDe.accept(sessao, um));
				}

				// O `NewBlock` é o anúncio forte: vem com a header inteira. A cabeça que ele
				// anuncia é a mesma que o hash sozinho anunciaria — quem quiser o desfecho das
				// transações pega as cruas do próprio `NewBlock`.
				if (mensagem instanceof NewBlock bloco) {
					aoAnunciar.accept(new AnuncioBloco(bloco.cabecalho().hash(), bloco.cabecalho().numero()));
					aoReceberBloco.accept(sessao, bloco);
				}

				if (mensagem instanceof NewPooledTransactionHashes anuncio) {
					aoAnunciarTransacoes.accept(sessao, anuncio);
				}

				if (mensagem instanceof Transactions transacoes) {
					aoReceberTransacoes.accept(sessao, transacoes);
				}

				if (mensagem instanceof Desconhecida nova) {
					naoTratadas.merge(nova.codigo(), 1L, Long::sum);
				}
			}

		} catch (RuntimeException e) {

			String qual = motivoDe(e);

			LOG.info(() -> "conversa com " + sessao.no() + " terminou: " + qual);

			motivo = qual;
		}

		anotarQueda("conversa", motivo);

		sessao.desativar();

		penalizados.put(sessao.no().idHex(), Instant.now().plus(PENALIDADE));
	}

	private void anotarQueda(String fase, String motivo) {
		quedas.merge(fase + " · " + motivo, 1L, Long::sum);
	}

	// O motivo é a FORMA da mensagem, sem o par: com o identificador dentro, cada queda seria
	// uma chave nova e o mapa diria "houve quedas" em vez de "por quê".
	private static String motivoDe(RuntimeException e) {

		String texto = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();

		// O PASSO em que o par derrubou decide o que a queda prova: antes do `Hello` ele ainda nem
		// viu o nosso `Status`, então "peer inútil" ali não pode ser sobre parecermos leech. A
		// primeira medição jogava o passo fora e deixava justamente essa pergunta sem resposta.
		if (texto.contains("derrubou")) {
			return texto.substring(texto.indexOf("derrubou"));
		}

		if (texto.contains("não chegou")) {
			return "mudo no aperto";
		}

		if (texto.contains("Falha ao conectar")) {
			return "inalcançável";
		}

		if (texto.contains("Falha lendo") || texto.contains("fechou")) {
			return "conexão caiu";
		}

		String semPar = texto.replaceAll("NoRemoto\\[[^\\]]*\\]", "par");

		return semPar.length() > 80 ? semPar.substring(0, 80) : semPar;
	}
}
