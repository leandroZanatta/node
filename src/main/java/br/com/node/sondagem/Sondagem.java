package br.com.node.sondagem;

import java.io.Closeable;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Impostos;
import br.com.node.nucleo.Ciclo;
import br.com.node.nucleo.Grafo;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Perna;
import br.com.node.nucleo.ReservasV2;

// Enriquece o catálogo com o que o log não conta: o imposto que NÓS pagaríamos em cada token.
//
// É o "frio" do novo desenho — mas frio de ENRIQUECER propriedade que quase não muda, não a via de
// sincronização fria/quente do old, que morreu. Roda numa thread própria, com o RPC de orçamento
// próprio (`Rpc`, com a parede adaptativa), e ESCREVE no `Impostos`. Quem decide lê o `Impostos`, nunca
// esta classe nem a rede (P2).
//
// Varre as pools que o catálogo conhece e mede a VENDA de cada token DENTRO da pool — transferir para o
// par é o que os tokens taxam, e o par é a âncora com saldo para achar o slot. Token medido com
// imposto sai do portão do cotador; medido limpo entra; não medido continua fora, como já era.
//
// Uma pool por vez, sem pressa: a sondagem não está no caminho da decisão, então pode ser lenta e
// educada com os nós públicos. Mede só o que ainda não mediu — imposto de token não muda a cada bloco,
// e remedir tudo a cada rodada seria gastar a parede à toa. (Muda quando o dono do token o altera; a
// remedição periódica é refinamento anotado, não feita aqui.)
public final class Sondagem implements Closeable {

	private static final Logger LOG = Logger.getLogger(Sondagem.class.getName());

	// Recuo da cabeça para a foto: a janela de estado dos nós públicos é de 90 a 110 blocos; 20 dá folga
	// e mede um estado que ainda existe.
	private static final int RECUO = 20;

	// PRIORIDADE: vetar as pools que entram em ciclo a partir de um numerário, não o catálogo inteiro. Uma
	// hora varrendo tudo vetou pouco e nada conexo — gastou o orçamento em pools que nunca viram rota e em
	// varreduras de slot que não fecham, e o grafo ficou vazio. O que rende é vetar o conjunto CONEXO que
	// vira ciclo, então é dele que se parte. O numerário é endereço, nunca símbolo.
	private static final List<byte[]> NUMERARIOS = List.of(
			HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"),  // WBNB
			HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955"),  // USDT
			HexFormat.of().parseHex("e9e7cea3dedca5984780bafc599bd69add087d56"),  // BUSD
			HexFormat.of().parseHex("8ac76a51cc950d9822d68b83fe1ad97b32cd580d"),  // USDC
			HexFormat.of().parseHex("ce24439f2d9c6a2289f741120fe202248b666666")); // U (United Stables)

	private static final int PERNAS = 3;

	// Teto de pools novas por rodada, para a rodada terminar e reportar em vez de varrer horas de uma vez.
	private static final int POR_RODADA = 40;

	private final Catalogo catalogo;
	private final Impostos impostos;
	private final Sonda sonda;
	private final Cadeia cadeia;

	// Pares (pool, token) já medidos: não se remede a cada rodada. Chave por PAR, não por token, porque o
	// imposto é do par — o mesmo token precisa ser medido em cada pool por onde possa rodar.
	private final Set<String> medidos = ConcurrentHashMap.newKeySet();

	private final ScheduledExecutorService laco = Executors
			.newSingleThreadScheduledExecutor(tarefa -> new Thread(tarefa, "sondagem"));

	public Sondagem(Catalogo catalogo, Impostos impostos, Cadeia cadeia) {
		this.catalogo = catalogo;
		this.impostos = impostos;
		this.cadeia = cadeia;
		this.sonda = new Sonda(cadeia);
	}

	public void iniciar() {
		laco.scheduleWithFixedDelay(this::rodada, 5, 30, TimeUnit.SECONDS);
	}

	@Override
	public void close() {
		laco.shutdownNow();
	}

	// Uma passada, medindo até `POR_RODADA` pools novas do conjunto que entra em ciclo. Perto da cabeça:
	// a foto tem de estar na janela de estado, então lê a cabeça a cada rodada em vez de fixar uma vez.
	private void rodada() {

		try {

			long altura = cadeia.cabeca() - RECUO;
			int mediu = 0;

			for (byte[] pool : poolsEmCiclo()) {

				if (mediu >= POR_RODADA) {
					break;
				}

				Par par = catalogo.par(pool).orElse(null);

				if (par == null) {
					continue;
				}

				int antes = mediu;
				mediu += medir(pool, par.token0(), altura);
				mediu += medir(pool, par.token1(), altura);

				if (mediu == antes) {
					continue;
				}
			}

			int m = mediu;
			LOG.info(() -> "sondagem: " + m + " tokens medidos nesta rodada, " + impostos.quantosTributados()
					+ " tributados, " + impostos.quantosLimpos() + " limpos, " + impostos.quantosSuspeitos()
					+ " suspeitos, " + impostos.quantosProvados() + " provados pela sonda");

		} catch (RuntimeException e) {
			LOG.warning(() -> "rodada de sondagem falhou, fica para a próxima: " + e.getMessage());
		}
	}

