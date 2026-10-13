package br.com.node;

import java.io.Closeable;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import br.com.node.descoberta.Descoberta;
import br.com.node.descoberta.Sementes;
import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.rede.Discv4;
import br.com.node.rede.Envelope;
import br.com.node.rede.PortaUdp;
import br.com.node.sessao.Lotacao;
import br.com.node.sessao.Sessao;
import br.com.node.sessao.Sessoes;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.AnuncioBloco;
import br.com.node.vo.BlockHeaders;
import br.com.node.vo.ForkId;
import br.com.node.vo.GetBlockHeadersDeHash;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.Status;

// A composição do node, numa porta sorteada. Existe para os testes de módulo não
// repetirem a fiação — e para que qualquer mudança nela apareça num lugar só.
public final class NodeDeTeste implements Closeable {

	// Id próprio: o id de requisição é único por conversa, e a frente começa no 1.
	private static final int ID_DA_CABECA = 9_001;

	public final NodeKey chave = NodeKey.gerar();
	public final List<NoRemoto> sementes = Sementes.carregar();

	private final PortaUdp porta = new PortaUdp(0);
	private final Discv4 discv4 = new Discv4(new Envelope(chave));
	private final TabelaDeRotas tabela = new TabelaDeRotas(chave.id(),
			Resources.inteiro("descoberta.nos.por.balde"));

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

	// Com ouvinte do anúncio: quem segue a cabeça precisa dele, quem só percorre uma faixa
	// do passado não.
	public Lotacao lotar(java.util.function.Predicate<ForkId> criterio, Consumer<AnuncioBloco> aoAnunciar) {

		lotacao = new Lotacao(sessoes, criterio);

		lotacao.iniciar(aoAnunciar);

		return lotacao;
	}

	// Com os ouvintes da mempool também: quem valida a projeção de N+1 precisa dos pendentes,
	// não só do anúncio da cabeça.
	public Lotacao lotar(java.util.function.Predicate<ForkId> criterio, Consumer<AnuncioBloco> aoAnunciar,
			BiConsumer<Sessao, br.com.node.vo.NewPooledTransactionHashes> aoHashes,
			BiConsumer<Sessao, br.com.node.vo.Transactions> aoTransacoes) {

		lotacao = new Lotacao(sessoes, criterio);

		lotacao.iniciar(aoAnunciar, aoHashes, aoTransacoes);

		return lotacao;
	}

	// Espera o pool ter par suficiente antes de medir qualquer coisa.
	//
	// Existe porque `procurarCabeca` se satisfaz com UMA sessão ativa, e uma sessão não sustenta
	// a frente: o `AnalisadorTest` achou a cabeça em 47 s, entrou no cursor com uma sessão só,
	// e parou com zero blocos no prazo de parada. Não é falha da cadeia nem do cursor — é
	// medição começando antes de haver o que medir.
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

	// A cabeça da cadeia, perguntada a quem já está no pool. Está aqui porque três testes
	// de módulo precisam dela e a fiação é a mesma — e porque a regra que ela respeita não é
	// óbvia: só serve sessão ATIVA, já que `pedir` correlaciona dentro do leitor da lotação,
	// e sessão sem leitor esperaria o prazo inteiro por uma resposta que ninguém entrega.
	// ATENÇÃO: a cabeça daqui é a do `Status`, e o `Status` é a cabeça do par NO APERTO DE MÃO. Sessão
	// aberta há minutos informa a cabeça de minutos atrás — medido, 168 blocos atrás da rede numa
	// conferência. Serve para escolher de onde RECUAR; para saber onde a cabeça ESTÁ, use a `Cabeca`
	// alimentada por `NewBlockHashes`, como o node de produção.
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
			// Par que não serve a cabeça não é par ruim: a lotação continua com ele.
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
