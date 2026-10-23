package br.com.node;

import java.io.Closeable;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import br.com.node.baixa.p2p.Descoberta;
import br.com.node.baixa.p2p.Sementes;
import br.com.node.baixa.protocolo.TabelaDeRotas;
import br.com.node.baixa.protocolo.Discv4;
import br.com.node.baixa.protocolo.Envelope;
import br.com.node.baixa.protocolo.PortaUdp;
import br.com.node.baixa.p2p.Lotacao;
import br.com.node.baixa.p2p.Sessao;
import br.com.node.baixa.p2p.Sessoes;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.AnuncioBloco;
import br.com.node.vo.BlockHeaders;
import br.com.node.vo.ForkId;
import br.com.node.vo.GetBlockHeadersDeHash;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.Status;

public final class NodeDeTeste implements Closeable {

	
	private static final int ID_DA_CABECA = 9_001;

	public final NodeKey chave = NodeKey.gerar();
	public final List<NoRemoto> sementes = Sementes.carregar();

	private final PortaUdp porta = new PortaUdp(0);
	private final Discv4 discv4 = new Discv4(new Envelope(chave));
	private final TabelaDeRotas tabela = new TabelaDeRotas(chave.id(), Resources.inteiro("descoberta.nos.por.balde"));

	public final Sessoes sessoes = new Sessoes(porta, discv4, tabela, chave);

	public final Descoberta descoberta;

	private Lotacao lotacao;

	public NodeDeTeste(Consumer<NoRemoto> aoDescobrir) {
		this.descoberta = new Descoberta(discv4, sessoes, tabela, sementes, aoDescobrir);
	}

	public void iniciar() {

		porta.abrir(descoberta::receber);

		descoberta.iniciar();
	}

	public Lotacao lotar(java.util.function.Predicate<ForkId> criterio) {
		return lotar(criterio, anuncio -> {
		});
	}

	
	
	
	public Lotacao lotar(java.util.function.Predicate<ForkId> criterio, Consumer<AnuncioBloco> aoAnunciar) {

		lotacao = new Lotacao(sessoes, criterio);

		lotacao.iniciar(aoAnunciar);

		return lotacao;
	}

	
	
	
	public Lotacao lotar(java.util.function.Predicate<ForkId> criterio, Consumer<AnuncioBloco> aoAnunciar,
			BiConsumer<Sessao, br.com.node.vo.NewPooledTransactionHashes> aoHashes,
			BiConsumer<Sessao, br.com.node.vo.Transactions> aoTransacoes) {

		lotacao = new Lotacao(sessoes, criterio);

		lotacao.iniciar(aoAnunciar, aoHashes, aoTransacoes);

		return lotacao;
	}

	
	
	
	
	
	
	
	
	
	public long esperarPool(int minimo, int esperaSegundos) throws InterruptedException {

		long fim = System.currentTimeMillis() + esperaSegundos * 1000L;

		long ativas = ativas();

		while (ativas < minimo && System.currentTimeMillis() < fim) {

			Thread.sleep(2000);

			ativas = ativas();
		}

		return ativas;
	}

	private long ativas() {
		return sessoes.todas().stream().filter(Sessao::ativa).count();
	}

	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	public long procurarCabeca(int esperaSegundos) throws InterruptedException {

		long fim = System.currentTimeMillis() + esperaSegundos * 1000L;

		while (System.currentTimeMillis() < fim) {

			for (Sessao sessao : sessoes.todas()) {

				if (sessao.ativa()) {

					long altura = alturaDaCabeca(sessao);

					if (altura > 0) {
						return altura;
					}
				}
			}

			Thread.sleep(2000);
		}

		return 0;
	}

	private long alturaDaCabeca(Sessao sessao) {

		try {

			byte[] hash = sessao.statusDoPar().map(Status::melhorBloco).orElseThrow();

			BlockHeaders resposta = sessao.pedir(new GetBlockHeadersDeHash(ID_DA_CABECA, hash, 1, 0, false),
					BlockHeaders.class, Resources.inteiro("rlpx.prazo.base.ms"));

			return resposta.cabecalhos().isEmpty() ? 0 : resposta.cabecalhos().get(0).numero();

		} catch (RuntimeException naoServiu) {
			
			return 0;
		}
	}

	@Override
	public void close() {

		if (lotacao != null) {
			lotacao.close();
		}

		descoberta.close();
		porta.close();
	}
}