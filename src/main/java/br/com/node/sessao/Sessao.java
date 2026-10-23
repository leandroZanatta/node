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

	private static final Duration VALIDADE_DO_VINCULO = Duration.ofHours(Resources.inteiro("descoberta.vinculo.horas"));

	private static final int NOS_POR_BUSCA = Resources.inteiro("descoberta.nos.por.busca");
	private static final int NOS_POR_PACOTE = Resources.inteiro("descoberta.nos.por.pacote");

	private static final Duration VALIDADE_DA_MENSAGEM = Duration.ofSeconds(60);

	private static final Duration TOLERANCIA_DE_MUDEZ = Duration.ofSeconds(Resources.inteiro("rlpx.mudez.segundos"));

	private static final int VERSAO_ETH = 68;

	private final NoRemoto no;
	private final ConexaoUdp udp;
	private final TabelaDeRotas tabela;

	private final Pedidos pedidos = new Pedidos();
	private final NodeKey chave;

	private final Vinculo vinculo = new Vinculo(VALIDADE_DO_VINCULO);

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

	public Optional<ForkId> rede() {
		return rede == null ? Optional.empty() : rede;
	}

	public boolean redeConhecida() {
		return rede != null;
	}

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

			if (vinculo.pongRecebido(pong.pingHash(), agora)) {
				tabela.inserir(no);
			}
			yield List.of();
		}

		case FindNode busca -> {
			responderBusca(busca, agora);
			yield List.of();
		}

		case Neighbors vizinhos ->
			vinculo.valido(agora) ? vizinhos.nos().stream().limit(NOS_POR_BUSCA).toList() : List.of();

		case EnrRequest ignorado -> List.of();

		case EnrResponse resposta -> {
			rede = resposta.rede();
			yield List.of();
		}
		};
	}

	public void ativar() {

		ConexaoTcp nova = new ConexaoTcp(no, chave);

		nova.abrir();
		nova.enviar(nossoStatus());

		statusDoPar = nova.esperar(Status.class);

		nova.enviar(new UpgradeStatus(false));

		ultimaEntrada = Instant.now();
		ativadaEmMs = System.currentTimeMillis();
		tcp = nova;
	}

	public boolean ativa() {

		Instant entrada = ultimaEntrada;

		return tcp != null && entrada != null && entrada.plus(TOLERANCIA_DE_MUDEZ).isAfter(Instant.now());
	}

	public MensagemRlpx atender() {

		ConexaoTcp aberta = tcp;

		if (aberta == null) {
			throw new PeerException("Sessão com " + no + " não está ativa");
		}

		MensagemRlpx mensagem = aberta.ler();

		ultimaEntrada = Instant.now();

		if (mensagem instanceof Correlacionada resposta) {
			pedidos.entregar(resposta);
		}

		if (mensagem instanceof PingP2p) {
			aberta.enviar(new PongP2p());
		}

		if (mensagem instanceof Disconnect fim) {
			throw new PeerException("Par " + no + " derrubou: " + fim.descricao());
		}

		return mensagem;
	}

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

	private Status nossoStatus() {

		return new Status(VERSAO_ETH, ParametrosBsc.REDE, BigInteger.ZERO, ParametrosBsc.GENESIS, ParametrosBsc.GENESIS,
				Forks.doBsc(Long.MAX_VALUE, Instant.now().getEpochSecond()));
	}

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

	private static final long MEIA_VIDA_PRIMEIROS_MS = 10 * 60_000L;
	private final PlacarDePrimeiros primeiros = new PlacarDePrimeiros(MEIA_VIDA_PRIMEIROS_MS);
	private volatile long ativadaEmMs;

	public void anotarPrimeiroEntregue() {
		primeiros.anotar(System.currentTimeMillis());
	}

	public double primeirosEntregues() {
		return primeiros.valor(System.currentTimeMillis());
	}

	public long ativaHaMs() {
		return ativa() && ativadaEmMs > 0 ? System.currentTimeMillis() - ativadaEmMs : 0;
	}

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