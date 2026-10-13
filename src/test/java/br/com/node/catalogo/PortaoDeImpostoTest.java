package br.com.node.catalogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// O imposto POR DIREÇÃO, sem rede e determinístico.
//
// Desde 27/09 o imposto entra na CONTA da perna: `Impostos.venda(pool, token)` é o que o token perde ao ser
// transferido ao par, `Impostos.compra(pool, token)` o que perde quando o par o envia. Vazio é perna que não
// se pode fazer (honeypot, suspeito, venda de token sabidamente taxado que ninguém mediu por esta pool). A
// chave segue por (POOL, token): o imposto é do par de destino (BlackSwan: 1300 num par, 0 noutro).
class PortaoDeImpostoTest {

	@Test
	@DisplayName("taxado vira NÚMERO na conta; honeypot fica de fora; não-medido entra com zero")
	void taxadoViraNumero() {

		Impostos impostos = new Impostos();

		byte[] pool = endereco(10);
		byte[] naoMedido = endereco(1);
		byte[] limpo = endereco(2);
		byte[] taxado = endereco(3);
		byte[] honeypot = endereco(4);

		impostos.medidaVenda(pool, limpo, 0);
		impostos.medidaVenda(pool, taxado, 1300);
		impostos.honeypot(pool, honeypot);

		assertEquals(OptionalInt.of(0), impostos.venda(pool, naoMedido), "não-medido entra com zero (a simulação filtra)");
		assertEquals(OptionalInt.of(0), impostos.venda(pool, limpo));
		assertEquals(OptionalInt.of(1300), impostos.venda(pool, taxado), "taxado tinha de voltar como 1300 bps, não barrado");
		assertTrue(impostos.venda(pool, honeypot).isEmpty(), "honeypot não se vende");
		assertTrue(impostos.compra(pool, honeypot).isEmpty(), "nem se compra");
	}

	@Test
	@DisplayName("venda e compra são medidas DIFERENTES do mesmo (pool, token) — a bPay só cobra na compra")
	void vendaECompraSeparadas() {

		Impostos impostos = new Impostos();

		byte[] pool = endereco(20);
		byte[] bpay = endereco(21);

		impostos.medidaVenda(pool, bpay, 0);
		impostos.medidaCompra(pool, bpay, 300);

		assertEquals(OptionalInt.of(0), impostos.venda(pool, bpay), "a compra medida apagou a venda");
		assertEquals(OptionalInt.of(300), impostos.compra(pool, bpay), "a compra medida não voltou");
	}

	@Test
	@DisplayName("prova antiga SEM direção vale como teto da compra enquanto a compra não é medida")
	void provaAntigaSemDirecaoEhConservadora() {

		Impostos impostos = new Impostos();

		byte[] pool = endereco(22);
		byte[] token = endereco(23);

		// As linhas de antes de 27/09 guardam o maior dos dois lados; semeadas, caem no lado da venda.
		impostos.semear(java.util.List.of(new br.com.node.vo.ProvaDeImposto(pool, token, 500, false)));

		assertEquals(OptionalInt.of(500), impostos.compra(pool, token),
				"sem compra medida, a prova antiga tinha de valer como compra — prometer menos, não mais");

		impostos.medidaCompra(pool, token, 200);
		assertEquals(OptionalInt.of(200), impostos.compra(pool, token), "medida a compra, ela vence a prova antiga");
	}

	@Test
	@DisplayName("o mesmo token tem imposto numa pool e zero noutra — a cicatriz do BlackSwan")
	void oMesmoTokenDependeDaPool() {

		Impostos impostos = new Impostos();

		byte[] token = endereco(44);
		byte[] parQueTaxa = endereco(24);
		byte[] parLimpo = endereco(25);

		impostos.medidaVenda(parQueTaxa, token, 1300);
		impostos.medidaVenda(parLimpo, token, 0);

		assertEquals(OptionalInt.of(1300), impostos.venda(parQueTaxa, token));
		assertEquals(OptionalInt.of(0), impostos.venda(parLimpo, token), "chave por token daria os dois iguais");
	}

	@Test
	@DisplayName("suspeita do veredicto tira a perna na hora, mas MANDA remedir (não é prova)")
	void suspeitaBarraEMandaRemedir() {

		Impostos impostos = new Impostos();

		byte[] pool = endereco(30);
		byte[] token = endereco(31);

		impostos.medidaVenda(pool, token, 0);

		assertEquals(OptionalInt.of(0), impostos.venda(pool, token), "limpo libera");
		assertTrue(impostos.jaProvado(pool, token), "e a prova limpa dispensava remedir");

		impostos.suspeitar(pool, token);

		assertTrue(impostos.venda(pool, token).isEmpty(), "suspeito sai na hora");
		assertTrue(impostos.compra(pool, token).isEmpty(), "nos dois sentidos");
		assertFalse(impostos.jaProvado(pool, token), "e a sonda tem de REMEDIR — suspeita não é prova");
		assertTrue(impostos.suspeito(pool, token), "e ela é prioridade na fila da sonda");
		assertEquals(1, impostos.quantosSuspeitos());
	}

	private static byte[] endereco(int semente) {

		byte[] endereco = new byte[20];

		endereco[19] = (byte) semente;

		return endereco;
	}
}
