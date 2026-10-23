package br.com.node.baixa.protocolo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.com.node.exception.PeerException;
import br.com.node.baixa.protocolo.Rlp;
import br.com.node.baixa.protocolo.RlpBytes;
import br.com.node.baixa.protocolo.RlpItem;
import br.com.node.vo.Log;

public final class Recibos {

	private static final int CAMPO_DOS_LOGS = 3;

	private Recibos() {
	}

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