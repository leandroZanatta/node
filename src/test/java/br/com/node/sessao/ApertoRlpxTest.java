package br.com.node.sessao;
import br.com.node.baixa.p2p.*;
import br.com.node.baixa.protocolo.*;


import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.baixa.sync.Forks;
import br.com.node.baixa.sync.ParametrosBsc;
import br.com.node.vo.Disconnect;
import br.com.node.vo.ForkId;
import br.com.node.vo.GetReceipts;
import br.com.node.vo.Hello;
import br.com.node.vo.MensagemRlpx;
import br.com.node.vo.NoRemoto;
import br.com.node.vo.PingP2p;
import br.com.node.vo.PongP2p;
import br.com.node.vo.Receipts;
import br.com.node.vo.Status;
import br.com.node.vo.UpgradeStatus;

class ApertoRlpxTest {

	private static final int ESPERA_DESCOBERTA_SEGUNDOS = 90;
	private static final int CANDIDATOS = 12;

	@Test
	@DisplayName("fecha o handshake do node até o Status e mede a volta de um GetReceipts")
	void fechaHandshakeEMedeAVoltaDoRecibo() throws InterruptedException {

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		List<NoRemoto> candidatos = acharParesDaBsc(node);

		assertTrue(!candidatos.isEmpty(), "a descoberta não achou par da BSC para tentar");

		int fechados = 0;
		List<String> falhas = new ArrayList<>();

		for (NoRemoto par : candidatos.stream().filter(no -> no.portaTcp() > 0).limit(CANDIDATOS).toList()) {

			try (ConexaoTcp conexao = new ConexaoTcp(par, node.chave)) {

				Hello dele = conexao.abrir();

				Status status = trocarStatus(conexao);

				
				
				conexao.enviar(new UpgradeStatus(false));

				fechados++;

				System.out.printf("%n== %s%n   cliente: %s  capacidades: %s%n", par, dele.cliente(),
						dele.capacidades());
				System.out.printf("   status: eth/%d rede=%d cabeça=%s fork=%s%n", status.versao(), status.rede(),
						HexFormat.of().formatHex(status.melhorBloco()).substring(0, 16), status.fork());

				medirRecibo(conexao, status);

			} catch (RuntimeException e) {
				falhas.add(par.ip().getHostAddress() + ": " + e.getMessage());
			}
		}

		node.close();

		System.out.println();
		falhas.forEach(f -> System.out.println("   falhou " + f));

		assertTrue(fechados > 0, "nenhum handshake completou até o Status. Falhas: " + falhas);
	}

	private void medirRecibo(ConexaoTcp conexao, Status status) {

		long pedidoId = 1;

		long inicio = System.nanoTime();

		conexao.enviar(new GetReceipts(pedidoId, List.of(status.melhorBloco())));

		
		
		for (int tentativa = 0; tentativa < 12; tentativa++) {

			MensagemRlpx resposta = conexao.ler();

			if (resposta instanceof Receipts recibos && recibos.requisicaoId() == pedidoId) {

				long ms = (System.nanoTime() - inicio) / 1_000_000;

				int bytes = recibos.crusPorBloco().stream().mapToInt(b -> b.length).sum();

				System.out.printf("   GetReceipts: %d ms · %d blocos · %d bytes%n", ms, recibos.crusPorBloco().size(),
						bytes);

				return;
			}

			if (resposta instanceof PingP2p) {
				conexao.enviar(new PongP2p());
				continue;
			}

			if (resposta instanceof UpgradeStatus) {
				continue;
			}

			if (resposta instanceof Disconnect fim) {
				System.out.println("   derrubou: " + fim.descricao());
				return;
			}
		}

		System.out.println("   sem recibo em 12 mensagens");
	}

	private static ForkId nosso() {
		return Forks.doBsc(Long.MAX_VALUE, Instant.now().getEpochSecond());
	}

	private Status trocarStatus(ConexaoTcp conexao) {

		
		
		
		conexao.enviar(new Status(68, ParametrosBsc.REDE, BigInteger.ZERO, ParametrosBsc.GENESIS, ParametrosBsc.GENESIS,
				nosso()));

		for (int tentativa = 0; tentativa < 6; tentativa++) {

			MensagemRlpx resposta = conexao.ler();

			if (resposta instanceof Status status) {
				return status;
			}

			if (resposta instanceof PingP2p) {
				conexao.enviar(new PongP2p());
			}

			if (resposta instanceof Disconnect fim) {
				throw new IllegalStateException("derrubou antes do Status: " + fim.descricao());
			}
		}

		throw new IllegalStateException("Status não chegou");
	}

	
	
	private List<NoRemoto> acharParesDaBsc(NodeDeTeste node) throws InterruptedException {

		node.iniciar();

		long fim = System.currentTimeMillis() + ESPERA_DESCOBERTA_SEGUNDOS * 1000L;

		while (System.currentTimeMillis() < fim && node.descoberta.pares(nosso()::equals).size() < CANDIDATOS) {
			Thread.sleep(1000);
		}

		return node.descoberta.pares(nosso()::equals);
	}
}