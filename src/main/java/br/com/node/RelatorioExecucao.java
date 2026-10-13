package br.com.node;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.google.gson.Gson;

import br.com.node.catalogo.Catalogo;
import br.com.node.descoberta.Descoberta;
import br.com.node.kademlia.TabelaDeRotas;
import br.com.node.sessao.Lotacao;
import br.com.node.sessao.Sessoes;
import br.com.node.sincronizacao.Cabeca;
import br.com.node.sincronizacao.Cursor;

public final class RelatorioExecucao implements Closeable {

	private static final Gson JSON = new Gson();

	private static final Logger LOG = Logger.getLogger(RelatorioExecucao.class.getName());

	private final long iniciouEmNanos = System.nanoTime();
	private final Instant iniciouEm = Instant.now();
	private final Path arquivo;
	private final BufferedWriter saida;
	private final Descoberta descoberta;
	private final Sessoes sessoes;
	private final TabelaDeRotas tabela;
	private final Lotacao lotacao;
	private final Cabeca cabeca;
	private final Cursor cursor;
	private final Catalogo catalogo;
	private final Supplier<Map<String, Long>> quedas;
	private final Supplier<Map<Integer, Long>> naoTratadas;
	private final Supplier<Map<String, Object>> arbitragem;
	private final Supplier<Map<String, Object>> mempool;
	private final Supplier<Map<String, Object>> ensaio;

	public RelatorioExecucao(Path arquivo, Descoberta descoberta, Sessoes sessoes, TabelaDeRotas tabela,
			Lotacao lotacao, Cabeca cabeca, Cursor cursor, Catalogo catalogo, Supplier<Map<String, Long>> quedas,
			Supplier<Map<Integer, Long>> naoTratadas, Supplier<Map<String, Object>> arbitragem,
			Supplier<Map<String, Object>> mempool, Supplier<Map<String, Object>> ensaio) {
		try {
			this.arquivo = arquivo;
			Files.createDirectories(arquivo.toAbsolutePath().getParent());
			this.saida = Files.newBufferedWriter(arquivo);
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível abrir relatório " + arquivo, e);
		}
		this.descoberta = descoberta;
		this.sessoes = sessoes;
		this.tabela = tabela;
		this.lotacao = lotacao;
		this.cabeca = cabeca;
		this.cursor = cursor;
		this.catalogo = catalogo;
		this.quedas = quedas;
		this.naoTratadas = naoTratadas;
		this.arbitragem = arbitragem;
		this.mempool = mempool;
		this.ensaio = ensaio;
	}

	public synchronized void registrar(String tipo) {
		Map<String, Object> dados = new LinkedHashMap<>();
		dados.put("tipo", tipo);
		dados.put("instante", Instant.now().toString());
		dados.put("inicio", iniciouEm.toString());
		dados.put("duracao_segundos", (System.nanoTime() - iniciouEmNanos) / 1_000_000_000.0);
		dados.put("sessoes_conhecidas", sessoes.quantas());
		dados.put("sessoes_prontas", descoberta.prontas());
		dados.put("sessoes_ativas", lotacao.ativas());
		dados.put("rota_total", tabela.total());
		dados.put("cabeca_alvo", cabeca.alvo());
		dados.put("cabeca_ignorados", cabeca.ignorados());
		dados.put("cursor_altura", cursor.altura());
		dados.put("cursor_reorganizacoes", cursor.reorganizacoes());
		dados.put("cursor_milis_aplicando", cursor.milisAplicando());
		dados.put("latencias_ms", estatisticas(cabeca.latenciasEmMilis()));
		dados.put("latencia_recente_ms", cabeca.latenciaRecente());
		dados.put("cabeca_reancoragens", cabeca.reancoragens());
		dados.put("cursor_repedidos", cursor.repedidos());
		dados.put("atraso_em_blocos_recente", cabeca.atrasoEmBlocosRecente());
		dados.put("linha_do_tempo_do_bloco", cabeca.linhaDoTempo());
		dados.put("quedas", quedas.get());
		dados.put("mensagens_nao_tratadas", naoTratadas.get());
		dados.put("pools_v2", catalogo.poolsV2());
		dados.put("pools_v3", catalogo.poolsV3());
		dados.put("pools_v4", catalogo.resumoDoGerente(br.com.node.catalogo.Eventos.GERENTE_V4));
		dados.put("pools_infinity_cl", catalogo.resumoDoGerente(br.com.node.catalogo.Eventos.GERENTE_INFINITY_CL));
		dados.put("pares_conhecidos", catalogo.paresConhecidos());
		dados.put("pools_descartadas", catalogo.poolsDescartadas());
		dados.put("arbitragem", arbitragem.get());
		dados.put("mempool", mempool.get());
		dados.put("ensaio", ensaio.get());
		try {
			LOG.fine(() -> dados.toString());
			saida.write(JSON.toJson(dados));
			saida.newLine();
			saida.flush();
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível gravar relatório " + arquivo, e);
		}
	}

	public synchronized Map<String, Object> snapshot() {
		Map<String, Object> dados = new LinkedHashMap<>();
		dados.put("tipo", "snapshot");
		dados.put("instante", Instant.now().toString());
		dados.put("inicio", iniciouEm.toString());
		dados.put("duracao_segundos", (System.nanoTime() - iniciouEmNanos) / 1_000_000_000.0);
		dados.put("sessoes_conhecidas", sessoes.quantas());
		dados.put("sessoes_prontas", descoberta.prontas());
		dados.put("sessoes_ativas", lotacao.ativas());
		dados.put("rota_total", tabela.total());
		dados.put("cabeca_alvo", cabeca.alvo());
		dados.put("cursor_altura", cursor.altura());
		dados.put("latencia_recente_ms", cabeca.latenciaRecente());
		dados.put("pools_v2", catalogo.poolsV2());
		dados.put("pools_v3", catalogo.poolsV3());
		dados.put("pools_v4", catalogo.resumoDoGerente(br.com.node.catalogo.Eventos.GERENTE_V4));
		dados.put("pools_infinity_cl", catalogo.resumoDoGerente(br.com.node.catalogo.Eventos.GERENTE_INFINITY_CL));
		dados.put("pares_conhecidos", catalogo.paresConhecidos());
		dados.put("arbitragem", arbitragem.get());
		dados.put("mempool", mempool.get());
		dados.put("ensaio", ensaio.get());
		return dados;
	}

	public Path arquivo() {
		return arquivo;
	}

	@Override
	public synchronized void close() {
		try {
			saida.close();
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível fechar relatório " + arquivo, e);
		}
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
}
