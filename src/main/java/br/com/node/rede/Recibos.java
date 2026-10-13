package br.com.node.rede;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.com.node.exception.PeerException;
import br.com.node.rlp.Rlp;
import br.com.node.rlp.RlpBytes;
import br.com.node.rlp.RlpItem;
import br.com.node.vo.Log;

// Extrai os logs de um bloco de recibos.
//
// Um recibo legado é lista RLP `[status, gasAcumulado, bloom, logs]`. Um recibo
// tipado (EIP-2718) viaja como CADEIA de bytes cujo conteúdo é
// `tipo || rlp([status, gasAcumulado, bloom, logs])` — tratar as duas formas como
// uma só perde todo recibo tipado em silêncio, e hoje eles são a maioria.
public final class Recibos {

	private static final int CAMPO_DOS_LOGS = 3;

	private Recibos() {
	}

	// Os logs agrupados POR TRANSAÇÃO, e o agrupamento não é cosmético: a assinatura de
	// uma arbitragem só existe no nível da transação — somando o fluxo líquido por token
	// dentro dela, nenhum fica negativo e exatamente um fica positivo. Achatar o bloco
	// num rio de logs apaga justamente o limite onde essa conta fecha.
	public static List<List<Log>> porTransacao(byte[] cruDoBloco) {

		List<List<Log>> porRecibo = new ArrayList<>();

		for (RlpItem recibo : Rlp.paraLista(Rlp.decodificar(cruDoBloco))) {
			porRecibo.add(deUmRecibo(recibo));
		}

		return porRecibo;
	}

	private static List<Log> deUmRecibo(RlpItem recibo) {

		RlpItem corpo = recibo instanceof RlpBytes tipado ? semOByteDeTipo(tipado.valor()) : recibo;

		List<RlpItem> campos = Rlp.paraLista(corpo);

		if (campos.size() <= CAMPO_DOS_LOGS) {
			throw new PeerException("Recibo com " + campos.size() + " campos");
		}

		List<Log> logs = new ArrayList<>();

		for (RlpItem bruto : Rlp.paraLista(campos.get(CAMPO_DOS_LOGS))) {

			List<RlpItem> partes = Rlp.paraLista(bruto);

			if (partes.size() < 3) {
				continue;
			}

			List<byte[]> topicos = new ArrayList<>();

			for (RlpItem topico : Rlp.paraLista(partes.get(1))) {
				topicos.add(Rlp.paraBytes(topico));
			}

			logs.add(new Log(Rlp.paraBytes(partes.get(0)), topicos, Rlp.paraBytes(partes.get(2))));
		}

		return logs;
	}

	private static RlpItem semOByteDeTipo(byte[] conteudo) {

		if (conteudo.length < 2) {
			throw new PeerException("Recibo tipado truncado: " + conteudo.length + " bytes");
		}

		return Rlp.decodificar(Arrays.copyOfRange(conteudo, 1, conteudo.length));
	}
}
