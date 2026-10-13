package br.com.node.ensaio;

import java.io.Closeable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import br.com.node.catalogo.Achado;
import br.com.node.nucleo.Perna;

public final class Backrun implements Closeable {

	// A MARGEM NÃO MORA AQUI. Ela já está dentro do `piso` (3/2 sobre o gás, o `MARGEM_NUM/DEN` do
	// `CustoDeGas`), e aqui havia um `MARGEM = 1` multiplicando-o: sem efeito nenhum, mas lido como
	// se DOIS lugares decidissem a margem — e aí mexer num e não no outro pareceria funcionar. O
	// número que fica é o do `piso`, porque é o MESMO que o `Disparo` manda ao contrato como
	// `lucroMinimo`: é o que impede o portão e o contrato de divergirem.

	// Janela de dedup: a MESMA oportunidade (mesmas pools, mesma entrada, mesmo lucro previsto) não se
	// reenvia nesse intervalo.
	// Sem isto, uma fenda persistente era despachada a cada bloco (dezenas de
	// vezes: 498 despachos =
	// ~8 oportunidades × dezenas), gerando "bundle already existed" e spammando o
	// relay à toa.
	private static final long JANELA_DEDUP_MS = 60_000;

	private static final Logger LOG = Logger.getLogger(Backrun.class.getName());

	private final CustoDeGas custoDeGas;
	private final Disparo disparo;
	private final Gabarito gabarito;

