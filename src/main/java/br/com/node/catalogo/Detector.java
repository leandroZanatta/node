package br.com.node.catalogo;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import br.com.node.nucleo.Arbitragem;
import br.com.node.vo.Log;

// Reconhece arbitragem numa transação pelo FLUXO DE TRANSFER, sem conhecer a DEX.
//
// Uma arbitragem deixa quem a executa com nenhum token negativo e algum positivo — nada foi gasto e
// algo foi ganho —, e o token do lucro SAIU dele e voltou maior (é um ciclo). Funciona igual com
// capital próprio e com empréstimo relâmpago, porque o empréstimo se paga dentro da mesma transação.
//
// Por que por `Transfer` e não por `Swap`: o detector anterior somava os eventos `Swap` V2/V3 e
// desistia da tx inteira se uma pool não estivesse no catálogo. Então a cobertura que o gabarito media
// era CIRCULAR — só contava a arbitragem que já sabíamos ver. Medido em 24/09 (757 blocos, pelo mesmo
// critério deste código): 214 de 365 arbitragens (71% do valor) tocavam Uniswap V4, PancakeSwap
// Infinity ou outro venue que não decodificamos, invisíveis para o detector por `Swap`.
//
// O recibo do p2p não traz `from`/`to` da tx, então quem executou é INFERIDO dos próprios `Transfer`:
// o endereço que satisfaz a regra acima. Os outros se separam sozinhos:
//   - a pool troca A por B: fica com A a mais e B a menos (tem negativo);
//   - o roteador que só repassa fica com saldo zero (não tem positivo);
//   - quem recebe taxa, ou quem compra com BNB nativo (o `Deposit` do WBNB não é `Transfer`),
//     recebe um token que nunca saiu dele (não é ciclo);
//   - o agregador que cobra taxa NA ENTRADA (recebe 30 USDT do usuário, repassa 29,91) parece ciclo,
//     mas o lucro dele veio do USUÁRIO. Na arbitragem o token do lucro volta de uma POOL — um contrato
//     que emite evento e não é token. CICATRIZ de 24/09: sem esta regra, 116 de 495 "arbitragens"
//     no confronto com 757 blocos reais eram taxa de agregador;
//   - o pagamento que chega e sai com uma taxinha (recebe 422,70 USDT, repassa 0,25) também "saiu e
//     voltou", mas numa arbitragem o que SAI é o principal, PARA UMA POOL: exige-se que o que saiu do
//     token do lucro para pools seja pelo menos o ganho. Isso também separa o roteador que cobra taxa
//     na SAÍDA (recebe da pool e repassa ao usuário, não a uma pool).
//
// O BNB nativo não aparece em log, então o `Withdrawal` do WBNB se julga pela ORDEM:
//   - desembrulhar DEPOIS da última entrada do token é embolsar o lucro (muitos bots pagam o builder em
//     nativo): neutro. Descontá-lo zerava arbitragens reais — a cobertura de valor caiu de 97% para 18%
//     no confronto;
//   - desembrulhar ANTES de o token voltar é gastá-lo no ciclo (pagar o Uniswap V4 em nativo): saída.
//     Tratado como neutro, inventou 0,606 BNB de lucro em 24/09.
// O `Deposit` segue neutro: o nativo que entra é capital de fora (msg.value), não prova ciclo.
// Limite conhecido: o bot que manda o lucro para outra carteira na mesma tx fica com saldo zero e não
// é visto.
//
// As "pools" são os emissores de `Swap` (V2, V3, gerente; e o cofre do Infinity) — para o V4 e o Infinity é o
// contrato único (PoolManager), o que basta para medir cobertura. Qualquer outro emissor de log (agregador,
// contrato de comissão) não é pool, e a rota precisa de pelo menos duas pools. SÓ LOG: não olha calldata, não
// pergunta a ninguém, e não julga se valeu a pena.
public final class Detector {

	private static final byte[] DEPOSIT = Eventos.assinatura("Deposit(address,uint256)");
	private static final byte[] WITHDRAWAL = Eventos.assinatura("Withdrawal(address,uint256)");
	private static final String ZERO = "00".repeat(20);

