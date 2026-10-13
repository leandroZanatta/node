package br.com.node.sessao;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import br.com.node.cadeia.Forks;
import br.com.node.cadeia.ParametrosBsc;
import br.com.node.exception.PeerException;
import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.Correlacionada;
import br.com.node.vo.Disconnect;
import br.com.node.vo.Endpoint;
import br.com.node.vo.EnrRequest;
import br.com.node.vo.EnrResponse;
import br.com.node.vo.FindNode;
import br.com.node.vo.ForkId;
import br.com.node.vo.MensagemDiscovery;
import br.com.node.vo.MensagemEth;
import br.com.node.vo.MensagemRlpx;
import br.com.node.vo.Neighbors;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.PacoteDiscovery;
import br.com.node.vo.Ping;
import br.com.node.vo.PingP2p;
import br.com.node.vo.Pong;
import br.com.node.vo.PongP2p;
import br.com.node.vo.Status;
import br.com.node.vo.UpgradeStatus;

public final class Sessao {

	private static final Duration VALIDADE_DO_VINCULO = Duration
			.ofHours(Resources.inteiro("descoberta.vinculo.horas"));

	private static final int NOS_POR_BUSCA = Resources.inteiro("descoberta.nos.por.busca");
	private static final int NOS_POR_PACOTE = Resources.inteiro("descoberta.nos.por.pacote");

	private static final Duration VALIDADE_DA_MENSAGEM = Duration.ofSeconds(60);

	private static final Duration TOLERANCIA_DE_MUDEZ = Duration.ofSeconds(Resources.inteiro("rlpx.mudez.segundos"));

	private static final int VERSAO_ETH = 68;

	private final NoRemoto no;
	private final ConexaoUdp udp;
	private final TabelaDeRotas tabela;

	// Um registro de pedidos por conversa: o id de requisição só é único dentro dela.
	private final Pedidos pedidos = new Pedidos();
	private final NodeKey chave;

	private final Vinculo vinculo = new Vinculo(VALIDADE_DO_VINCULO);

	// Escritos pela thread de recepção ou pela de atendimento, lidos pela de rodada.
	// São escritas de referência únicas, sem invariante entre elas.
	private volatile Optional<ForkId> rede;
	private volatile ConexaoTcp tcp;
	private volatile Instant ultimaEntrada;
	private volatile Status statusDoPar;

	public Sessao(NoRemoto no, ConexaoUdp udp, TabelaDeRotas tabela, NodeKey chave) {
		this.no = no;
		this.udp = udp;
		this.tabela = tabela;
		this.chave = chave;
	}

	public NoRemoto no() {
		return no;
	}

	public boolean pronta() {
		return vinculo.valido(Instant.now());
	}

	// Três estados, e são diferentes: `null` é "ainda não perguntamos";
	// `Optional.empty()` é "respondeu e não publica fork id"; um ForkId é a resposta.
	// Colapsar os dois primeiros faria a rodada perguntar para sempre a quem já
	// disse que não sabe.
	public Optional<ForkId> rede() {
		return rede == null ? Optional.empty() : rede;
	}

	public boolean redeConhecida() {
		return rede != null;
	}

	// O handshake é do node: nos apresentamos e ficamos provados. Não bloqueia — a
	// prova chega num datagrama, mais tarde, na thread de recepção.
	public void handshake() {
		vinculo.pingEnviado(enviar(new Ping(MensagemDiscovery.VERSAO, udp.origem(), endpointDoPar(), expiracao())));
	}

	public boolean perguntarRede() {

		if (!pronta()) {
			return false;
		}

		enviar(new EnrRequest(expiracao()));

		return true;
	}

	public boolean buscar(byte[] alvo) {

		if (!pronta()) {
			return false;
		}

		enviar(new FindNode(alvo, expiracao()));

		return true;
	}

	public List<NoRemoto> receber(PacoteDiscovery pacote) {

		Instant agora = Instant.now();

		if (pacote.mensagem().expirada(agora)) {
			return List.of();
		}

		return switch (pacote.mensagem()) {

		case Ping ignorado -> {
			responderPing(pacote.hash(), agora);
			yield List.of();
		}

		case Pong pong -> {
			// A tabela é de quem provou endereço, não de quem foi mencionado. Quem sabe
			// que a prova fechou é esta sessão, então é ela que se insere.
			if (vinculo.pongRecebido(pong.pingHash(), agora)) {
				tabela.inserir(no);
			}
			yield List.of();
		}

		case FindNode busca -> {
			responderBusca(busca, agora);
			yield List.of();
		}

		case Neighbors vizinhos -> vinculo.valido(agora) ? vizinhos.nos().stream().limit(NOS_POR_BUSCA).toList()
				: List.of();

		case EnrRequest ignorado -> List.of();

		case EnrResponse resposta -> {
			rede = resposta.rede();
			yield List.of();
		}
		};
	}

