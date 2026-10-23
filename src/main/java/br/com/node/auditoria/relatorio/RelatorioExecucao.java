package br.com.node.auditoria.relatorio;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.google.gson.Gson;

public final class RelatorioExecucao implements Closeable {

	private static final Gson JSON = new Gson();

	private static final Logger LOG = Logger.getLogger(RelatorioExecucao.class.getName());

	private final long iniciouEmNanos = System.nanoTime();
	private final Instant iniciouEm = Instant.now();
	private final Path arquivo;
	private final BufferedWriter saida;
	private final Supplier<Map<String, Object>> rede;
	private final Supplier<Map<String, Object>> mercado;
	private final Supplier<Map<String, Object>> ensaio;

	public RelatorioExecucao(Path arquivo, Supplier<Map<String, Object>> rede, Supplier<Map<String, Object>> mercado,
			Supplier<Map<String, Object>> ensaio) {
		try {
			this.arquivo = arquivo;
			Files.createDirectories(arquivo.toAbsolutePath().getParent());
			this.saida = Files.newBufferedWriter(arquivo);
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível abrir relatório " + arquivo, e);
		}
		this.rede = rede;
		this.mercado = mercado;
		this.ensaio = ensaio;
	}

	public synchronized void registrar(String tipo) {
		Map<String, Object> dados = dados(tipo);
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
		return dados("snapshot");
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

	private Map<String, Object> dados(String tipo) {
		Map<String, Object> dados = new LinkedHashMap<>();
		dados.put("tipo", tipo);
		dados.put("instante", Instant.now().toString());
		dados.put("inicio", iniciouEm.toString());
		dados.put("duracao_segundos", (System.nanoTime() - iniciouEmNanos) / 1_000_000_000.0);
		dados.putAll(rede.get());
		dados.putAll(mercado.get());
		dados.put("ensaio", ensaio.get());
		return dados;
	}
}
