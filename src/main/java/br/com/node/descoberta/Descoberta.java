package br.com.node.descoberta;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import br.com.node.exception.PeerException;
import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.rede.Discv4;
import br.com.node.sessao.Sessao;
import br.com.node.sessao.Sessoes;
import br.com.node.util.Resources;
import br.com.node.util.Secp256k1;
import br.com.node.vo.ForkId;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.PacoteDiscovery;

public final class Descoberta implements Closeable {

	private static final Logger LOG = Logger.getLogger(Descoberta.class.getName());

	private static final Duration INTERVALO = Duration.ofSeconds(Resources.inteiro("descoberta.intervalo.segundos"));
	private static final int BUSCAS_POR_RODADA = Resources.inteiro("descoberta.buscas.por.rodada");
	private static final int MAXIMO_SESSOES = Resources.inteiro("descoberta.maximo.sessoes");

	// Folga para o Pong das sementes chegar antes da primeira pergunta.
	private static final Duration ESPERA_DO_VINCULO = Duration.ofSeconds(2);

	private final Discv4 discv4;
	private final Sessoes sessoes;
	private final TabelaDeRotas tabela;
	private final List<NoRemoto> sementes;
	private final Consumer<NoRemoto> aoDescobrir;

	private final SecureRandom sorteio = new SecureRandom();

	private final ScheduledExecutorService rodadas = Executors
			.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "descoberta"));

	// Cinco parâmetros, e a tabela é o quinto de propósito: tabela vazia significa
	// que não respondemos FindNode, e não responder FindNode é sair das tabelas
	// alheias e ficar invisível na DHT. É o sintoma mais caro deste módulo, e tem de
	// aparecer no log da rodada — esconder isso para economizar um parâmetro foi o
	// tipo de troca que tornou o node anterior indepurável.
	public Descoberta(Discv4 discv4, Sessoes sessoes, TabelaDeRotas tabela, List<NoRemoto> sementes,
			Consumer<NoRemoto> aoDescobrir) {
		this.discv4 = discv4;
		this.sessoes = sessoes;
		this.tabela = tabela;
		this.sementes = List.copyOf(sementes);
		this.aoDescobrir = aoDescobrir;
	}

	public void iniciar() {

		for (NoRemoto semente : sementes) {
			sessoes.obter(semente).handshake();
		}

		// A primeira rodada não espera um intervalo inteiro: o Pong das sementes chega
		// em menos de um segundo, e ficar quieto depois disso é desperdiçar o único
		// momento em que já há com quem falar.
		rodadas.scheduleWithFixedDelay(this::rodada, ESPERA_DO_VINCULO.toMillis(), INTERVALO.toMillis(),
				TimeUnit.MILLISECONDS);

		LOG.info(() -> "descoberta iniciada com " + sementes.size() + " sementes");
	}

	public long prontas() {
		return sessoes.todas().stream().filter(Sessao::pronta).count();
	}

	// Os pares que declararam uma rede que satisfaz o critério de quem pergunta. A
	// Descoberta não sabe o que é BSC — quem sabe é o crivo, e ele é de quem precisa
	// escolher com quem falar.
	public List<NoRemoto> pares(Predicate<ForkId> criterio) {

		return sessoes.todas().stream().filter(sessao -> sessao.rede().filter(criterio).isPresent()).map(Sessao::no)
				.toList();
	}

	public Map<ForkId, Long> redes() {

		return sessoes.todas().stream().map(Sessao::rede).filter(Optional::isPresent).map(Optional::get)
				.collect(Collectors.groupingBy(rede -> rede, Collectors.counting()));
	}

	@Override
	public void close() {
		rodadas.shutdownNow();
	}

	// Um datagrama chegou: descobre de quem é, entrega à sessão dele e aprende o que
	// vier. Público porque quem liga isto à porta é quem compõe o node — a porta não
	// pode conhecer a descoberta, nem a descoberta abrir a porta.
	public void receber(InetSocketAddress origem, byte[] dados) {

		PacoteDiscovery pacote;

		try {
			pacote = discv4.decodificar(dados);
		} catch (PeerException e) {
			br.com.node.util.Falhas.anotar("pacote de descoberta ignorado", e);
			return;
		}

		Sessao sessao = sessoes
				.obter(new NoRemoto(pacote.remetenteId(), origem.getAddress(), origem.getPort(), origem.getPort()));

		aprender(sessao.receber(pacote));
	}

	// Se apresenta a cada vizinho que ainda não conhecíamos. Quem diz se o nó é novo
	// é o registro de sessões: ele já existe, já é indexado por identidade e já tem
	// teto. Um segundo conjunto de "sondados" seria a mesma verdade em dois lugares —
	// e no node anterior ele crescia sem limite e nunca esquecia ninguém.
	private void aprender(List<NoRemoto> vizinhos) {

		for (NoRemoto vizinho : vizinhos) {

			if (sessoes.conhece(vizinho) || sessoes.quantas() >= MAXIMO_SESSOES) {
				continue;
			}

			aoDescobrir.accept(vizinho);

			sessoes.obter(vizinho).handshake();
		}
	}

	private void rodada() {

		try {

			List<Sessao> vivas = new ArrayList<>(sessoes.todas());

			Collections.shuffle(vivas);

			int perguntas = 0;

			for (Sessao sessao : vivas) {

				if (!sessao.pronta()) {
					sessao.handshake();
					continue;
				}

				// "De que rede você é" vale a pena perguntar uma vez por par, e não disputa
				// cota com a busca: são pacotes diferentes, com respostas de validade
				// diferente.
				if (!sessao.redeConhecida()) {
					sessao.perguntarRede();
				}

				if (perguntas < BUSCAS_POR_RODADA && sessao.buscar(alvoSorteado())) {
					perguntas++;
				}
			}

			LOG.info(() -> "rodada: " + sessoes.quantas() + " sessões, " + prontas() + " provadas, " + tabela.total() + " na tabela, "
					+ redes().size() + " redes declaradas");

		} catch (RuntimeException e) {
			br.com.node.util.Falhas.anotar("rodada de descoberta", e);
		}
	}

	// O alvo de cada busca é sorteado. Perguntar sempre pela própria identidade
	// devolve sempre a mesma vizinhança; é sorteando que a varredura alcança baldes
	// distantes e a tabela cresce.
	private byte[] alvoSorteado() {

		byte[] alvo = new byte[Secp256k1.TAMANHO_ID];

		sorteio.nextBytes(alvo);

		return alvo;
	}
}
