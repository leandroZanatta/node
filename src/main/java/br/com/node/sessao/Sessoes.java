package br.com.node.sessao;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.rede.Discv4;
import br.com.node.rede.PortaUdp;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.NoRemoto;

// O registro único. A descoberta guardava o próprio mapa, e se a sincronização
// guardasse outro passariam a existir dois donos do mesmo estado — que é como o
// projeto anterior chegou a um gerente de sessões de 683 linhas.
public final class Sessoes {

	private final PortaUdp porta;
	private final Discv4 discv4;
	private final TabelaDeRotas tabela;
	private final NodeKey chave;

	private final Map<String, Sessao> porId = new ConcurrentHashMap<>();

	public Sessoes(PortaUdp porta, Discv4 discv4, TabelaDeRotas tabela, NodeKey chave) {
		this.porta = porta;
		this.discv4 = discv4;
		this.tabela = tabela;
		this.chave = chave;
	}

	// A chave é a identidade, não o endereço: o mesmo nó pode nos alcançar de uma
	// porta de origem diferente, e duas sessões para o mesmo par significariam dois
	// vínculos disputando o mesmo Pong.
	public Sessao obter(NoRemoto no) {
		return porId.computeIfAbsent(no.idHex(), id -> nova(no));
	}

	public boolean conhece(NoRemoto no) {
		return porId.containsKey(no.idHex());
	}

	public Collection<Sessao> todas() {
		return porId.values();
	}

	public int quantas() {
		return porId.size();
	}

	private Sessao nova(NoRemoto no) {

		ConexaoUdp udp = new ConexaoUdp(porta, discv4, new InetSocketAddress(no.ip(), no.portaUdp()),
				Resources.inteiro("node.porta.tcp"));

		return new Sessao(no, udp, tabela, chave);
	}
}
