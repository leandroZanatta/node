package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.node.nucleo.Arbitragem;
import br.com.node.sincronizacao.Cabeca;

// O desfecho de cada envio e a FOLGA até o bloco seguinte, e o lance que aprende com eles — sem rede.
class DesfechosELancesTest {

	private static final byte[] NOSSO = HexFormat.of().parseHex("65b52a63507fae61382e1bf1e14ffbd2716d0c3e");
	private static final byte[] RIVAL = HexFormat.of().parseHex("9999b0cdd35d7f3b281ba02efc0d228486940515");
	private static final byte[] POOL_A = HexFormat.of().parseHex("aa".repeat(20));
	private static final byte[] POOL_B = HexFormat.of().parseHex("bb".repeat(20));

	// Fração inicial 30 % (300/1000): valor arbitrário para os testes de aprendizado.
	private static final int FRACAO = 300;
	// Lucro de 0,001 BNB em wei (1e15); gasParaOLance de 460k.
	// Com rival a 1 gwei e 460k gas: rivalPago = 4,6e14 = 46% do lucro → rivalFracao = 460.
	private static final BigInteger LUCRO_BNB = BigInteger.valueOf(1_000_000_000_000_000L);
	private static final BigInteger GAS_LANCE = BigInteger.valueOf(460_000);

	@TempDir
	Path pasta;

	@Test
	@DisplayName("rival no N+1 com a gente no prazo: desfecho rival_n1, folga medida, fração dobra")
	void rivalNoPrazoDobraAFracao() throws Exception {

		Lances lances = new Lances(pasta.resolve("lances.json"), FRACAO);
		Desfechos desfechos = desfechos();
		List<Desfechos.Desfecho> vistos = new ArrayList<>();
		desfechos.aoDecidir(d -> {
			vistos.add(d);
			lances.aprender(d);
		});

		desfechos.registrar(envio("0x01", 100, 1_000));
		desfechos.arbitragem(arbitragem(RIVAL), 101, 3);
		desfechos.blocoFechado(102);
		assertEquals(0, vistos.size(), "decidiu antes da espera");
		desfechos.blocoFechado(108);

		assertEquals(1, vistos.size());
		assertEquals("rival_n1", vistos.get(0).classe());
		assertEquals(200L, vistos.get(0).folgaMs(), "folga = bloco N+1 (1200) − chegada (1000)");
		assertEquals(3, vistos.get(0).posicao());
		// Sem bid do rival: dobra → min(950, 300 × 2) = 600
		assertEquals(600, lances.fracaoPara("rota"), "no prazo e perdeu sem bid do rival: dobra a fração");
	}

	@Test
	@DisplayName("pool mexida no N+1 por tx que não é arbitragem: desfecho pool_mexida_n1, fração NÃO muda")
	void poolMexidaPorOutraTx() {

		Lances lances = new Lances(pasta.resolve("lances.json"), FRACAO);
		Desfechos desfechos = desfechos();
		List<Desfechos.Desfecho> vistos = new ArrayList<>();
		desfechos.aoDecidir(d -> {
			vistos.add(d);
			lances.aprender(d);
		});

		desfechos.registrar(envio("0x04", 100, 1_000));
		desfechos.transacao(List.of(), 101); // posição 0: não toca
		desfechos.transacao(List.of(new br.com.node.vo.Log(POOL_A, List.of(new byte[32]), new byte[0])), 101); // posição 1
		desfechos.blocoFechado(108);

		assertEquals("pool_mexida_n1", vistos.get(0).classe());
		assertEquals(1, vistos.get(0).posicao());
		assertEquals(FRACAO, lances.fracaoPara("rota"), "pool mexida sem coinbase não muda a fração");
	}

