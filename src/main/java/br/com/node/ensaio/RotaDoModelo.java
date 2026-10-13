package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Tarifario;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.PoolV2;
import br.com.node.nucleo.PoolV3;

// Traduz o que o MODELO decidiu para o que o CONTRATO executa.
//
// É a peça que fecha o circuito fictício: o `Achado` carrega `nucleo.Travessia`
// (pool + estado + direção), o `Executor` exige (pool, entra, sai, tipo, taxa).
// Cada campo da tradução é uma hipótese do modelo posta à prova:
//
//  - `entra`/`sai`: o SENTIDO. Vêm do `Par` do catálogo + `Direcao` da travessia.
//    Trocar inverte a rota inteira — e rota invertida é TokenForaDaPool, não lucro.
//  - `v3`: o TIPO. Vem da classe da pool (`PoolV3` ou não). Curva errada erra por
//    ordens de grandeza, não por margem.
//  - `taxaBps`: a TAXA. Vem do `Tarifario` — identidade da factory vence, prova por
//    troca decide o resto. É o número que mais custou no projeto anterior
//    (5 bps gravados onde se cobrava 1000 — 13 reverts num dia).
//  - `montante`: a ENTRADA. É a `Oportunidade.entrada` do achado, em wei do token
//    de partida. O contrato lê o saldo que a primeira perna deixa, então o
//    montante do flash tem de ser exatamente o que o modelo dimensionou.
//  - `lucroMinimo`: o PISO com a TOLERÂNCIA do modelo em cima — `max(piso, lucro bruto − piso)`. O
//    que NÃO se faz mais: mandar `lucro bruto − piso` PURO, a conta antiga. Ela aceitava qualquer
//    lucro acima de `lucro − piso`, e quando a fenda encolhia até perto do piso o contrato podia
//    fechar uma arb que NÃO pagava o próprio gás. Exigir só o piso mataria a folga que absorve o
//    estado andar; o máximo dos dois preserva a folga E a promessa "INCLUÍDO ⟹ LUCROU acima do gás".
//
// Vazio quando o modelo não tem o que o contrato exige: pool sem par no catálogo,
// sem taxa no tarifário, ou direção que não casa com nenhum dos dois tokens do par.
// Recusar aqui é o tradutor dizendo "o modelo está incompleto" — e isso é LACUNA,
// não veredito da cadeia.
public final class RotaDoModelo {

	private RotaDoModelo() {
	}

	public record Chamada(List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {
	}

	public static Optional<Chamada> traduzir(Achado achado, Catalogo catalogo, Tarifario tarifario,
			BigInteger pisoEmWei) {

		if (achado.ciclo().pernas().size() != achado.travessias().size() || achado.travessias().isEmpty()) {
			return Optional.empty();
		}

		List<Salto> rota = new ArrayList<>();

		for (int i = 0; i < achado.travessias().size(); i++) {

			// O endereço vive na perna (topologia); o preço vive na travessia.
			// Andam em paralelo, na mesma posição — o Cotador montou assim.
			byte[] pool = achado.ciclo().pernas().get(i).pool();
			br.com.node.nucleo.Travessia travessia = achado.travessias().get(i);

			Optional<Salto> perna = traduzir(pool, travessia, catalogo, tarifario);

			if (perna.isEmpty()) {
				return Optional.empty();
			}

			rota.add(perna.get());
		}

		BigInteger montante = achado.oportunidade().entrada();

		if (montante.signum() <= 0) {
			return Optional.empty();
		}

		// O `lucroMinimo` é o CHÃO que chega (piso + pagamento ao builder) — nada acima dele.
		//
		// CICATRIZ: era `max(piso, lucro − piso)`. Numa fenda de lucro 10× o piso, o contrato exigia 9× o
		// piso: se o estado andasse e a arb rendesse 3×, ela revertia e o bundle caía — perdendo uma
		// execução que pagava o gás com sobra. A "folga" apertava em vez de folgar. O contrato só precisa
		// garantir que a arb se paga; qualquer lucro acima do chão é ganho, não motivo para reverter.
		BigInteger lucroMinimo = pisoEmWei.max(BigInteger.ZERO);

		return Optional.of(new Chamada(List.copyOf(rota), montante, lucroMinimo));
	}

	private static Optional<Salto> traduzir(byte[] pool, br.com.node.nucleo.Travessia travessia,
			Catalogo catalogo, Tarifario tarifario) {

		if (Catalogo.deGerente(pool)) {
			return traduzirDoGerente(pool, travessia, catalogo);
		}

		Optional<Par> par = catalogo.par(pool);
		Optional<Integer> taxa = tarifario.taxa(pool);

		if (par.isEmpty() || taxa.isEmpty()) {
			return Optional.empty();
		}

		if (par.get().token0().length != 20 || par.get().token1().length != 20) {
			return Optional.empty();
		}

		byte[] entra = travessia.direcao() == Direcao.ZERO_PARA_UM ? par.get().token0() : par.get().token1();
		byte[] sai = travessia.direcao() == Direcao.ZERO_PARA_UM ? par.get().token1() : par.get().token0();

		int taxaBps = taxa.get();

		// O contrato lê a taxa num uint16 em PONTOS-BASE, e a pool V2 guarda em
		// milionésimos. 25 bps = 2500 milionésimos. Sem a conversão, a pool pagaria
		// 100x a taxa — lucro certo no modelo, SemLucro certo na cadeia.
		// V3: a taxa já está no endereço (a identidade provou); o contrato a ignora.
		if (!(travessia.pool() instanceof PoolV3)) {
			if (taxaBps % Pool.PONTO_BASE != 0) {
				return Optional.empty();
			}
			taxaBps = taxaBps / Pool.PONTO_BASE;
		}

		return Optional.of(new Salto(hex(pool), hex(entra), hex(sai), travessia.pool() instanceof PoolV3,
				taxaBps));
	}

	// A perna de GERENTE: `pool` é o gerente, a pool mesma vai na `chave`, e a taxa não vai (o contrato não a
	// usa: quem cobra é o gerente). Entra e sai pelos LADOS da pool, em WBNB onde ela é de BNB nativo — o
	// Executor embrulha na borda. A chave tem de dar o próprio `poolId` (`confere`): chave errada é pedir ao
	// gerente uma pool que não é a do modelo.
	private static Optional<Salto> traduzirDoGerente(byte[] pool, br.com.node.nucleo.Travessia travessia,
			Catalogo catalogo) {

		Optional<br.com.node.catalogo.ChaveDaPool> chave = catalogo.chaveDaPool(pool)
				.filter(br.com.node.catalogo.ChaveDaPool::modelavel).filter(br.com.node.catalogo.ChaveDaPool::confere);
		Optional<Catalogo.Lados> lados = catalogo.lados(pool);

		if (chave.isEmpty() || lados.isEmpty() || !(travessia.pool() instanceof PoolV3)) {
			return Optional.empty();
		}

		boolean v4 = java.util.Arrays.equals(chave.get().gerente(), br.com.node.catalogo.Eventos.GERENTE_V4);
		byte[] entra = travessia.direcao() == Direcao.ZERO_PARA_UM ? lados.get().token0() : lados.get().token1();
		byte[] sai = travessia.direcao() == Direcao.ZERO_PARA_UM ? lados.get().token1() : lados.get().token0();

		return Optional.of(new Salto(hex(chave.get().gerente()), hex(entra), hex(sai),
				v4 ? Salto.TIPO_V4 : Salto.TIPO_INFINITY_CL, 0, chave.get().abi()));
	}

	private static String hex(byte[] endereco) {
		return HexFormat.of().formatHex(endereco);
	}
}