	private final ExecutorService envio = Executors
			.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "backrun-envio"));

	// O N+1 corre contra o bloco: não espera na fila da cauda nem das sombras, e dois N+1 seguem juntos.
	private final ExecutorService envioN1 = Executors.newVirtualThreadPerTaskExecutor();

	// Assinatura da oportunidade (pools ordenadas) → instante do último envio. Poda
	// o que passou da
	// janela quando cresce. Lido/escrito na thread que considera — mas considerar
	// vem de threads da
	// mempool e do cursor, então concorrente.
	private final Map<String, Long> visto = new ConcurrentHashMap<>();

	private final AtomicLong recebidos = new AtomicLong();
	private final AtomicLong abaixoPiso = new AtomicLong();
	private final AtomicLong deduplicados = new AtomicLong();
	private final AtomicLong despachados = new AtomicLong();

	// O PLACAR do que o piso barrou, para a pergunta "piso apertado demais ou conta errada?" ter
	// resposta em número em vez de opinião. Guarda o MELHOR caso já recusado — a maior fração do
	// piso — com o lucro e o piso que a produziram. A leitura é direta: melhor recusada a 2% do piso
	// quer dizer que o piso não é o problema (a fenda é poeira); a 97%, que o piso é que aperta.
	private final AtomicLong melhorFracaoBps = new AtomicLong();

	private static final long JANELA_DO_REGISTRO_MS = 5 * 60_000L;
	private long inicioDaJanela = System.currentTimeMillis();
	private long melhorDaJanelaBps;
	private final AtomicReference<BigInteger> melhorLucro = new AtomicReference<>(BigInteger.ZERO);
	private final AtomicReference<BigInteger> melhorPiso = new AtomicReference<>(BigInteger.ZERO);

	// A RAIZ da melhor recusa (o token em que o piso e o lucro estão medidos). O `melhor_lucro` e o
	// `melhor_piso` são wei DO TOKEN DA RAIZ, e sem este campo eles são um número sem unidade: foi
	// assim que "0,0133 vs 0,0168" foi lido como BNB quando era USDT — 600× de erro na leitura.
	private final AtomicReference<String> melhorRaiz = new AtomicReference<>("");

	// Recusadas que PAGARIAM o gás (lucro ≥ 1× o custo), barradas SÓ pela margem de 3/2. São as
	// únicas em que mexer na margem muda o desfecho — o resto não paga nem o próprio gás. Sem esta
	// separação, "16 abaixo do piso" não diz se perdemos 16 fendas boas ou 16 poeiras.
	private final AtomicLong pagariaOGas = new AtomicLong();

	// AUTÓPSIA SOMBRA: o melhor recusado que paga o gás vai à autópsia por `eth_call`, sem envio. Sem
	// isto o modelo só é conferido quando algo passa do piso — e na corrida de 23/09 18:33 nada passou
	// em 106 min (3.743 achados), então a precisão do modelo ficou sem medida nenhuma. Com teto de ritmo
	// (uma a cada SOMBRA_INTERVALO_MS) e sem repetir o mesmo conjunto de pools dentro de SOMBRA_JANELA_MS:
	// é amostra, e o RPC público não é nosso.
	private static final long SOMBRA_INTERVALO_MS = 20_000;
	private static final long SOMBRA_JANELA_MS = 600_000;
	private final AtomicLong ultimaSombra = new AtomicLong();
	private final Map<String, Long> sombraVista = new ConcurrentHashMap<>();
	private final AtomicLong sombras = new AtomicLong();

	public Backrun(CustoDeGas custoDeGas, Disparo disparo, Gabarito gabarito) {
		this.custoDeGas = custoDeGas;
		this.disparo = disparo;
		this.gabarito = gabarito;
	}

	// Do bloco aplicado à fenda chegar aqui: o tempo do GARIMPO, medido em toda fenda da cauda — e não só
	// nas poucas que viram envio. Com o `bloco_ate_aplicado` da `Cabeca`, fecha a conta até a decisão.
	private volatile java.util.function.LongFunction<java.util.Optional<br.com.node.sincronizacao.Cabeca.Momentos>> momentos = altura -> java.util.Optional
			.empty();
	private final br.com.node.util.Amostras aplicadoAteBackrun = new br.com.node.util.Amostras(2_000);
	private final br.com.node.util.Amostras blocoAteBackrun = new br.com.node.util.Amostras(2_000);

	public void medirCom(
			java.util.function.LongFunction<java.util.Optional<br.com.node.sincronizacao.Cabeca.Momentos>> momentos) {
		this.momentos = momentos;
	}

	public void considerar(List<Achado> fendas, byte[] cruaPendente) {

		if (cruaPendente == null && !fendas.isEmpty()) {
			long agora = System.currentTimeMillis();
			momentos.apply(fendas.get(0).altura()).ifPresent(m -> {
				aplicadoAteBackrun.anotar(agora - m.aplicadoMs());
				blocoAteBackrun.anotar(agora - m.blocoMs());
			});
		}

		recebidos.addAndGet(fendas.size());
		Achado melhor = null;
		Achado melhorRecusada = null;

		for (Achado fenda : fendas) {

			// Toda fenda, abaixo do piso ou não: o gabarito mede se a DETECÇÃO acerta, e o piso é outra
			// pergunta (se vale para nós).
			gabarito.anotar(fenda, cruaPendente != null);

			BigInteger gasDaCauda = custoDeGas.estimarGasDasTravessias(fenda.travessias(), fenda.ciclo().pernas());
			byte[] raiz = fenda.ciclo().pernas().get(0).entra();
			Optional<BigInteger> piso = custoDeGas.piso(raiz, fenda.oportunidade().entrada(), gasDaCauda);

			// O PISO é o único filtro de VALOR do sistema, e aqui é o único lugar que o
			// aplica: o
			// `Disparo` só o usa como `lucroMinimo` do contrato. O número sai do número e do
			// tipo das
			// pernas (cada V2 ≈ 75k, cada V3 ≈ 120k, + base, overhead e empréstimo), calibrado
			// com swaps
			// reais da BSC — não de uma constante genérica de 400k.
			// Sem ele o despacho vira a maior poeira do bloco: medido no ar, tudo que saía
			// estava 600× a
			// 3.500× abaixo deste número e revertia na simulação do relay (falha de CUSTO,
			// que o relay não
			// cobra e por isso não dói — mas também não ensina nada). Sem piso (raiz
			// não-numerário) é
			// falha FECHADA, como no `Disparo`: não se entra.
			BigInteger lucro = fenda.oportunidade().lucro();

			if (piso.isEmpty() || lucro.compareTo(piso.get()) < 0) {
				abaixoPiso.incrementAndGet();
				anotarRecusa(fenda, gasDaCauda, lucro, piso.orElse(null));
				boolean pagaOGas = piso.isPresent() && lucro.compareTo(custoDeGas.custoDoGas(raiz, gasDaCauda)) >= 0;
				if (pagaOGas && (melhorRecusada == null || emBnb(fenda).compareTo(emBnb(melhorRecusada)) > 0)) {
					melhorRecusada = fenda;
				}
				continue;
			}

			// Em BNB, não em wei cru: fendas de raízes diferentes (WBNB, USDT, BTCB) têm lucro em unidades
			// diferentes, e comparar os wei escolhia a de raiz com mais casas, não a que vale mais.
			if (melhor == null || emBnb(fenda).compareTo(emBnb(melhor)) > 0) {
				melhor = fenda;
			}
		}

		if (melhor == null) {
			if (melhorRecusada != null && cruaPendente == null) {
				sombra(melhorRecusada);
			}
			return;
		}

		if (repetida(melhor)) {
			return;
		}

		Achado escolhido = melhor;
		despachados.incrementAndGet();
		LOG.info(() -> "backrun: despacho lucro " + escolhido.oportunidade().lucro() + " wei, "
				+ escolhido.ciclo().pernas().size() + " pernas" + (cruaPendente == null ? " (cauda)" : " (n+1)"));
		// Todo disparo em paralelo (virtual threads). A cauda também: em 24/09 14:08 quatro disparos do
		// mesmo bloco saíram em série (476+402+315+246 ms) e o mais valioso chegou por último. A thread
		// única fica só para as sombras, que não correm contra o bloco.
		envioN1.execute(() -> disparo.disparar(escolhido, cruaPendente));
	}

	// A CONTA da recusa, escrita uma vez por melhora. Só na melhora, e isso é deliberado: a corrida
	// de 9 h recusou 16.538 fendas, e registrar cada uma trocaria um silêncio por uma inundação. O
	// que se quer ver é a MELHOR delas — a única que decidiria alguma coisa —, e ela aparece com
	// todos os números que a decisão usou: gás, custo (1×), piso (1,5×), lucro, e a fração do piso.
	//
	// `piso` nulo é a recusa por FALTA de piso (raiz que não é numerário): não há fração a medir,
	// só o fato, e ele já está contado no `abaixo_piso`.
	private void anotarRecusa(Achado fenda, BigInteger gas, BigInteger lucro, BigInteger piso) {

		if (piso == null) {
			return;
		}

		BigInteger custo = custoDeGas.custoDoGas(fenda.ciclo().pernas().get(0).entra(), gas);
		BigInteger bps = lucro.multiply(BigInteger.valueOf(10_000)).divide(piso);

		if (lucro.compareTo(custo) >= 0) {
			pagariaOGas.incrementAndGet();
		}

		synchronized (melhorFracaoBps) {

			// A marca do REGISTRO é por janela, não da corrida inteira. CICATRIZ de 26/09: com a marca
			// acumulada desde a subida, a linha sumia por uma hora depois de a noite chegar a 9.775 bps — e
			// parecia node travado, quando as fendas recebidas seguiam em ~5 a 7 mil por meia hora. Cada janela
			// volta a mostrar a melhor recusa dela; o relatório continua com a melhor da corrida.
			long agora = System.currentTimeMillis();
			if (agora - inicioDaJanela >= JANELA_DO_REGISTRO_MS) {
				inicioDaJanela = agora;
				melhorDaJanelaBps = 0;
			}

			if (bps.longValue() > melhorFracaoBps.get()) {
				melhorFracaoBps.set(bps.longValue());
				melhorLucro.set(lucro);
				melhorPiso.set(piso);
			}

			if (bps.longValue() <= melhorDaJanelaBps) {
				return;
			}

			melhorDaJanelaBps = bps.longValue();
			melhorRaiz.set(HexFormat.of().formatHex(fenda.ciclo().pernas().get(0).entra()));

			List<String> pools = new ArrayList<>();
			for (Perna perna : fenda.ciclo().pernas()) {
				pools.add(HexFormat.of().formatHex(perna.pool()));
			}

			LOG.info(() -> "piso: melhor recusa em " + bps + " bps (gás " + gas + " · custo " + custo + " · piso "
					+ piso + " · lucro " + lucro + " · líquido " + lucro.subtract(custo)
					+ " — custo, piso, lucro e líquido em wei DO TOKEN DA RAIZ) · raiz "
					+ HexFormat.of().formatHex(fenda.ciclo().pernas().get(0).entra()) + " · "
					+ fenda.ciclo().pernas().size() + " pernas · entrada " + fenda.oportunidade().entrada()
					+ (lucro.compareTo(custo) >= 0 ? " · PAGARIA o gás: barrada só pela margem" : " · não paga nem o gás")
					+ " · pools " + String.join(",", pools));
		}
	}

	private void sombra(Achado achado) {

		long agora = System.currentTimeMillis();
		long ultima = ultimaSombra.get();

		if (agora - ultima < SOMBRA_INTERVALO_MS) {
			return;
		}

		if (sombraVista.size() > 10_000) {
			sombraVista.values().removeIf(quando -> agora - quando > SOMBRA_JANELA_MS);
		}

		String assinatura = assinatura(achado);
		Long vista = sombraVista.get(assinatura);

		if (vista != null && agora - vista < SOMBRA_JANELA_MS) {
			return;
		}

		if (!ultimaSombra.compareAndSet(ultima, agora)) {
			return;
		}

		sombraVista.put(assinatura, agora);
		sombras.incrementAndGet();
		envio.execute(() -> disparo.examinarSemEnviar(achado));
	}

	// O lucro da fenda em BNB. Só é chamado com piso presente — e o piso exige cotação da raiz —, então sem
	// câmbio aqui é dado incoerente: estoura, em vez de a fenda perder a ordem valendo zero.
	private BigInteger emBnb(Achado fenda) {
		byte[] raiz = fenda.ciclo().pernas().get(0).entra();
		return custoDeGas.paraBnb(raiz, fenda.oportunidade().lucro()).orElseThrow(() -> new IllegalStateException(
				"fenda com piso e sem câmbio BNB para a raiz " + HexFormat.of().formatHex(raiz)));
	}

	private static String assinatura(Achado achado) {

		List<String> pools = new ArrayList<>();
		for (Perna perna : achado.ciclo().pernas()) {
			pools.add(HexFormat.of().formatHex(perna.pool()));
		}
		pools.sort(null);

		return String.join("-", pools);
	}

	// Já mandamos ESTA oportunidade dentro da janela? Se sim, dedup — não reenvia. Poda entradas velhas
	// quando o mapa cresce, para não vazar memória.
	//
	// CICATRIZ: a assinatura era só o conjunto de pools, por 60 s. Numa pool que anda a cada bloco a fenda
	// REABRE com outro tamanho no mesmo ciclo — e era calada por um minuto (~130 blocos) depois do primeiro
	// despacho, que quase sempre perde. Com entrada e lucro na assinatura só se cala o reenvio idêntico
	// (a fenda persistente que não mudou), que é o que a janela existia para barrar.
	private boolean repetida(Achado achado) {

		long agora = System.currentTimeMillis();

		if (visto.size() > 10_000) {
			visto.values().removeIf(quando -> agora - quando > JANELA_DEDUP_MS);
		}

		String assinatura = assinatura(achado) + "@" + achado.oportunidade().entrada() + "/"
				+ achado.oportunidade().lucro();

		Long ultimo = visto.get(assinatura);

		if (ultimo != null && agora - ultimo < JANELA_DEDUP_MS) {
			deduplicados.incrementAndGet();
			return true;
		}

		visto.put(assinatura, agora);
		return false;
	}

	public Map<String, Object> resumo() {

		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("recebidos", recebidos.get());
		resumo.put("cauda_aplicado_ate_backrun_ms", aplicadoAteBackrun.resumo());
		resumo.put("cauda_bloco_ate_backrun_ms", blocoAteBackrun.resumo());
		resumo.put("abaixo_piso", abaixoPiso.get());
		resumo.put("pagaria_o_gas", pagariaOGas.get());
		resumo.put("melhor_fracao_do_piso_bps", melhorFracaoBps.get());
		resumo.put("melhor_lucro", melhorLucro.get().toString());
		resumo.put("melhor_piso", melhorPiso.get().toString());
		// A unidade das duas linhas acima, e o câmbio que converteu o piso: sem eles o piso é um número
		// que não se sabe ler nem conferir (foi o que aconteceu no relatório de 25/09).
		resumo.put("melhor_piso_raiz", melhorRaiz.get());
		resumo.put("cambio_usdt_bnb", custoDeGas.cambioUsdtPorBnb().map(BigInteger::toString).orElse("nunca medido"));
		resumo.put("deduplicados", deduplicados.get());
		resumo.put("despachados", despachados.get());
		resumo.put("autopsias_sombra", sombras.get());
		return resumo;
	}

	@Override
	public void close() {
		envio.shutdownNow();
		envioN1.shutdownNow();
	}
}
