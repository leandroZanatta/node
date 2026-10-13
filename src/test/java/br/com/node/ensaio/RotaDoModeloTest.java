package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Fabricas;
import br.com.node.catalogo.Tarifario;
import br.com.node.catalogo.Taxas;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Oportunidade;
import br.com.node.nucleo.Perna;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.Travessia;
import br.com.node.vo.Log;

// O tradutor se prova contra a decisão que o `Avaliador` tomou: a rota que ele
// monta tem de dizer os mesmos tokens, o mesmo montante e o mesmo piso que o
// achado — e a taxa do contrato tem de ser a do tarifário em pontos-base.
class RotaDoModeloTest {

	private static final byte[] TOKEN_A = hex("aa".repeat(20));
	private static final byte[] TOKEN_B = hex("bb".repeat(20));
	private static final byte[] POOL_AB = hex("11".repeat(20));

	private static final BigInteger GAS = BigInteger.valueOf(300_000_000_000_000L);

	@Test
	@DisplayName("o achado vira chamada com o mesmo sentido, montante e piso")
	void traduzAchadoParaChamada() {

		Catalogo catalogo = catalogo();
		Tarifario tarifario = new Tarifario(catalogo, taxas(catalogo), new Fabricas());

		// Ida A->B na pool AB, volta B->A na mesma pool: ciclo de duas pernas. Estas são as
		// travessias do NÚCLEO (pool + direção), não os saltos do contrato — o tradutor as lê.
		Travessia ida = new Travessia(new PoolV2(reserva(1_000_000), reserva(1_000_000), 2_500), Direcao.ZERO_PARA_UM);
		Travessia volta = new Travessia(new PoolV2(reserva(1_000_000), reserva(1_010_000), 2_500),
				Direcao.UM_PARA_ZERO);

		// Lucro bem ACIMA do gás: o `lucroMinimo` é só o piso — exigir `lucro − piso` fazia reverter uma arb
		// que encolheu no caminho mas ainda pagava o gás com sobra.
		BigInteger entrada = BigInteger.TEN.pow(18);
		BigInteger lucro = BigInteger.TEN.pow(15);

		Achado achado = new Achado(99L,
				new Ciclo(List.of(new Perna(POOL_AB, TOKEN_A, TOKEN_B), new Perna(POOL_AB, TOKEN_B, TOKEN_A))),
				new Oportunidade(entrada, entrada.add(lucro)), List.of(ida, volta));

		Optional<RotaDoModelo.Chamada> chamada = RotaDoModelo.traduzir(achado, catalogo, tarifario, GAS);

		assertTrue(chamada.isPresent(), "rota completa e lucrativa tem de traduzir");
		assertEquals(entrada, chamada.get().montante());
		assertEquals(GAS, chamada.get().lucroMinimo(), "o contrato exige o chão, não o lucro previsto");
		assertTrue(chamada.get().lucroMinimo().compareTo(GAS) >= 0, "e nunca cai abaixo do piso");

		List<br.com.node.ensaio.Salto> rota = chamada.get().rota();
		assertEquals(2, rota.size());
		assertEquals(hex(TOKEN_A), rota.get(0).entra());
		assertEquals(hex(TOKEN_B), rota.get(0).sai());
		assertEquals(hex(TOKEN_B), rota.get(1).entra());
		assertEquals(hex(TOKEN_A), rota.get(1).sai());
		// 2500 milionésimos no núcleo = 25 pontos-base no contrato.
		assertEquals(25, rota.get(0).taxaBps());
	}

	@Test
	@DisplayName("fenda fina: o lucro mínimo NÃO cai abaixo do piso — a conta antiga furava o gás")
	void fendaFinaNaoFuraOPiso() {

		Catalogo catalogo = catalogo();
		Tarifario tarifario = new Tarifario(catalogo, taxas(catalogo), new Fabricas());

		Travessia ida = new Travessia(new PoolV2(reserva(1_000_000), reserva(1_000_000), 2_500), Direcao.ZERO_PARA_UM);
		Travessia volta = new Travessia(new PoolV2(reserva(1_000_000), reserva(1_010_000), 2_500),
				Direcao.UM_PARA_ZERO);

		// Lucro só um wei acima do piso: `lucro − piso` seria 1 wei, e o contrato fecharia uma arb com
		// lucro muito abaixo do gás. Com o máximo, ele exige o piso inteiro.
		BigInteger entrada = BigInteger.TEN.pow(18);
		BigInteger lucro = GAS.add(BigInteger.ONE);

		Achado achado = new Achado(99L,
				new Ciclo(List.of(new Perna(POOL_AB, TOKEN_A, TOKEN_B), new Perna(POOL_AB, TOKEN_B, TOKEN_A))),
				new Oportunidade(entrada, entrada.add(lucro)), List.of(ida, volta));

		Optional<RotaDoModelo.Chamada> chamada = RotaDoModelo.traduzir(achado, catalogo, tarifario, GAS);

		assertTrue(chamada.isPresent());
		assertEquals(GAS, chamada.get().lucroMinimo(), "abaixo do piso o contrato não pode fechar");
	}


	private static Catalogo catalogo() {

		Catalogo catalogo = new Catalogo();

		catalogo.aprenderPar(POOL_AB, List.of(TOKEN_A, TOKEN_B));
		catalogo.aplicarSync(new Log(POOL_AB, List.of(), reservas(1_000_000, 1_000_000)), 1L);

		return catalogo;
	}

	private static Taxas taxas(Catalogo catalogo) {

		Taxas taxas = new Taxas();
		taxas.semear(List.of(new br.com.node.vo.ProvaDeTaxa(POOL_AB, 2_500, "00".repeat(20), 1)));

		return taxas;
	}

	private static BigInteger reserva(long valor) {
		return BigInteger.valueOf(valor);
	}

	private static byte[] reservas(long r0, long r1) {

		byte[] dados = new byte[64];
		byte[] b0 = BigInteger.valueOf(r0).toByteArray();
		byte[] b1 = BigInteger.valueOf(r1).toByteArray();

		System.arraycopy(b0, 0, dados, 32 - b0.length, b0.length);
		System.arraycopy(b1, 0, dados, 64 - b1.length, b1.length);

		return dados;
	}

	private static byte[] hex(String texto) {
		return HexFormat.of().parseHex(texto);
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