	// Abre a conversa eth com este par. BLOQUEIA: são um connect, um aperto ECIES e
	// duas trocas de mensagem. Quem chama é quem escolheu ativar esta sessão, na
	// thread dela — nunca o laço da descoberta.
	public void ativar() {

		ConexaoTcp nova = new ConexaoTcp(no, chave);

		nova.abrir();
		nova.enviar(nossoStatus());

		statusDoPar = nova.esperar(Status.class);

		// No eth/68 a BSC troca isto depois do Status, e quem não responde leva
		// Disconnect por erro de subprotocolo. No eth/70 ela NÃO é trocada.
		nova.enviar(new UpgradeStatus(false));

		ultimaEntrada = Instant.now();
		tcp = nova;
	}

	// Viva é quem deu QUALQUER entrada de protocolo dentro da tolerância — não só
	// bloco. Medir mudez por bloco derruba sessão saudável durante catch-up pesado,
	// e foi um dos defeitos que encheu o pool anterior de zumbis.
	public boolean ativa() {

		Instant entrada = ultimaEntrada;

		return tcp != null && entrada != null && entrada.plus(TOLERANCIA_DE_MUDEZ).isAfter(Instant.now());
	}

	// Lê uma mensagem da conversa e responde o que o protocolo exige. Bloqueia até o
	// timeout do socket; estourar o timeout é a morte sendo detectada.
	public MensagemRlpx atender() {

		ConexaoTcp aberta = tcp;

		if (aberta == null) {
			throw new PeerException("Sessão com " + no + " não está ativa");
		}

		MensagemRlpx mensagem = aberta.ler();

		ultimaEntrada = Instant.now();

		// O leitor é o único que lê, então é ele que correlaciona. Quem pediu está
		// bloqueado no próprio id, não no socket.
		if (mensagem instanceof Correlacionada resposta) {
			pedidos.entregar(resposta);
		}

		// O par manda Ping para saber se estamos vivos, e não responder é levar
		// Disconnect 0x0b. O node anterior nunca respondeu, e atribuía as quedas a
		// "peer inútil" porque a tabela de motivos estava errada.
		if (mensagem instanceof PingP2p) {
			aberta.enviar(new PongP2p());
		}

		if (mensagem instanceof Disconnect fim) {
			throw new PeerException("Par " + no + " derrubou: " + fim.descricao());
		}

		return mensagem;
	}

	// O que o par declarou de si ao abrir a conversa: a cabeça dele, a dificuldade
	// total e o fork. É por aqui que se descobre a altura do topo — o Status traz o
	// hash, e a altura vem do cabeçalho daquele hash.
	public Optional<Status> statusDoPar() {
		return Optional.ofNullable(statusDoPar);
	}

	public void desativar() {

		ConexaoTcp aberta = tcp;

		tcp = null;

		pedidos.abortar(new PeerException("Conversa com " + no + " encerrada"));

		if (aberta != null) {
			aberta.close();
		}
	}

	// Sem cursor ainda: anunciamos o genesis como cabeça e dificuldade zero. É o que
	// faz um par nos tratar como leech, e muda quando o cursor existir.
	private Status nossoStatus() {

		return new Status(VERSAO_ETH, ParametrosBsc.REDE, BigInteger.ZERO, ParametrosBsc.GENESIS,
				ParametrosBsc.GENESIS, Forks.doBsc(Long.MAX_VALUE, Instant.now().getEpochSecond()));
	}

	// Pede e espera a resposta DAQUELE pedido, na thread de quem chama.
	//
	// EXIGE UM LEITOR VIVO na conversa — é ele que correlaciona. Quem chama `pedir` não
	// lê o socket: se lesse, duas threads lendo o mesmo socket embaralhariam quadros. O
	// leitor é da `Lotacao`, e é por isso que a frente só usa sessão ATIVA em vez de
	// abrir a sua.
	//
	// O PRAZO É DE QUEM PEDE, porque só quem pede sabe o tamanho do que pediu. Um prazo único de
	// 15 s servia a faixa de 16 blocos do catch-up e o bloco único da cabeça: medido na cabeça com
	// leque 1, 13 prazos estourados travaram o cursor por 15 s cada, e a mediana foi 18.978 ms.
	// LATÊNCIA REAL deste peer: média móvel do tempo de resposta de TODO pedido nosso (cabeçalho, recibo,
	// corpo). Prazo estourado entra como o próprio prazo — lento e mudo pesam igual. Sobrevive a
	// reconexões (a Sessao do nó é a mesma), então a lotação lembra quem é rápido.
	private static final double PESO_DA_AMOSTRA = 0.2;
	private volatile double latenciaMs = -1;
	private volatile long amostrasDeLatencia;