	// O BNB NATIVO NAS POOLS DE GERENTE. Nativo não emite `Transfer`, então o rival que paga BNB nativo a uma pool
	// V4/Infinity e recebe WBNB de outra aparecia com o WBNB inteiro de "lucro". Medido em 26/09 na cadeia: uma
	// "arbitragem de 0,1274 WBNB" rendeu ao contrato do rival +0,000039 WBNB e −0,000014 BNB nativo — ~0,000025,
	// o que o NOSSO modelo dava; e ~90% do valor das arbitragens alheias ≥ 0,01 WBNB passava por pool nativa.
	// Aqui o `Swap` do gerente vira movimento de WBNB entre QUEM CHAMOU (tópico 2) e o gerente (o cofre, no
	// Infinity), no sentido do evento — nativo e WBNB são o mesmo dinheiro para a conta do lucro. Precisa saber
	// qual lado da chave é o nativo: 0 ou 1, ou vazio se a pool não é nativa ou não se conhece a chave.
	private final java.util.function.Function<byte[], java.util.OptionalInt> ladoNativo;

	private static final String COFRE_INFINITY = "238a358808379702088667322f80ac48bad5e6c4";
	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	public Detector() {
		this(poolId -> java.util.OptionalInt.empty());
	}

	public Detector(java.util.function.Function<byte[], java.util.OptionalInt> ladoNativo) {
		this.ladoNativo = ladoNativo;
	}

	// O catálogo não é mais necessário para detectar; o construtor fica para quem já o passava.
	public Detector(Catalogo catalogo) {
		this(poolId -> java.util.OptionalInt.empty());
	}

