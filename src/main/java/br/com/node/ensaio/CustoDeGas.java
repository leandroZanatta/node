package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import br.com.node.catalogo.Catalogo;
import br.com.node.nucleo.EstadoV3;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.PoolV3;
import br.com.node.nucleo.ReservasV2;
import br.com.node.nucleo.Travessia;

// Converte o preço de gás vivo da rede nos números que a execução usa: o maxFee que a gente assina
// e o piso de lucro que exige para entrar — este último NO TOKEN em que o ciclo fecha (o numerário
// da primeira perna), porque é nesse token que o contrato mede o lucro.
//
// Nasceu de um erro medido: o piso usava uma constante de ~1 gwei (era da BSC cara) enquanto a taxa
// usava o preço vivo. A BSC roda a 0,05 gwei — o piso pedia ~34× o gás real e recusava o mercado
// inteiro (`pagariam_gas`=0). Agora piso e taxa saem do MESMO preço, relido da rede, e a margem de
// 3/2 é literal sobre o gás.
//
// A ESTIMATIVA DE GÁS é ESTRUTURAL e CONSERVADORA: um teto por tipo de perna, não um chute único.
// As constantes saíram de swaps REAIS da BSC medidos numa janela de ~60 blocos (comentário de cada
// uma), e são teto de propósito — superestimar o piso só recusa fenda boa (perder oportunidade não
// custa gas); subestimar faz entrar em fenda que não paga o próprio gas. Quem quiser o número EXATO
// da EVM pergunta ao nó (`RpcDoEnsaio.estimarGas`), que mede imposto, tick cruzado e slot frio; aí o
// valor medido substitui o teto tanto no limite quanto no piso. O analítico é o padrão e o fallback.
//
// Não assina, não envia, não decide se envia — só traduz preço em números. Quem paga a rede (o
// `Disparo`) e quem filtra achado (o `Backrun`) perguntam aqui.
public final class CustoDeGas {