	public double latenciaMs() {
		return latenciaMs;
	}

	public long amostrasDeLatencia() {
		return amostrasDeLatencia;
	}

	public long rttUdpMs() {
		return vinculo.rttMs();
	}

	public static final int AMOSTRAS_PARA_JULGAR = 20;

	// A melhor estimativa de distância a este peer, em ms: a latência dos nossos pedidos (mede o que
	// usamos — respostas eth) quando já há amostras; senão o ping UDP da descoberta; senão, a pior.
	public double distancia() {

		if (amostrasDeLatencia >= AMOSTRAS_PARA_JULGAR) {
			return latenciaMs;
		}

		long udp = vinculo.rttMs();

		return udp >= 0 ? udp : Double.MAX_VALUE;
	}

	private synchronized void anotarLatencia(double ms) {
		latenciaMs = latenciaMs < 0 ? ms : latenciaMs + PESO_DA_AMOSTRA * (ms - latenciaMs);
		amostrasDeLatencia++;
	}

	public <T extends Correlacionada> T pedir(Correlacionada pedido, Class<T> resposta, long prazoMs) {

		ConexaoTcp aberta = tcp;

		if (aberta == null) {
			throw new PeerException("Sessão com " + no + " não está ativa");
		}

		pedidos.registrar(pedido.requisicaoId());

		// Escrita que falha é conexão MORTA, não pedido azarado: derruba já. CICATRIZ de 24/09: a sessão
		// seguia "ativa" até o prazo de mudez vencer, e nesse meio-tempo a frente a escolheu 1.293.561
		// vezes em 3 minutos ("Pipe quebrado") — um laço quente num socket fechado.
		try {
			aberta.enviar(pedido);
		} catch (PeerException escrita) {
			desativar();
			throw escrita;
		}

		long inicio = System.nanoTime();

		try {
			T veio = pedidos.esperar(pedido.requisicaoId(), resposta, prazoMs);
			anotarLatencia((System.nanoTime() - inicio) / 1_000_000.0);
			return veio;
		} catch (PeerException falhou) {
			// Prazo estourado só é DISTÂNCIA em pedido de bloco (cabeçalho, recibo), que o peer tem de ter.
			// Corpo de tx que não vem é o peer que já não tem a tx — contá-lo aqui (medido em 24/09 12:12)
			// levou a "latência" dos ativos a ~1.200 ms com as respostas reais em ~500 ms.
			boolean deBloco = resposta != br.com.node.vo.PooledTransactions.class;
			if (deBloco && falhou.getMessage() != null && falhou.getMessage().contains("não chegou")) {
				anotarLatencia(prazoMs);
			}
			throw falhou;
		}
	}

	private void responderPing(byte[] hashDoPing, Instant agora) {

		enviar(new Pong(endpointDoPar(), hashDoPing, expiracao()));

		if (!vinculo.valido(agora)) {
			handshake();
		}
	}

	private void responderBusca(FindNode busca, Instant agora) {

		if (!vinculo.valido(agora)) {
			return;
		}

		List<NoRemoto> vizinhos = tabela.maisProximos(busca.alvo(), NOS_POR_BUSCA);

		for (int i = 0; i < vizinhos.size(); i += NOS_POR_PACOTE) {

			List<NoRemoto> fatia = vizinhos.subList(i, Math.min(i + NOS_POR_PACOTE, vizinhos.size()));

			enviar(new Neighbors(fatia, expiracao()));
		}
	}

	// Quem escolhe o transporte é o TIPO do que se quer dizer, resolvido pelo
	// compilador. Mensagem de descoberta sai pela conexão UDP porque é disso que ela
	// é feita — não porque alguém chamou um método com nome de transporte.
	private byte[] enviar(MensagemDiscovery mensagem) {
		return udp.enviar(mensagem);
	}

	private Endpoint endpointDoPar() {
		return new Endpoint(no.ip(), no.portaUdp(), no.portaTcp());
	}

	private long expiracao() {
		return Instant.now().plus(VALIDADE_DA_MENSAGEM).getEpochSecond();
	}
}