	public Optional<Arbitragem> em(List<Log> daTransacao) {

		Set<String> tokens = new HashSet<>();
		Map<String, byte[]> enderecos = new HashMap<>();
		Map<String, Map<String, BigInteger>> fluxo = new LinkedHashMap<>();
		List<String[]> saidas = new ArrayList<>();
		List<BigInteger> valoresDasSaidas = new ArrayList<>();
		Map<String, Set<String>> origens = new HashMap<>();
		// A última entrada na conta de QUALQUER token: o desembrulho seguido dela pagou algo no meio da rota.
		Map<String, Integer> ultimaEntradaNaConta = new HashMap<>();
		List<Object[]> desembrulhos = new ArrayList<>();
		Set<String> pagaramNativo = new HashSet<>();
		// POOL É QUEM TROCA: emissor de `Swap` (V2, V3, gerente — e o cofre do Infinity, onde o dinheiro mora).
		// CICATRIZ de 27/09: "pool" era qualquer emissor de log que não fosse token, e agregadores e contratos de
		// comissão emitem log — comissão de bot e swap de usuário viravam "arbitragem" de dezenas de dólares
		// (conferido na cadeia: 0 de lucro), inflando mercado, gabarito e o funil das gordas.
		Set<String> trocadores = new HashSet<>();
		int indice = -1;

		for (Log log : daTransacao) {

			indice++;
			String emissor = HexFormat.of().formatHex(log.endereco());

			// Pela ASSINATURA do evento (V4/Infinity inclusive), não pelo endereço do gerente: vale para forks. O
			// `Sync` também identifica pool — é quem guarda reserva, e todo swap de par V2 o emite.
			if (log.assinadoPor(Eventos.SWAP_V2) || log.assinadoPor(Eventos.SYNC_V2) || Eventos.ehSwapV3(log)
					|| log.assinadoPor(Eventos.SWAP_V4) || log.assinadoPor(Eventos.SWAP_INFINITY_CL)) {
				trocadores.add(emissor);
				if (java.util.Arrays.equals(log.endereco(), Eventos.GERENTE_INFINITY_CL)) {
					trocadores.add(COFRE_INFINITY);
				}
			}

			if (Eventos.ehSwapDeGerente(log) && log.topicos().size() >= 3) {

				java.util.OptionalInt lado = ladoNativo.apply(log.topicos().get(1));
				BigInteger quantia = lado.isPresent() ? Eventos.palavraComSinalGrande(log, lado.getAsInt()) : BigInteger.ZERO;

				if (quantia.signum() != 0) {

					String wbnb = HexFormat.of().formatHex(WBNB);
					String quem = HexFormat.of().formatHex(Eventos.enderecoDoTopico(log, 2));
					// No Infinity o dinheiro mora no cofre, que é onde os `Transfer` ERC20 também vão.
					String contraparte = java.util.Arrays.equals(log.endereco(), Eventos.GERENTE_INFINITY_CL) ? COFRE_INFINITY
							: emissor;

					tokens.add(wbnb);
					enderecos.putIfAbsent(wbnb, WBNB);
					pagaramNativo.add(quem);

					// Visão de quem chama: negativo é o que ele PAGOU.
					if (quantia.signum() < 0) {
						mover(fluxo, saidas, valoresDasSaidas, origens, wbnb, quem, contraparte, quantia.negate());
					} else {
						mover(fluxo, saidas, valoresDasSaidas, origens, wbnb, contraparte, quem, quantia);
						ultimaEntradaNaConta.put(quem, indice);
					}
				}

				continue;
			}

			if (log.assinadoPor(DEPOSIT) || log.assinadoPor(WITHDRAWAL)) {
				tokens.add(emissor);
				if (log.assinadoPor(WITHDRAWAL) && log.topicos().size() == 2 && log.dados().length >= 32) {
					desembrulhos.add(new Object[] { HexFormat.of().formatHex(Eventos.enderecoDoTopico(log, 1)), emissor,
							Eventos.palavra(log, 0), indice });
				}
				continue;
			}

			if (!log.assinadoPor(Eventos.TRANSFER) || log.topicos().size() != 3 || log.dados().length < 32) {
				continue;
			}

			tokens.add(emissor);
			enderecos.putIfAbsent(emissor, log.endereco());

			String de = HexFormat.of().formatHex(Eventos.enderecoDoTopico(log, 1));
			String para = HexFormat.of().formatHex(Eventos.enderecoDoTopico(log, 2));
			BigInteger valor = Eventos.palavra(log, 0);

			if (de.equals(para) || valor.signum() == 0) {
				continue;
			}

			mover(fluxo, saidas, valoresDasSaidas, origens, emissor, de, para, valor);
			ultimaEntradaNaConta.put(para, indice);
		}

		// O desembrulho no meio do ciclo é gasto (saída para fora de qualquer pool).
		for (Object[] d : desembrulhos) {
			String conta = (String) d[0];
			String token = (String) d[1];
			// Quem pagou nativo a um gerente já tem esse BNB contado no movimento sintético: descontar o
			// desembrulho seria contar o mesmo dinheiro duas vezes.
			if (pagaramNativo.contains(conta)) {
				continue;
			}
			// Gasto se DEPOIS dele entrou qualquer coisa na conta: o nativo pagou um passo da rota. CICATRIZ de 27/09:
			// só contava se entrasse o MESMO token, e o roteador de agregador que desembrulhou 0,967 WBNB para pagar um
			// formador de mercado (RFQ) em nativo, e recebeu USDT de volta, aparecia com 0,967 WBNB "de lucro".
			if (ultimaEntradaNaConta.getOrDefault(conta, -1) > (int) d[3]) {
				fluxo.computeIfAbsent(conta, k -> new LinkedHashMap<>()).merge(token, ((BigInteger) d[2]).negate(),
						BigInteger::add);
			}
		}

		if (tokens.size() < 2) {
			return Optional.empty();
		}

		String executor = null;
		String ganho = null;

		for (Map.Entry<String, Map<String, BigInteger>> conta : fluxo.entrySet()) {

			String endereco = conta.getKey();

			// Pool ou cofre não é executor: o cofre do Infinity "ganhava" o USDT de um swap de usuário.
			if (endereco.equals(ZERO) || tokens.contains(endereco) || trocadores.contains(endereco)) {
				continue;
			}

			// O que esta conta mandou a POOLS, por token.
			Map<String, BigInteger> paraPools = new HashMap<>();
			for (int i = 0; i < saidas.size(); i++) {
				String[] saida = saidas.get(i);
				if (saida[0].equals(endereco) && trocadores.contains(saida[1]) && !saida[1].equals(endereco)) {
					paraPools.merge(saida[2], valoresDasSaidas.get(i), BigInteger::add);
				}
			}

			String positivo = positivoDeCiclo(conta.getValue(), paraPools);

			if (positivo != null) {
				Set<String> deQuem = origens.getOrDefault(endereco + positivo, Set.of());
				boolean dePool = deQuem.stream().allMatch(o -> trocadores.contains(o) && !o.equals(endereco));
				positivo = dePool ? positivo : null;
			}

			// Mais de um executor possível é ambíguo: não se inventa arbitragem.
			if (positivo != null) {
				if (executor != null) {
					return Optional.empty();
				}
				executor = endereco;
				ganho = positivo;
			}
		}

		if (executor == null) {
			return Optional.empty();
		}

		Set<String> pools = new LinkedHashSet<>();
		Map<String, byte[]> bytesDaPool = new HashMap<>();
		int v2 = 0;
		int v3 = 0;

		for (Log log : daTransacao) {

			String emissor = HexFormat.of().formatHex(log.endereco());

			if (tokens.contains(emissor) || emissor.equals(executor) || !trocadores.contains(emissor)) {
				continue;
			}

			pools.add(emissor);
			bytesDaPool.putIfAbsent(emissor, log.endereco());

			if (log.assinadoPor(Eventos.SWAP_V2)) {
				v2++;
			} else if (Eventos.ehSwapV3(log)) {
				v3++;
			}
		}

		if (pools.size() < 2) {
			return Optional.empty();
		}

		List<byte[]> ordem = new ArrayList<>();
		pools.forEach(p -> ordem.add(bytesDaPool.get(p)));

		return Optional.of(new Arbitragem(ordem, enderecos.get(ganho), fluxo.get(executor).get(ganho), v2, v3,
				HexFormat.of().parseHex(executor)));
	}

