package br.com.node.transformacao.catalogo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import br.com.node.baixa.protocolo.Recibos;
import br.com.node.baixa.sync.Aplicador;
import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.vo.Log;

public final class AplicadorDeBloco implements Aplicador {

	private final Catalogo catalogo;
	private final List<Consumer<Transicao>> ouvintesDeTransicao = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<List<Log>, Long>> ouvintesAntesDaTransacao = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<List<Log>, Long>> ouvintesDaTransacao = new CopyOnWriteArrayList<>();

	public AplicadorDeBloco(Catalogo catalogo) {
		this.catalogo = catalogo;
	}

	public void aoTransicao(Consumer<Transicao> ouvinte) {
		ouvintesDeTransicao.add(ouvinte);
	}

	public void antesDaTransacao(BiConsumer<List<Log>, Long> ouvinte) {
		ouvintesAntesDaTransacao.add(ouvinte);
	}

	public void aoAplicarTransacao(BiConsumer<List<Log>, Long> ouvinte) {
		ouvintesDaTransacao.add(ouvinte);
	}

	@Override
	public void aplicar(byte[] recibos, long emQueAltura) {

		List<List<Log>> porTransacao = Recibos.porTransacao(recibos);

		for (int transacao = 0; transacao < porTransacao.size(); transacao++) {

			List<Log> logsDaTransacao = porTransacao.get(transacao);

			ouvintesAntesDaTransacao.forEach(ouvinte -> ouvinte.accept(logsDaTransacao, emQueAltura));

			for (Log log : logsDaTransacao) {

				Optional<ReservasV2> antes = Optional.empty();

				if (log.assinadoPor(Eventos.SYNC_V2)) {
					antes = catalogo.aplicarSync(log, emQueAltura);
				} else if (Eventos.ehSwapV3(log)) {
					catalogo.aplicarSwapV3(log, emQueAltura);
				} else if ((log.assinadoPor(Eventos.MINT_V3) || log.assinadoPor(Eventos.BURN_V3))
						&& log.topicos().size() >= 4) {
					catalogo.aplicarLiquidezV3(log, emQueAltura);
				} else if (Eventos.ehSwapDeGerente(log)) {
					catalogo.aplicarSwapDeGerente(log, emQueAltura);
				} else if (Eventos.ehLiquidezDeGerente(log)) {
					catalogo.aplicarLiquidezDeGerente(log, emQueAltura);
				} else if (Eventos.ehInicializacaoDeGerente(log)) {
					catalogo.aplicarInicializacao(log, emQueAltura);
				}

				Transicao transicao = new Transicao(log, emQueAltura, transacao, antes);
				ouvintesDeTransicao.forEach(ouvinte -> ouvinte.accept(transicao));
			}

			ouvintesDaTransacao.forEach(ouvinte -> ouvinte.accept(logsDaTransacao, emQueAltura));
		}

		catalogo.inferirPares(porTransacao);
	}
}
