package br.com.node.execucao.disparo;

import java.io.Closeable;
import java.util.Map;

import br.com.node.execucao.bundle.RotaDoModelo;
import br.com.node.transformacao.catalogo.Achado;

public interface AnalisadorDeTransacao extends Closeable {

	void examinar(String operador, String contrato, Achado achado, RotaDoModelo.Chamada chamada, String desfecho,
			String veredito);

	void examinar(String operador, String contrato, Achado achado, RotaDoModelo.Chamada chamada, String desfecho,
			String veredito, String hashEnviado);

	void examinar(String operador, String contrato, Achado achado, RotaDoModelo.Chamada chamada, String desfecho,
			String veredito, String hashEnviado, Map<String, Object> extras);

	void concluir(String hash, Map<String, Object> resultado);

	Map<String, Object> resumo();

	@Override
	void close();
}