	@Test
	@DisplayName("sabendo quanto pagou quem passou na frente, a fração vai a rival + 5%")
	void fracaoTiradaDoMercado() {

		Lances lances = new Lances(pasta.resolve("lances.json"), FRACAO);
		Desfechos desfechos = desfechos();
		desfechos.aoDecidir(lances::aprender);

		desfechos.registrar(envio("0x05", 100, 1_000));
		// bloco N+1: posição 1 = rival a 1 gwei; lucro = 1 BNB, gas = 460k
		// rivalPago = 1e9 wei/gas × 460k gas = 4.6e14 wei
		// rivalFracao = 4.6e14 × 1000 / 1e18 = 460 (46%)
		// novaFracao = min(950, 460 + 50) = 510
		desfechos.precos(101, List.of(BigInteger.ZERO, BigInteger.valueOf(1_000_000_000L)));
		desfechos.arbitragem(arbitragem(RIVAL), 101, 1);
		desfechos.blocoFechado(108);

		assertEquals(510, lances.fracaoPara("rota"), "rival a 1 gwei com 1 BNB de lucro → fração = 51%");
	}

	@Test
	@DisplayName("veredito do contrato diferente de ok: desfecho `invalida`, e o lance NÃO aprende com ele")
	void invalidaNaoEnsina() {

		Lances lances = new Lances(pasta.resolve("lances.json"), FRACAO);
		Desfechos desfechos = desfechos();
		List<Desfechos.Desfecho> vistos = new ArrayList<>();
		desfechos.aoDecidir(d -> {
			vistos.add(d);
			lances.aprender(d);
		});

		desfechos.registrar(envio("0x06", 100, 1_000));
		desfechos.veredito("0x06", "NaoPagou");
		desfechos.precos(101, List.of(BigInteger.ZERO, BigInteger.valueOf(1_000_000_000L)));
		desfechos.arbitragem(arbitragem(RIVAL), 101, 1);
		desfechos.blocoFechado(108);

		assertEquals("invalida", vistos.get(0).classe());
		assertEquals(FRACAO, lances.fracaoPara("rota"), "bundle que reverteria ensinou o lance");
	}

	@Test
	@DisplayName("chegamos depois do bloco: fração NÃO muda (é tempo, não lance)")
	void atrasadoNaoMexeNaFracao() {

		Lances lances = new Lances(pasta.resolve("lances.json"), FRACAO);
		Desfechos desfechos = desfechos();
		desfechos.aoDecidir(lances::aprender);

		desfechos.registrar(envio("0x02", 100, 1_300));
		desfechos.arbitragem(arbitragem(RIVAL), 101, 1);
		desfechos.blocoFechado(108);

		assertEquals(FRACAO, lances.fracaoPara("rota"));
	}

	@Test
	@DisplayName("incluída desce a fração, e o lances.json volta igual depois de reiniciar")
	void incluidaDesceEPersiste() {

		Path arquivo = pasta.resolve("lances.json");
		Lances lances = new Lances(arquivo, FRACAO);
		Desfechos desfechos = desfechos();
		desfechos.aoDecidir(lances::aprender);

		desfechos.registrar(envio("0x03", 100, 1_000));
		desfechos.arbitragem(arbitragem(NOSSO), 101, 1);
		desfechos.blocoFechado(108);

		// 300 × 9/10 = 270
		assertEquals(270, lances.fracaoPara("rota"));
		assertEquals(270, new Lances(arquivo, FRACAO).fracaoPara("rota"), "o lance aprendido se perdeu no reinício");
	}

	@Test
	@DisplayName("lances.json ilegível estoura na subida, não recomeça calado")
	void arquivoIlegivelEstoura() throws Exception {
		Path arquivo = pasta.resolve("lances.json");
		Files.writeString(arquivo, "{ isto não é json");
		assertThrows(IllegalStateException.class, () -> new Lances(arquivo, FRACAO));
	}

	private Desfechos desfechos() {
		return new Desfechos(NOSSO, altura -> altura == 101 ? Optional.of(new Cabeca.Momentos(1_200, 0, 0)) : Optional.empty(),
				pasta.resolve("desfechos.jsonl"));
	}

	private static Desfechos.Envio envio(String hash, long altura, long chegadaMs) {
		return new Desfechos.Envio(hash, altura, Set.of(HexFormat.of().formatHex(POOL_A), HexFormat.of().formatHex(POOL_B)),
				chegadaMs, "cauda", "rota", FRACAO, BigInteger.ONE, LUCRO_BNB, GAS_LANCE);
	}

	private static Arbitragem arbitragem(byte[] executor) {
		return new Arbitragem(List.of(POOL_A, POOL_B), HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"),
				BigInteger.TEN, 2, 0, executor);
	}
}
