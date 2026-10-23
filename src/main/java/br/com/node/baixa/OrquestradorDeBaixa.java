package br.com.node.baixa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

import br.com.node.baixa.p2p.Descoberta;
import br.com.node.baixa.p2p.Lotacao;
import br.com.node.baixa.p2p.Sementes;
import br.com.node.baixa.p2p.Sessao;
import br.com.node.baixa.p2p.Sessoes;
import br.com.node.baixa.persistencia.PeersRepository;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.baixa.protocolo.Discv4;
import br.com.node.baixa.protocolo.Envelope;
import br.com.node.baixa.protocolo.PortaUdp;
import br.com.node.baixa.protocolo.TabelaDeRotas;
import br.com.node.baixa.sync.Aplicador;
import br.com.node.baixa.sync.Cabeca;
import br.com.node.baixa.sync.Crivo;
import br.com.node.baixa.sync.Cursor;
import br.com.node.baixa.sync.Frente;
import br.com.node.baixa.sync.Sincronizacao;
import br.com.node.util.NodeKey;
import br.com.node.util.Resources;
import br.com.node.vo.NewBlock;
import br.com.node.vo.NewPooledTransactionHashes;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.Transactions;

public final class OrquestradorDeBaixa {

	private final boolean atalhoDaCabeca = !"0".equals(System.getenv("ATALHO_CABECA"));

	private final List<BiConsumer<Sessao, NewBlock>> ouvintesDeBloco = new CopyOnWriteArrayList<>();

	private final PortaUdp porta;
	private final Descoberta descoberta;
	private final Sessoes sessoes;
	private final TabelaDeRotas tabela;
	private final Lotacao lotacao;
	private final Cabeca cabeca;
	private final Frente frente;
	private final Cursor cursor;
	private final Sincronizacao sincronizacao;

	public OrquestradorDeBaixa(NodeKey chave, Postgres banco, Cabeca cabeca, Aplicador aplicador) {

		PeersRepository peers = new PeersRepository(banco);

		this.porta = new PortaUdp(Resources.inteiro("node.porta.udp"));
		Discv4 discv4 = new Discv4(new Envelope(chave));
		this.tabela = new TabelaDeRotas(chave.id(), Resources.inteiro("descoberta.nos.por.balde"));
		this.sessoes = new Sessoes(porta, discv4, tabela, chave);
		this.descoberta = new Descoberta(discv4, sessoes, tabela, sementes(peers), peers::salvar);
		this.lotacao = new Lotacao(sessoes, Crivo.corteDeHoje()::aceita);
		this.cabeca = cabeca;
		this.frente = new Frente(sessoes, Resources.inteiro("sincronizacao.lote"));
		this.cursor = new Cursor(frente, aplicador);
		this.sincronizacao = new Sincronizacao(cabeca, frente, cursor);
	}

	public void aoReceberBloco(BiConsumer<Sessao, NewBlock> ouvinte) {
		ouvintesDeBloco.add(ouvinte);
	}

	public void aoFecharBloco(LongConsumer ouvinte) {
		cursor.aoFecharBloco(ouvinte);
	}

	public void iniciar(BiConsumer<Sessao, NewPooledTransactionHashes> aoAnunciarHashes,
			BiConsumer<Sessao, Transactions> aoReceberTransacoes) {

		porta.abrir(descoberta::receber);

		descoberta.iniciar();

		lotacao.aoReceberBloco((sessao, bloco) -> {
			if (atalhoDaCabeca) {
				frente.adiantar(sessao, bloco.cabecalho());
			}
			ouvintesDeBloco.forEach(ouvinte -> ouvinte.accept(sessao, bloco));
		});
		if (atalhoDaCabeca) {
			lotacao.aoAnunciarDe(frente::anunciado);
		}
		lotacao.iniciar(cabeca::anunciar, aoAnunciarHashes, aoReceberTransacoes);
		sincronizacao.iniciar();
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("sessoes_conhecidas", sessoes.quantas());
		resumo.put("sessoes_prontas", descoberta.prontas());
		resumo.put("sessoes_ativas", lotacao.ativas());
		resumo.put("rota_total", tabela.total());
		resumo.put("cabeca_alvo", cabeca.alvo());
		resumo.put("cabeca_ignorados", cabeca.ignorados());
		resumo.put("cursor_altura", cursor.altura());
		resumo.put("cursor_reorganizacoes", cursor.reorganizacoes());
		resumo.put("cursor_milis_aplicando", cursor.milisAplicando());
		resumo.put("latencias_ms", estatisticas(cabeca.latenciasEmMilis()));
		resumo.put("latencia_recente_ms", cabeca.latenciaRecente());
		resumo.put("cabeca_reancoragens", cabeca.reancoragens());
		resumo.put("cursor_repedidos", cursor.repedidos());
		resumo.put("atraso_em_blocos_recente", cabeca.atrasoEmBlocosRecente());
		resumo.put("linha_do_tempo_do_bloco", cabeca.linhaDoTempo());
		resumo.put("quedas", lotacao.quedas());
		resumo.put("mensagens_nao_tratadas", lotacao.naoTratadas());
		resumo.put("frente", frente.resumo());
		resumo.put("peers", lotacao.resumoDePeers());
		return resumo;
	}

	private static Map<String, Long> estatisticas(List<Long> valores) {
		Map<String, Long> resultado = new LinkedHashMap<>();
		resultado.put("amostras", (long) valores.size());
		if (valores.isEmpty()) {
			return resultado;
		}
		List<Long> ordenados = valores.stream().sorted().toList();
		resultado.put("min", ordenados.get(0));
		resultado.put("mediana", ordenados.get(ordenados.size() / 2));
		resultado.put("p95",
				ordenados.get(Math.min(ordenados.size() - 1, (int) Math.ceil(ordenados.size() * 0.95) - 1)));
		resultado.put("max", ordenados.get(ordenados.size() - 1));
		return resultado;
	}

	public void fechar() {

		sincronizacao.close();
		lotacao.close();
		descoberta.close();
		porta.close();
	}

	private static List<NoRemoto> sementes(PeersRepository peers) {

		List<NoRemoto> todas = new ArrayList<>(Sementes.carregar());

		todas.addAll(peers.carregar());

		return todas;
	}
}
