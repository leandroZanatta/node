package br.com.node.catalogo;

import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.nucleo.Par;

// Decide qual taxa e qual espaçamento valem para precificar uma pool.
//
// DUAS PROCEDÊNCIAS, e a ordem entre elas é a regra:
//   1. IDENTIDADE (`Fabricas`): o endereço prova a factory e o bytecode. Em V3 isso prova a taxa e o
//      espaçamento; em V2 de taxa fixa, a taxa. Quando existe, vence.
//   2. PROVA POR TROCA (`Taxas`): só onde a identidade não fixa a taxa — Biswap e MDEX, que guardam a
//      taxa por par, e V2 de factory desconhecida.
//
// Por que a identidade vence: a conferência contra o contrato achou duas pools V2 com taxa provada
// 3000 cobrando 2000 (PancakeSwap V1) e 2500 (PancakeSwap V2). O K só limita por cima, e o robô que
// calcula com a fórmula de 0,3% "prova" 3000 em qualquer pool mais barata. Observando trocas isso não
// se corrige; sabendo de que contrato a pool é, não existe.
//
// E AS DUAS SE CONFEREM. Quando as duas existem e discordam de um jeito que o K não permite, a pool
// fica fora — um dos dois dados está errado e não há como saber qual:
//   * V2 de taxa fixa: prova por troca ABAIXO da taxa da factory é impossível (o K não aceita saída
//     maior que o máximo), então é estado errado. Acima é o robô de 0,3%: esperado, e sem efeito.
//   * V3: a taxa está dentro do endereço. Prova por troca diferente é estado errado.
//
// V3 SEM IDENTIDADE NÃO SE PRECIFICA. Sem a tabela da factory não há espaçamento, e espaçamento errado
// faz a pool aceitar entrada que cruza tick — a única recusa que protege de calcular além da liquidez
// que se vê. A conferência achou uma factory V3 com 10000 → 2000, onde o mapa universal dizia 200.
public final class Tarifario {

	private final Catalogo catalogo;
	private final Taxas taxas;
	private final Fabricas fabricas;

	// Pools em que identidade e prova por troca discordam de um jeito impossível. Escrito e lido na
	// thread de quem precifica; contado para que "fica fora" nunca seja silencioso.
	private final Set<String> emConflito = ConcurrentHashMap.newKeySet();

	// A taxa que a CADEIA cobrou de nós (achada pela autópsia depois de um `K`). Vence as duas
	// procedências acima: identidade e prova por troca dizem o que a pool cobra de alguém; esta diz o que
	// cobra do NOSSO chamador, que é o único que importa para executar.
	private final Quarentena licoes;

	// V3 de factory DESCONHECIDA: espaçamento perguntado à pool, taxa pelo que ela cobrou (estável). Nulo
	// = desligado, e a regra antiga vale (V3 sem identidade não se precifica).
	private volatile EspacamentoPorChamada porChamada;

	public void porChamada(EspacamentoPorChamada porChamada) {
		this.porChamada = porChamada;
	}

	// Por que uma V3 sem factory conhecida está sem taxa — para o relatório dizer o que falta.
	public String porqueSemTaxaV3(byte[] pool) {

		EspacamentoPorChamada chamada = porChamada;

		if (chamada == null) {
			return "V3 sem taxa (factory desconhecida)";
		}

		if (chamada.de(pool).isEmpty()) {
			return "V3 sem espaçamento (perguntado à pool)";
		}

		return catalogo.taxaV3JaVista(pool) ? "V3: taxa instável (dinâmica)" : "V3: taxa ainda não observada";
	}

	public Tarifario(Catalogo catalogo, Taxas taxas, Fabricas fabricas) {
		this(catalogo, taxas, fabricas, new Quarentena());
	}

	public Tarifario(Catalogo catalogo, Taxas taxas, Fabricas fabricas, Quarentena licoes) {
		this.catalogo = catalogo;
		this.taxas = taxas;
		this.fabricas = fabricas;
		this.licoes = licoes;
	}

	public Optional<Integer> taxa(byte[] pool) {

		// POOL DE GERENTE: a taxa é a que o último `Swap` COBROU, e ela é exata — o evento a traz, e o
		// `GerenteTest` provou contra 1.235 pares reais que é essa que fecha a conta. Não há factory nem
		// prova por troca a conferir; a chave só garante que ela é FIXA (`ChaveDaPool.modelavel`).
		if (Catalogo.deGerente(pool)) {
			return catalogo.chaveDaPool(pool).filter(ChaveDaPool::modelavel).flatMap(c -> catalogo.taxaNoGerente(pool));
		}

		Optional<Par> par = catalogo.par(pool);

		if (par.isEmpty()) {
			return Optional.empty();
		}

		boolean v3 = catalogo.reservas(pool).isEmpty() && catalogo.estado(pool).isPresent();

		if (!v3) {
			Optional<Integer> medida = licoes.taxaMedida(pool);
			if (medida.isPresent()) {
				return medida;
			}
		}
		Optional<Origem> origem = fabricas.de(pool, par.get());

		if (origem.isPresent() && origem.get().taxa().isPresent()) {
			return conferida(pool, origem.get().taxa().getAsInt(), v3);
		}

		if (v3) {
			// Sem identidade: só com o espaçamento da própria pool e a taxa que ela cobrou, estável.
			EspacamentoPorChamada chamada = porChamada;
			return chamada != null && origem.isEmpty() && chamada.de(pool).isPresent() ? catalogo.taxaObservadaV3(pool)
					: Optional.empty();
		}

		// A 7a3eb6e7 cobra 5 bps dos seus dois chamadores e 10% de quem chama o par direto: taxa por
		// chamador não se vê por log, e é por isso que pool fechada a chamadores fica fora aqui.
		return taxas.fechadaAChamadores(pool) ? Optional.empty() : taxas.de(pool);
	}

	public OptionalInt espacamento(byte[] pool) {

		if (Catalogo.deGerente(pool)) {
			return catalogo.chaveDaPool(pool).map(c -> OptionalInt.of(c.espacamento())).orElse(OptionalInt.empty());
		}

		OptionalInt daIdentidade = catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).map(Origem::espacamento)
				.orElse(OptionalInt.empty());

		EspacamentoPorChamada chamada = porChamada;

		if (daIdentidade.isPresent() || chamada == null || catalogo.reservas(pool).isPresent()
				|| catalogo.estado(pool).isEmpty() || catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).isPresent()) {
			return daIdentidade;
		}

		return chamada.de(pool);
	}

	public boolean solidlyStable(byte[] pool) {
		return catalogo.par(pool).flatMap(par -> fabricas.de(pool, par))
				.map(o -> o.fabrica().solidlyStable()).orElse(false);
	}

	// True se a pool é criada por uma factory conhecida (CREATE2 bate). Pools sem identificação
	// podem ter curva diferente de xy=k — modelar como V2 causaria reverts no relay.
	public boolean identificada(byte[] pool) {
		return catalogo.par(pool).flatMap(par -> fabricas.de(pool, par)).isPresent();
	}

	public int quantasEmConflito() {
		return emConflito.size();
	}

	private Optional<Integer> conferida(byte[] pool, int daIdentidade, boolean v3) {

		Optional<Integer> provada = taxas.de(pool);

		boolean impossivel = provada.isPresent() && (v3 ? provada.get() != daIdentidade : provada.get() < daIdentidade);

		if (impossivel) {
			emConflito.add(HexFormat.of().formatHex(pool));
			return Optional.empty();
		}

		return Optional.of(daIdentidade);
	}
}