	private static void mover(Map<String, Map<String, BigInteger>> fluxo, List<String[]> saidas,
			List<BigInteger> valoresDasSaidas, Map<String, Set<String>> origens, String token, String de, String para,
			BigInteger valor) {

		fluxo.computeIfAbsent(de, k -> new LinkedHashMap<>()).merge(token, valor.negate(), BigInteger::add);
		saidas.add(new String[] { de, para, token });
		valoresDasSaidas.add(valor);
		fluxo.computeIfAbsent(para, k -> new LinkedHashMap<>()).merge(token, valor, BigInteger::add);
		origens.computeIfAbsent(para + token, k -> new HashSet<>()).add(de);
	}

	// O token de lucro, se a conta fecha como arbitragem: nenhum negativo, algum positivo, e todo positivo
	// SAIU desta conta PARA POOLS em quantia pelo menos igual ao ganho (ciclo, com principal), e é o ÚNICO
	// positivo.
	private static String positivoDeCiclo(Map<String, BigInteger> saldo, Map<String, BigInteger> saiu) {

		String primeiro = null;

		for (Map.Entry<String, BigInteger> token : saldo.entrySet()) {

			int sinal = token.getValue().signum();

			if (sinal < 0) {
				return null;
			}

			if (sinal > 0) {
				if (saiu.getOrDefault(token.getKey(), BigInteger.ZERO).compareTo(token.getValue()) < 0) {
					return null;
				}
				// Um ciclo termina com UM token a mais. Dois é cofre de liquidez se rebalanceando (tira
				// WBNB+X da pool e devolve menos dos dois) — CICATRIZ de 24/09: 1,608 BNB "de lucro" num
				// rebalanceamento sem swap nenhum.
				if (primeiro != null) {
					return null;
				}
				primeiro = token.getKey();
			}
		}

		return primeiro;
	}
}
