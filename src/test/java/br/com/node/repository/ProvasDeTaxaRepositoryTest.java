package br.com.node.repository;
import br.com.node.baixa.persistencia.*;
import br.com.node.transformacao.persistencia.*;
import br.com.node.baixa.persistencia.postgres.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.exception.BancoException;
import br.com.node.baixa.persistencia.postgres.Postgres;
import br.com.node.vo.ProvaDeTaxa;

class ProvasDeTaxaRepositoryTest {

	private final SecureRandom sorteio = new SecureRandom();

	@Test
	@DisplayName("evidência de taxa soma, separa por chamador, volta igual, e o lote recusado não se perde")
	void guardaEvidenciaEmVezDeDecisao() {

		try (Postgres banco = new Postgres()) {

			banco.migrar();

			ProvasDeTaxaRepository provas = new ProvasDeTaxaRepository(banco);

			byte[] pool = endereco();
			String chamadorA = HexFormat.of().formatHex(endereco());
			String chamadorB = HexFormat.of().formatHex(endereco());

			try {

				provas.anotar(new ProvaDeTaxa(pool, 2500, chamadorA, 1));
				provas.anotar(new ProvaDeTaxa(pool, 2500, chamadorA, 1));
				provas.anotar(new ProvaDeTaxa(pool, 2500, chamadorA, 1));
				provas.anotar(new ProvaDeTaxa(pool, 2500, chamadorB, 1));

				assertEquals(2, provas.descarregar(), "três fechamentos do mesmo chamador tinham de virar UMA linha");
				assertEquals(3, fechamentos(provas, pool, 2500, chamadorA), "o lote não somou os fechamentos");
				assertEquals(1, fechamentos(provas, pool, 2500, chamadorB),
						"o chamador não separou a evidência — 'provado por dois' não se reaplicaria");

				provas.anotar(new ProvaDeTaxa(pool, 2500, chamadorA, 1));
				provas.anotar(new ProvaDeTaxa(pool, 2500, chamadorA, 1));
				provas.descarregar();

				assertEquals(5, fechamentos(provas, pool, 2500, chamadorA),
						"o segundo lote substituiu o primeiro em vez de somar");

				assertEquals(0, provas.descarregar(), "descarregar sem nada anotado gravou alguma coisa");

				provas.anotar(new ProvaDeTaxa(pool, 2501, chamadorA, 1));

				assertThrows(BancoException.class, provas::descarregar,
						"o banco aceitou taxa quebrada — a regra só existiria por convenção");
				assertThrows(BancoException.class, provas::descarregar,
						"o lote recusado sumiu da memória em vez de voltar para a próxima tentativa");

			} finally {
				apagar(banco, pool);
			}
		}
	}

	private long fechamentos(ProvasDeTaxaRepository provas, byte[] pool, int taxa, String chamador) {

		return provas.carregar().stream().filter(
				prova -> Arrays.equals(prova.pool(), pool) && prova.taxa() == taxa && prova.chamador().equals(chamador))
				.mapToLong(ProvaDeTaxa::fechamentos).sum();
	}

	private byte[] endereco() {

		byte[] endereco = new byte[20];

		sorteio.nextBytes(endereco);

		return endereco;
	}

	private static void apagar(Postgres banco, byte[] pool) {

		banco.executar(conexao -> {

			try (PreparedStatement comando = conexao.prepareStatement("delete from provas_de_taxa where pool = ?")) {
				comando.setBytes(1, pool);
				comando.executeUpdate();
			}

			return null;
		});
	}
}