	// As pools que aparecem em ciclo de até `PERNAS` a partir de um numerário — o conjunto conexo que vira
	// rota. Só topologia (par + estado), sem imposto: é o que decide QUEM vetar, e o imposto é o que a
	// vetagem vai medir. Reconstruído a cada rodada porque o catálogo cresce.
	private List<byte[]> poolsEmCiclo() {

		Grafo grafo = new Grafo();

		for (byte[] pool : catalogo.pools()) {

			// Pool de gerente não tem endereço: a vetagem transfere PARA a pool, e ali quem recebe é o
			// gerente de todas. Token taxado nela faz a sessão do gerente reverter — não custa gás.
			if (br.com.node.catalogo.Catalogo.deGerente(pool)) {
				continue;
			}

			boolean temEstado = catalogo.reservas(pool).isPresent() || catalogo.estado(pool).isPresent();

			if (temEstado) {
				catalogo.par(pool).ifPresent(par -> grafo.ligar(pool, par));
			}
		}

		Set<String> vistas = new java.util.LinkedHashSet<>();
		List<byte[]> pools = new java.util.ArrayList<>();

		for (byte[] numerario : NUMERARIOS) {
			for (Ciclo ciclo : grafo.ciclos(numerario, PERNAS)) {
				for (Perna perna : ciclo.pernas()) {
					if (vistas.add(HexFormat.of().formatHex(perna.pool()))) {
						pools.add(perna.pool());
					}
				}
			}
		}

		// PRIORIDADE: a SUSPEITA vem antes de tudo. Ela não é palpite — é o que a cadeia acabou de
		// responder sobre um par que já entrou em rota nossa (`TRANSFER_FAILED`), e o grafo já o
		// soltou. Remedi-lo primeiro é o que fecha o ciclo suspeita → veredicto → grafo.
		//
		// Depois dela, prova primeiro a pool que mais MEXE (é onde a fenda nasce) e, empatando, a de
		// maior reserva (arb que cobre gás mora em pool líquida; pool pequena rende micro). O
		// orçamento de chamada é escasso (o RPC público limita), então gastá-lo na ordem certa é o
		// que faz a cobertura render — não adianta provar em ordem arbitrária.
		pools.sort(java.util.Comparator.comparingInt(this::suspeita)
				.thenComparing(java.util.Comparator.comparingLong(catalogo::atividade).thenComparing(this::reserva)
						.reversed()));

		return pools;
	}

	// Esta pool tem um par sob suspeita — o que a cadeia disse que não move o token? 0 vem antes de 1.
	private int suspeita(byte[] pool) {

		return catalogo.par(pool)
				.filter(par -> impostos.suspeito(pool, par.token0()) || impostos.suspeito(pool, par.token1()))
				.isPresent() ? 0 : 1;
	}

	private BigInteger reserva(byte[] pool) {
		return catalogo.reservas(pool).map(ReservasV2::reserva0).orElse(BigInteger.ZERO);
	}

	// Mede a venda do token para dentro da pool, com o próprio par como âncora de saldo. Grava só quando
	// MEDIU — motivo de transporte não vira veredicto, e definitivo (slot não achado, honeypot) marca o
	// token como não-limpo sem inventar número.
	private int medir(byte[] pool, byte[] token, long altura) {

		// Já provado (semeado do banco ou medido antes) NÃO se remede: a prova de imposto é imutável,
		// e remedir torra o orçamento de RPC no que já se sabe. Era a quebra que segurava a cobertura
		// — a cada run a sonda refazia as centenas de provas do banco em vez de medir pool nova.
		if (impostos.jaProvado(pool, token)) {
			return 0;
		}

		String chave = HexFormat.of().formatHex(pool) + ":" + HexFormat.of().formatHex(token);

		// A suspeita FURA a regra "já medido nesta execução não se remede": ela existe justamente
		// porque a medida anterior (ou a ausência dela) se provou errada na cadeia.
		if (!impostos.suspeito(pool, token) && !medidos.add(chave)) {
			return 0;
		}

		Medida venda = sonda.venda(token, pool, pool, altura);

		if (venda.motivo() == Medida.Motivo.SEM_RESPOSTA) {
			// transporte, não veredicto: destrava para tentar de novo numa próxima rodada
			medidos.remove(chave);
			return 0;
		}

		if (venda.motivo().definitivo() && !venda.mediu()) {
			impostos.honeypot(pool, token);
			return 1;
		}

		// A compra é a outra metade, e a cicatriz FLS foi dela: 1100 bps no token, ZERO na venda que a
		// gente media. O par nos envia o token via swap; mede-se o que entra. Taxado em QUALQUER
		// direção condena — a rota paga imposto de venda ao entrar na pool e de compra ao sair.
		Medida compra = sonda.compra(pool, token, altura);

		// Cada direção grava a SUA medida — até 27/09 gravava o maior dos dois como um número só, que servia
		// para barrar a perna e não para fazer a conta dela.
		if (compra.motivo().definitivo() && !compra.mediu()) {
			impostos.honeypot(pool, token);
		} else if (compra.mediu() || (venda.mediu() && venda.bps() > 0)) {
			// Só grava a direção que se MEDIU: zero numa direção não medida seria provar limpo o que não se provou.
			if (venda.mediu()) {
				impostos.medidaVenda(pool, token, venda.bps());
			}
			if (compra.mediu()) {
				if (compra.bps() >= 10_000) {
					impostos.honeypot(pool, token);
				} else {
					impostos.medidaCompra(pool, token, compra.bps());
				}
			}
		} else {
			// A compra não se mediu (slot não achado) e a venda deu zero: gravar 0 seria provar limpo
			// o que só se provou na metade — e a prova é imutável. Fica sem prova; a próxima rodada tenta.
			medidos.remove(chave);
		}

		return 1;
	}
}