	private static final Logger LOG = Logger.getLogger(CustoDeGas.class.getName());

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] BUSD = HexFormat.of().parseHex("e9e7cea3dedca5984780bafc599bd69add087d56");
	private static final byte[] USDC = HexFormat.of().parseHex("8ac76a51cc950d9822d68b83fe1ad97b32cd580d");
	private static final byte[] U    = HexFormat.of().parseHex("ce24439f2d9c6a2289f741120fe202248b666666");

	// Pool WBNB/U (PancakeSwap V2): token0=WBNB (bb4c…<ce24…), token1=U. Usada como oráculo U↔BNB.
	private static final byte[] POOL_WBNB_U = HexFormat.of().parseHex("108752b2a22c731ede3edac2205c63ae553e221a");

	// A pool canônica WBNB/USDT, o oráculo do preço BNB↔USDT. Endereço, nunca símbolo.
	//
	// MEDIDO EM PRODUÇÃO (25/09): ela é **V3** (tier 100 = 0,01%), não V2 — e o `emUsdt` só lia
	// `reservas` (V2). Resultado: o caminho da canônica NUNCA respondia, e TODA fenda enraizada em
	// USDT saía com o fator fixo de reserva. A prova estava na própria linha do relatório:
	// `piso/custo = 900 = 1,5 × 600`, exato, contra um preço real de ~777 USDT/BNB. O piso em USDT
	// estava ~23% barato — aceitando fenda que o gás real não paga.
	private static final byte[] POOL_WBNB_USDT = HexFormat.of().parseHex("172fcd41e0913e95784454622d1c3724f546f849");

	// 2^96, a escala do `sqrtPrecoX96` do V3. `preco = (sqrtPrecoX96/2^96)²` = token1 por token0.
	private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);
	private static final BigInteger Q192 = BigInteger.ONE.shiftLeft(192);

	// A TAXA DO EMPRÉSTIMO RELÂMPAGO — ZERO por padrão, porque ela JÁ ESTÁ no lucro do modelo.
	//
	// O contrato pega a entrada emprestada da primeira perna V2 e devolve exatamente o `montante`: o
	// empréstimo é o próprio swap da perna 0, cuja taxa (0,25% na Pancake) o `Avaliador` já desconta ao
	// cotar a saída. Somar 26 bps aqui cobrava a mesma taxa DUAS vezes. CICATRIZ: o comentário antigo
	// dizia que a devolução era `montante × 10.000/9.975`; o replay de 26/09 provou o contrário — o lucro
	// devolvido pelo contrato bateu ao wei com o modelo, sem taxa extra. Fica o botão `TAXA_FLASH_BPS`
	// para uma fonte de empréstimo que cobre à parte.
	private static final BigInteger TAXA_FLASH_BPS = BigInteger.valueOf(java.util.Optional
			.ofNullable(System.getenv("TAXA_FLASH_BPS")).map(String::trim).filter(v -> v.matches("\\d{1,5}"))
			.map(Long::parseLong).orElse(0L));

	// A última razão MEDIDA na canônica (wei a wei), ou nula enquanto nunca se mediu. SEM valor frio: até
	// 26/09 havia um 2.000 fixo aqui, usado em silêncio até a primeira leitura — o mesmo padrão do 600 fixo
	// que fez o relatório de mercado ler USDT como BNB. Sem câmbio medido, converter USDT ESTOURA.
	private volatile BigInteger usdtPorBnb;

	// Razão U/BNB medida na pool WBNB/U (reserve1/reserve0, 18 decimais). Nula até a primeira leitura.
	private volatile BigInteger uPorBnb;

	// --- calibração da EVM ---------------------------------------------------

	// Taxas de calldata da EVM (EIP-2028): byte zero custa 4, byte não-zero custa 16.
	private static final long GAS_BYTE_ZERO = 4L;
	private static final long GAS_BYTE_NAO_ZERO = 16L;

	// Os 21k obrigatórios de QUALQUER transação.
	private static final long GAS_BASE_TX = 21_000L;

	// Overhead do `Executor` que não depende do número de pernas: dispatch, checagem de
	// autorizado, leitura da rota, laço dos saltos, verificação de lucro e retorno.
	private static final long GAS_EXECUTOR_FIXO = 60_000L;

	// O empréstimo relâmpago. O primeiro par manda os tokens (um swap + transferência) e recebe de
	// volta com a taxa no fim (transferência + accounting) — medido junto dos swaps de router.
	private static final long GAS_EMPRESTIMO = 60_000L;

	// Custo de UMA perna. Teto, não mediana: nos routers da BSC (janela de ~60 blocos) um swap de 2
	// hops V2 fecha em ~117k de tx inteira (p90 ~189k) e um `exactInputSingle` V3 em ~117k de
	// mediana (p90 ~150k). Nossas pernas chamam o par direto — sem o overhead do router, mas pagando
	// a transferência ERC20 que o router amortiza e podendo pegar slot frio. Por isso o teto fica
	// ACIMA do que o router paga por hop e ABAIXO do par antigo 150k/200k, que dobrava o gás de uma
	// rota de 3 pernas (600k) e inflava junto o piso e o teto de gasto por tx.
	private static final long GAS_PERNA_V2 = 75_000L;
	private static final long GAS_PERNA_V3 = 120_000L;

	// Perna de GERENTE (V4, Infinity CL): a sessão (`unlock`/`lock` + callback), o swap, `sync`+`settle` do
	// que entra e `take` do que sai — e, se a pool é de BNB nativo, embrulhar ou desembrulhar. Medido no
	// fork da BSC em 25/09 (`Gerentes.fork.t.sol`): de ~70k (slots quentes, ERC20) a ~145k (frios,
	// Infinity) por perna. Teto, como os outros.
	private static final long GAS_PERNA_GERENTE = 150_000L;

	// A perna de gerente carrega a chave: 6 palavras de cabeça + comprimento + 5 ou 6 de chave ≈ 13
	// palavras, todas contadas como não-zero no teto.
	private static final long GAS_CALLDATA_POR_PERNA_GERENTE = 13L * 32L * 16L;

	// Teto de calldata por perna para quando NÃO se tem os bytes reais (o caminho da cauda, antes de
	// traduzir): cada perna ocupa 5 palavras = 160 bytes, e o teto é todos eles não-zero.
	private static final long GAS_CALLDATA_POR_PERNA = 160L * GAS_BYTE_NAO_ZERO;

	// Margem sobre o gás para entrar (o piso), como fração NUM/DEN. 3/2 (1,5×): baixa de propósito —
	// a decisão é "migalha que paga o gás serve, consistência importa mais que tamanho". Fica ACIMA
	// de 1× para o líquido ser positivo mesmo se o gás real diferir um pouco da estimativa, mas
	// captura as fendas entre 1× e 3× que o piso antigo (3×) jogava fora.
	//
	// `PISO_MARGEM` troca o 1,5 na subida (ex.: 1.1), em centésimos. Abaixo de 1,0 não aceita: piso menor que
	// o gás é pagar para trabalhar. É seguro descer até perto de 1: o pagamento ao builder é no máximo o que
	// sobra ACIMA do piso (`Disparo.pagamentoAoBuilder`), então o líquido nunca fica abaixo de (margem − 1)
	// × gás — e o gás daqui é teto calibrado, não mediana.
	private static final BigInteger MARGEM_DEN = BigInteger.valueOf(100);
	private static final BigInteger MARGEM_NUM = BigInteger.valueOf(java.util.Optional
			.ofNullable(System.getenv("PISO_MARGEM")).map(String::trim).flatMap(texto -> {
				// Valor ilegível ou abaixo de 1,0 ESTOURA na subida: antes caía calado no 1,5, e um erro de
				// digitação no servidor mudava o piso sem ninguém saber.
				long centesimos = Math.round(Double.parseDouble(texto.replace(',', '.')) * 100);
				if (centesimos < 100) {
					throw new IllegalStateException("PISO_MARGEM abaixo de 1,0: " + texto);
				}
				return java.util.Optional.of(centesimos);
			}).orElse(150L));

	// NÃO HÁ GORJETA FIXA AQUI, e a ausência é a correção de um erro medido DUAS vezes. Havia um 0,1
	// gwei fixo: somado à base no piso (`base + 0,1` = 0,15 gwei, 3× o mercado) e assinado como
	// gorjeta (0,1 = 2× o que a rede paga). Os dois erros somavam contra nós — piso caro recusa fenda
	// boa, e pagar acima do mercado compra o mesmo bloco por mais. O preço que se paga e o preço que
	// se cobra no piso são o MESMO número, e ele vem da rede: inventá-lo em constante é o que fazia o
	// piso divergir do custo real sem que nada acusasse.

	private final RpcDoEnsaio rpc;
	private final Catalogo catalogo;

	// O preço de MERCADO do gás, em wei/gás, relido a cada 10 s. É o `eth_gasPrice` do nó, e na BSC
	// ele É o que a transação paga por gás. MEDIDO numa janela real: `baseFeePerGas` = 0 em 12 de 12
	// blocos e o `effectiveGasPrice` do recibo = `maxPriorityFeePerGas` em 5 de 5 txs tipo-2 — com
	// base zero, `min(maxFee, base + gorjeta)` é a própria gorjeta. E a MODA do que o bloco paga é
	// 0,05 gwei (212 de 1.034 txs), exatamente o `eth_gasPrice`; o p90 de 0,5 gwei é MEV disputando
	// bloco, não o preço de incluir uma cauda não-disputada.
	//
	// Escrito pela thread agendada (`atualizar`), lido por toda thread que pergunta piso/taxa —
	// `volatile` é toda a coordenação. Nulo até a primeira leitura, e aí piso e taxa ESTOURAM: até 26/09
	// começava num 1 gwei fixo (20× o mercado), um número inventado que entrava na conta sem aviso.
	private volatile BigInteger mercado;

	private volatile BigInteger gorjeta = BigInteger.ZERO;

	public CustoDeGas(RpcDoEnsaio rpc, Catalogo catalogo) {
		this.rpc = rpc;
		this.catalogo = catalogo;
	}

	// Relê o preço-base da rede E o câmbio BNB↔USDT. Chamado periodicamente (thread agendada do
	// `Main`). Se o nó não responder, mantém o último valor — nunca zera o piso.
	public void atualizar() {

		try {
			comMercadoMedido(rpc.precoDeGas());
		} catch (RuntimeException e) {
			// Mantém o último MEDIDO (o preço da BSC é estável em 0,05 gwei), mas a falha fica à vista.
			br.com.node.util.Falhas.anotar("preço do gás (eth_gasPrice)", e);
		}

		atualizarCambio();
	}

	// Relê o câmbio da pool canônica e o GUARDA, para que a ausência momentânea dela não derrube o
	// piso para a constante fria. Loga só quando o preço anda (>1%), para o número ficar auditável
	// sem encher o log — foi por falta disto que o fator fixo passou 63 min sem ninguém ver.
	public void atualizarCambio() {

		Optional<BigInteger> medido = cambioDaCanonica();

		if (medido.isPresent()) {
			BigInteger anterior = usdtPorBnb;
			usdtPorBnb = medido.get();
			if (anterior == null) {
				LOG.info(() -> "câmbio BNB/USDT: primeira medida " + usdtPorBnb + " wei");
			} else {
				BigInteger desvio = anterior.subtract(usdtPorBnb).abs().multiply(BigInteger.valueOf(100));
				if (anterior.signum() > 0 && desvio.compareTo(anterior) > 0) {
					LOG.info(() -> "câmbio BNB/USDT: " + usdtPorBnb + " wei (era " + anterior + ")");
				}
			}
		}

		Optional<BigInteger> medidoU = cambioDaPoolU();

		if (medidoU.isPresent()) {
			BigInteger anteriorU = uPorBnb;
			uPorBnb = medidoU.get();
			if (anteriorU == null) {
				LOG.info(() -> "câmbio BNB/U: primeira medida " + uPorBnb + " wei");
			}
		}
	}

	// U por BNB a partir das reservas V2 da pool WBNB/U. token0=WBNB (bb4c<ce24), token1=U.
	private Optional<BigInteger> cambioDaPoolU() {

		Optional<Par> par = catalogo.par(POOL_WBNB_U);

		if (par.isEmpty()) {
			return Optional.empty();
		}

		Optional<ReservasV2> reservas = catalogo.reservas(POOL_WBNB_U);

		if (reservas.isEmpty() || reservas.get().reserva0().signum() <= 0) {
			return Optional.empty();
		}

		boolean wbnbEhToken0 = Arrays.equals(par.get().token0(), WBNB);

		BigInteger wbnbR = wbnbEhToken0 ? reservas.get().reserva0() : reservas.get().reserva1();
		BigInteger uR    = wbnbEhToken0 ? reservas.get().reserva1() : reservas.get().reserva0();

		if (wbnbR.signum() <= 0) {
			return Optional.empty();
		}

		return Optional.of(uR.divide(wbnbR));
	}

	// A razão USDT/BNB que a canônica publica, wei a wei, ou vazio se ela não está no catálogo
	// ainda. V2 e V3 são caminhos DIFERENTES: a canônica da BSC é V3, e ler só `reservas` era o erro
	// que mantinha o fator fixo de pé.
	private Optional<BigInteger> cambioDaCanonica() {

		Optional<Par> par = catalogo.par(POOL_WBNB_USDT);

		if (par.isEmpty()) {
			return Optional.empty();
		}

		boolean usdtEhToken0 = Arrays.equals(par.get().token0(), USDT);
		boolean wbnbEhToken0 = Arrays.equals(par.get().token0(), WBNB);

		if (!usdtEhToken0 && !wbnbEhToken0) {
			return Optional.empty();
		}

		Optional<ReservasV2> reservas = catalogo.reservas(POOL_WBNB_USDT);

		if (reservas.isPresent() && reservas.get().reserva0().signum() > 0
				&& reservas.get().reserva1().signum() > 0) {

			BigInteger usdt = usdtEhToken0 ? reservas.get().reserva0() : reservas.get().reserva1();
			BigInteger wbnb = usdtEhToken0 ? reservas.get().reserva1() : reservas.get().reserva0();

			// As duas moedas têm 18 casas, então a razão das reservas JÁ É o preço wei-a-wei: nada de
			// escalar aqui. A truncagem custa menos de 1 parte em 600 (o BNB vale 600+ USDT), e o erro
			// que importa — o de ordem de grandeza — é o que este caminho deixa de cometer.
			return Optional.of(usdt.divide(wbnb));
		}

		Optional<EstadoV3> estado = catalogo.estado(POOL_WBNB_USDT);

		return estado.isEmpty() ? Optional.empty() : cambioDoV3(estado.get().sqrtPrecoX96(), usdtEhToken0);
	}

	// USDT por WBNB (wei a wei) a partir do `sqrtPrecoX96` de uma pool V3 cujo par é WBNB/USDT. As
	// duas moedas têm 18 casas na BSC, então a razão crua JÁ É o preço: `preco = token1/token0`, e o
	// que se quer é o inverso quando o USDT é o token0 (que é o caso: 0x55d3… < 0xbb4c…).
	// Público para o teste poder fixar o valor medido em produção (~777 USDT/BNB).
	public static Optional<BigInteger> cambioDoV3(BigInteger sqrtPrecoX96, boolean usdtEhToken0) {

		if (sqrtPrecoX96 == null || sqrtPrecoX96.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger quadrado = sqrtPrecoX96.multiply(sqrtPrecoX96);

		if (quadrado.signum() <= 0) {
			return Optional.empty();
		}

		// token1 por token0, em unidades cruas — e o que se quer é USDT por WBNB (wei a wei), que é
		// este preço invertido quando o USDT é o token0 (é o caso: 0x55d3… < 0xbb4c…).
		BigInteger usdtPorBnb = usdtEhToken0 ? Q192.divide(quadrado) : quadrado.divide(Q192);

		return usdtPorBnb.signum() > 0 ? Optional.of(usdtPorBnb) : Optional.empty();
	}

	// O preço que a transação de FATO paga por gás: o de mercado MAIS a gorjeta. Com a
	// gorjeta em zero — o valor inicial — o efetivo É o mercado: 0,05 gwei medido, não 0,15.
	// Piso e taxa saem do MESMO número.
	// O preço de mercado MEDIDO (o `eth_gasPrice`). Separado para o teste informar a medida sem rede — em vez de
	// herdar um valor inventado, que é o que o 1 gwei inicial fazia.
	CustoDeGas comMercadoMedido(BigInteger precoPorGas) {
		if (precoPorGas == null || precoPorGas.signum() <= 0) {
			throw new IllegalArgumentException("preço do gás medido inválido: " + precoPorGas);
		}
		this.mercado = precoPorGas;
		return this;
	}

	public BigInteger precoEfetivo() {
		BigInteger medido = mercado;
		if (medido == null) {
			throw new IllegalStateException("preço do gás nunca medido: eth_gasPrice ainda não respondeu");
		}
		return medido.add(gorjeta);
	}

	// O câmbio USDT/BNB que o piso está usando agora (wei a wei, o mesmo número que converte o piso de
	// USDT). Público para o RELATÓRIO publicá-lo: sem ele, um piso em USDT é um valor sem unidade nem
	// escala — e foi assim que o fator fixo de 600 atravessou uma corrida inteira sem ninguém ver.
	public java.util.Optional<BigInteger> cambioUsdtPorBnb() {
		return java.util.Optional.ofNullable(usdtPorBnb);
	}

	// O maxFeePerGas para assinar: 2× o preço vigente (mercado + gorjeta) cobre uma subida sem
	// pagar 3× o mercado quando nada está disputado.
	public BigInteger maxTaxa() {
		return precoEfetivo().multiply(BigInteger.TWO);
	}

	// O maxPriorityFeePerGas para assinar: o preço efetivo INTEIRO, não só a gorjeta. Com base zero na
	// BSC, `min(maxFee, base + prioridade)` é a prioridade — assinar a gorjeta (0) pagava 0 gwei.
	// CICATRIZ de 24/09: o relay recusou "bundle price too low", e todo bundle nosso entrava por último
	// na ordem de preço do bloco.
	public BigInteger prioridade() {
		return precoEfetivo();
	}

	public void gorjeta(BigInteger gorjeta) {
		this.gorjeta = gorjeta == null || gorjeta.signum() < 0 ? BigInteger.ZERO : gorjeta;
	}

	// Gás estimado da rota do MODELO (`Travessia`), antes de virar `Salto` — é o que o portão do
	// `Backrun` e a primeira passada do `Disparo` pedem, porque traduzir exige tarifário.
	public BigInteger estimarGasDasTravessias(List<Travessia> travessias) {
		return estimarGasDasTravessias(travessias, List.of());
	}

	// Com as PERNAS, que dizem se a pool é de gerente (identidade de 32 bytes) — a `Travessia` só sabe que
	// a curva é de liquidez concentrada, e a perna de gerente custa mais que a V3 de endereço.
	public BigInteger estimarGasDasTravessias(List<Travessia> travessias, List<br.com.node.nucleo.Perna> pernas) {

		if (travessias == null || travessias.isEmpty()) {
			return BigInteger.valueOf(GAS_BASE_TX);
		}

		int v3 = 0;
		int gerente = 0;
		for (int i = 0; i < travessias.size(); i++) {
			if (i < pernas.size() && br.com.node.catalogo.Catalogo.deGerente(pernas.get(i).pool())) {
				gerente++;
			} else if (travessias.get(i).pool() instanceof PoolV3) {
				v3++;
			}
		}

		return estrutural(travessias.size() - v3 - gerente, v3, gerente);
	}

	// Gás estimado da rota TRADUZIDA, sem os bytes da calldata — teto de calldata por perna.
	public BigInteger estimarGas(List<Salto> rota) {

		if (rota == null || rota.isEmpty()) {
			return BigInteger.valueOf(GAS_BASE_TX);
		}

		int v3 = contarV3(rota);
		int gerente = contarGerente(rota);
		return estrutural(rota.size() - v3 - gerente, v3, gerente);
	}

	// Gás estimado da rota COM a calldata real do contrato. A parte estrutural é a mesma; o custo de
	// calldata passa a ser contado byte a byte (EIP-2028), em vez do teto por perna. É o número mais
	// preciso que se obtém SEM a EVM — e o ponto de partida que o `Disparo` manda ao nó.
	public BigInteger estimarGas(List<Salto> rota, byte[] calldata) {

		if (rota == null || rota.isEmpty()) {
			return BigInteger.valueOf(GAS_BASE_TX);
		}

		if (calldata == null || calldata.length == 0) {
			return estimarGas(rota);
		}

		int v3 = contarV3(rota);
		int gerente = contarGerente(rota);

		return BigInteger.valueOf(GAS_BASE_TX + GAS_EXECUTOR_FIXO + GAS_EMPRESTIMO)
				.add(BigInteger.valueOf(GAS_PERNA_V2).multiply(BigInteger.valueOf(rota.size() - v3 - gerente)))
				.add(BigInteger.valueOf(GAS_PERNA_V3).multiply(BigInteger.valueOf(v3)))
				.add(BigInteger.valueOf(GAS_PERNA_GERENTE).multiply(BigInteger.valueOf(gerente)))
				.add(gasDaCalldata(calldata));
	}

	// Custo EXATO dos bytes da calldata (EIP-2028). Público para o teste poder prová-lo: a diferença
	// entre contar byte a byte e usar o teto por perna é a diferença entre medir e chutar.
	public static BigInteger gasDaCalldata(byte[] calldata) {

		long zeros = 0;

		for (byte b : calldata) {
			if (b == 0) {
				zeros++;
			}
		}

		long naoZeros = calldata.length - zeros;

		return BigInteger.valueOf(zeros * GAS_BYTE_ZERO + naoZeros * GAS_BYTE_NAO_ZERO);
	}

	// Gás ESTRUTURAL: o que não depende dos bytes da calldata. Calldata entra como teto por perna.
	private static BigInteger estrutural(int pernasV2, int pernasV3, int pernasGerente) {

		return BigInteger.valueOf(GAS_BASE_TX + GAS_EXECUTOR_FIXO + GAS_EMPRESTIMO)
				.add(BigInteger.valueOf(GAS_PERNA_V2 + GAS_CALLDATA_POR_PERNA)
						.multiply(BigInteger.valueOf(pernasV2)))
				.add(BigInteger.valueOf(GAS_PERNA_V3 + GAS_CALLDATA_POR_PERNA)
						.multiply(BigInteger.valueOf(pernasV3)))
				.add(BigInteger.valueOf(GAS_PERNA_GERENTE + GAS_CALLDATA_POR_PERNA_GERENTE)
						.multiply(BigInteger.valueOf(pernasGerente)));
	}

	private static int contarGerente(List<Salto> rota) {
		return (int) rota.stream().filter(Salto::deGerente).count();
	}

	private static int contarV3(List<Salto> rota) {

		int v3 = 0;

		for (Salto salto : rota) {
			if (salto.v3()) {
				v3++;
			}
		}

		return v3;
	}


	// O CUSTO DO GÁS da rota, 1× e sem margem, EM WEI DO TOKEN DA RAIZ. É ele que responde
	// "esta fenda PAGA o próprio gás?"; o `piso` acrescenta a margem e a taxa do empréstimo, e
	// responde "vale a pena?". As duas perguntas são diferentes e por isso os dois números existem
	// separados: sem a distinção, uma fenda que paga o gás mas não a margem some no mesmo contador
	// da poeira, e "abaixo do piso" deixa de dizer QUAL das duas coisas aconteceu.
	//
	// O token da raiz entrou na assinatura porque sem ele este número saía em BNB e era comparado com
	// um lucro em USDT — 600× de erro no contador `pagaria_o_gas`, MEDIDO em produção (25/09).
	public BigInteger custoDoGas(byte[] tokenRaiz, BigInteger gasEstimado) {
		return converterDoBnb(tokenRaiz, precoEfetivo().multiply(gasEstimado));
	}

	// A TAXA DO EMPRÉSTIMO RELÂMPAGO sobre o valor tomado emprestado (a entrada do ciclo), EM WEI DO
	// TOKEN DA RAIZ — é neste mesmo token que o contrato pega emprestado para começar a rota.
	public BigInteger taxaDoEmprestimo(BigInteger montante) {
		return montante == null || montante.signum() <= 0 ? BigInteger.ZERO
				: montante.multiply(TAXA_FLASH_BPS).divide(BigInteger.valueOf(10_000));
	}

	// O piso de lucro para entrar, EM WEI DO TOKEN DE RAIZ (o token em que o ciclo abre e fecha, e em
	// que o contrato mede o lucro): o gás da rota × preço vigente × margem de 3/2, MAIS a taxa do
	// empréstimo relâmpago. WBNB: direto. USDT: convertido pelo preço da canônica. Qualquer outro
	// token: vazio — não se entra.
	//
	// As duas parcelas são as duas coisas que a fenda tem de pagar para o líquido ser positivo. O gás
	// é FIXO (uma rota de 3 pernas custa o mesmo com 3 ou 3.000 dólares de entrada); a taxa do
	// empréstimo é 25 bps de QUALQUER entrada. Numa fenda fina é ela que domina — medido em produção
	// (25/09): a melhor fenda de 63 min tinha 42,5 bps de borda bruta, 35,8 de gás e 25 de
	// empréstimo, ou seja 18,3 bps NEGATIVA antes de qualquer limiar.
	//
	// Recebe o GÁS já estimado (analítico ou medido pela EVM) e o MONTANTE emprestado: é o único jeito
	// de o `Disparo` refinar o piso com o número que o nó devolveu, sem recontar as pernas.
	public Optional<BigInteger> piso(byte[] tokenRaiz, BigInteger montante, BigInteger gasEstimado) {

		if (tokenRaiz == null || gasEstimado == null || gasEstimado.signum() <= 0) {
			return Optional.empty();
		}

		if (!Arrays.equals(tokenRaiz, WBNB) && !Arrays.equals(tokenRaiz, USDT) && !Arrays.equals(tokenRaiz, U)) {
			return Optional.empty();
		}

		BigInteger gasComMargem = precoEfetivo().multiply(gasEstimado).multiply(MARGEM_NUM).divide(MARGEM_DEN);

		return Optional.of(converterDoBnb(tokenRaiz, gasComMargem).add(taxaDoEmprestimo(montante)));
	}

	// Valor em BNB → wei do token da raiz. Identidade no WBNB; no USDT, pelo preço da canônica; em U, pela pool V2.
	// Só é chamado com raiz que já passou pelo portão dos numerários (`piso`/`custodoGas`).
	private BigInteger converterDoBnb(byte[] tokenRaiz, BigInteger emBnb) {
		if (Arrays.equals(tokenRaiz, WBNB)) return emBnb;
		if (Arrays.equals(tokenRaiz, U))    return emU(emBnb);
		return emUsdt(emBnb);
	}

	// BNB → token da raiz, e o caminho de volta. Existem para o PAGAMENTO AO BUILDER: o lucro é medido
	// no token da raiz, e o builder é pago em BNB. Mesma pool canônica do piso; raiz não-numerário: vazio.
	public Optional<BigInteger> deBnb(byte[] tokenRaiz, BigInteger bnb) {

		if (Arrays.equals(tokenRaiz, WBNB)) return Optional.of(bnb);
		if (Arrays.equals(tokenRaiz, USDT)) return Optional.of(emUsdt(bnb));
		if (Arrays.equals(tokenRaiz, U))    return Optional.of(emU(bnb));
		return Optional.empty();
	}

	public Optional<BigInteger> paraBnb(byte[] tokenRaiz, BigInteger quantia) {

		if (Arrays.equals(tokenRaiz, WBNB)) {
			return Optional.of(quantia);
		}

		if (Arrays.equals(tokenRaiz, U)) {
			BigInteger umBnbEmU = emU(BigInteger.TEN.pow(18));
			return umBnbEmU.signum() > 0 ? Optional.of(quantia.multiply(BigInteger.TEN.pow(18)).divide(umBnbEmU))
					: Optional.empty();
		}

		if (!Arrays.equals(tokenRaiz, USDT) && !Arrays.equals(tokenRaiz, BUSD) && !Arrays.equals(tokenRaiz, USDC)) {
			return Optional.empty();
		}

		// A razão da conversão de ida, invertida: quanto BNB vale 1 BNB em USDT, aplicado de volta.
		BigInteger umBnbEmUsdt = emUsdt(BigInteger.TEN.pow(18));

		return umBnbEmUsdt.signum() > 0 ? Optional.of(quantia.multiply(BigInteger.TEN.pow(18)).divide(umBnbEmUsdt))
				: Optional.empty();
	}

	// Converte um valor em wei de BNB para wei de USDT. A razão vem da canônica (V2 pelas reservas, V3
	// pelo `sqrtPrecoX96`); quando ela não está disponível NESTE instante, usa a última medida — e só
	// cai no valor frio se nunca se mediu nada. NUNCA devolve vazio: antes, o vazio derrubava toda arb
	// enraizada em USDT, e é por isso que a queda para a constante existe — mas ela é o ÚLTIMO
	// recurso, não o primeiro (ver o comentário da canônica).
	private BigInteger emUsdt(BigInteger valorBnb) {

		Optional<BigInteger> medido = cambioDaCanonica();

		if (medido.isPresent()) {
			usdtPorBnb = medido.get();
			return valorBnb.multiply(medido.get());
		}

		BigInteger ultimo = usdtPorBnb;

		if (ultimo == null) {
			throw new IllegalStateException("câmbio BNB/USDT nunca medido: a canônica ainda não está no catálogo");
		}

		return valorBnb.multiply(ultimo);
	}

	// Converte um valor em wei de BNB para wei de U (United Stables). Lê as reservas V2 da pool WBNB/U;
	// se não disponíveis agora, usa a última medida. ESTOURA se nunca mediu — mesma política do USDT.
	private BigInteger emU(BigInteger valorBnb) {

		Optional<BigInteger> medido = cambioDaPoolU();

		if (medido.isPresent()) {
			uPorBnb = medido.get();
			return valorBnb.multiply(medido.get());
		}

		BigInteger ultimo = uPorBnb;

		if (ultimo == null) {
			throw new IllegalStateException("câmbio BNB/U nunca medido: pool WBNB/U ainda não está no catálogo");
		}

		return valorBnb.multiply(ultimo);
	}

}
