package br.com.node.catalogo;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import br.com.node.nucleo.Par;
import br.com.node.util.Keccak;

// Diz de qual factory conhecida uma pool é, recalculando o endereço dela.
//
// POR QUE ISTO EXISTE: a conferência contra o contrato achou duas pools V2 com taxa provada 3000
// cobrando 2000 e 2500 — uma PancakeSwap V1 e uma V2. O K só limita a taxa por cima, e um robô que
// calcula com a fórmula de 0,3% "prova" 3000 em qualquer pool mais barata. E achou uma V3 com
// espaçamento 2000 onde eu derivava 200: o mapa taxa→espaçamento é de cada factory, não universal.
// Nenhuma das duas se resolve observando trocas.
//
// O QUE RESOLVE: o endereço de uma pool é CREATE2(deployer, sal, hash do bytecode). Recalculá-lo e
// bater prova de onde a pool veio, e com isso o bytecode — que fixa a fórmula do K, a taxa do V2 de
// taxa fixa, e em V3 a própria taxa, que entra no sal. É prova, não suposição (P8): a taxa "suposta
// pela factory" que gravou 5 bps numa pool de 10% era um palpite sobre a pool; isto é a pool dizendo
// de que contrato é.
//
// Cada entrada foi provada contra a cadeia antes de entrar, e a procedência está ao lado. Factory
// que não se prova não entra — BakerySwap não expõe o hash e não teve par para conferir.
public final class Fabricas {

	private static final List<Fabrica> CONHECIDAS = List.of(

			// hash por INIT_CODE_PAIR_HASH(); CREATE2 bateu em 3 de 3 getPair reais; taxa 2500 reproduz o
			// roteador em 3 de 3 pares, e 476 de 477 pools na conferência completa
			v2("PancakeSwap V2", "ca143ce32fe78f1f7019d7d551a6402fc5350c73",
					"00fb7f630766e6a796048ea87d01acd3068e8ff67d078148a3fa3f4a84f69bd5", OptionalInt.of(2500)),

			// hash por INIT_CODE_PAIR_HASH(); CREATE2 3 de 3; taxa 2000 reproduz o roteador em 3 de 3
			v2("PancakeSwap V1", "bcfccbde45ce874adcb698cc183debcf17952812",
					"d0d4c4cd0848c93cb4fd1f498d7013ee6bfb25783ea21593d5834f5d250ece66", OptionalInt.of(2000)),

			// hash por INIT_CODE_PAIR_HASH(); CREATE2 3 de 3; taxa 2000 reproduz o roteador em 3 de 3
			v2("ApeSwap", "0841bd0b734e4f5853f0dd8d7ea041c241fb0da6",
					"f4ccce374816856d11f00e4069e7cada164065686fbef53c6167a63ec2fd8c5b", OptionalInt.of(2000)),

			// hash por pairCodeHash(); CREATE2 3 de 3; taxa 3000 reproduz o roteador em 3 de 3
			v2("SushiSwap", "c35dadb65012ec5796536bd9864ed8773abc74c4",
					"e18a34eb0e04b04f7a0ac29a6e80748dca96319b42c54d679cb821dca90c6303", OptionalInt.of(3000)),

			// hash por INIT_CODE_HASH(); CREATE2 3 de 3. Taxa POR PAR: swapFee() 1 e 2 medidos (1000 e 2000)
			v2("Biswap", "858e3312ed3a876947ea49d572a7c42de08af7ee",
					"fea293c909d87cd4153593f077b76bb7e94340200f4ee84211ae8e4f9bd7ffdf", OptionalInt.empty()),

			// hash por initCodeHash(); CREATE2 3 de 3. Taxa POR PAR: getPairFees 20 e 30 medidos (2000 e 3000)
			v2("MDEX", "3cd1c46068daea5ebb0d3f55f6915b10648062b8",
					"0d994d996174b05cfc7bed897dc1b20b4c458fc8d64fe98bc78b3c64a6b4d093", OptionalInt.empty()),

			// deployer por poolDeployer() da factory 0x0bfbcf9f…; CREATE2 bateu em 4 de 4 getPool reais; tabela
			// por feeAmountTickSpacing (3000 desabilitado), e o tickSpacing de cada pool confere
			v3("PancakeSwap V3", "0bfbcf9fa4f9c56b0f40a671ad40e0805a091865", "41ff9aa7e16b8b1a8a8dc4f0efacd93d02d071c9",
					"6ce8eb472fa82df5469c6ab6d485f17c3ad13c8cd7af59b3d4a8026c5ce0f7e2",
					Map.of(100, 1, 500, 10, 2500, 50, 10000, 200)),

			// a factory é o deployer; CREATE2 bateu em 20 de 20 getPool reais (5 pares × 4 taxas); tabela por
			// feeAmountTickSpacing (2500 desabilitado, 3000 → 60)
			v3("Uniswap V3", "db1d10011ad0ff90774d0c6bb92e5c5c8b4461f7", "db1d10011ad0ff90774d0c6bb92e5c5c8b4461f7",
					"e34f199b19b2b4f47f68442619d555527d244f78a3297ea89325f843f87b8b54",
					Map.of(100, 1, 500, 10, 3000, 60, 10000, 200)),

			// pairCodeHash() = 0x8d3d214c…; CREATE2 bateu em WBNB/USDT volatile e stable (10/10/26);
			// getFee(bool=false)=20 bps → 2000 milionésimos; sal = keccak(token0++token1++0x00)
			v2Solidly("ThenaFi Volatile", "afd89d21bdb66d00817d4153e055830b1c2b3970",
					"8d3d214c094a9889564f695c3e9fa516dd3b50bc3258207acd7f8b8e6b94fb65",
					OptionalInt.of(2000), (byte) 1),

			// getFee(bool=true)=1 bps → 100 milionésimos; sal = keccak(token0++token1++0x01)
			// curva exata: x³y+xy³=k (Newton na PoolSolidlyStable), não xy=k do V2
			v2Solidly("ThenaFi Stable", "afd89d21bdb66d00817d4153e055830b1c2b3970",
					"8d3d214c094a9889564f695c3e9fa516dd3b50bc3258207acd7f8b8e6b94fb65",
					OptionalInt.of(100), (byte) 2));

	private static final byte PREFIXO_CREATE2 = (byte) 0xff;

	// A identidade de uma pool não muda, e recalculá-la custa até oito keccak por pool: guarda-se a
	// resposta, inclusive a de "não é de factory conhecida".
	private final Map<String, Optional<Origem>> porPool = new ConcurrentHashMap<>();

	// O endereço do par V2 que a factory dada cria para estes dois tokens — CREATE2 determinístico. É
	// como se sabe EM QUAL pool um roteador troca: o roteador da Pancake V2 só troca nos pares da
	// factory dela. Vazio se a factory não é uma das conhecidas (sem o hash do código não há conta).
	public static Optional<byte[]> parV2(byte[] factory, byte[] tokenA, byte[] tokenB) {

		for (Fabrica fabrica : CONHECIDAS) {

			if (!fabrica.v3() && Arrays.equals(fabrica.factory(), factory)) {
				boolean aPrimeiro = java.util.HexFormat.of().formatHex(tokenA)
						.compareTo(java.util.HexFormat.of().formatHex(tokenB)) < 0;
				byte[] sal = aPrimeiro ? Keccak.hash(tokenA, tokenB) : Keccak.hash(tokenB, tokenA);
				return Optional.of(create2(fabrica, sal));
			}
		}

		return Optional.empty();
	}

	// Par de um roteador Solidly (ThenaFi e forks). O flag `stable` determina o byte no sal e
	// qual entrada da CONHECIDAS usar (solidlyType 1=volatile, 2=stable).
	public static Optional<byte[]> parSolidly(byte[] factory, byte[] tokenA, byte[] tokenB, boolean stable) {

		byte alvo = stable ? (byte) 2 : (byte) 1;

		for (Fabrica fabrica : CONHECIDAS) {

			if (!fabrica.v3() && fabrica.solidlyType() == alvo && Arrays.equals(fabrica.factory(), factory)) {
				boolean aPrimeiro = java.util.HexFormat.of().formatHex(tokenA)
						.compareTo(java.util.HexFormat.of().formatHex(tokenB)) < 0;
				byte[] tok0 = aPrimeiro ? tokenA : tokenB;
				byte[] tok1 = aPrimeiro ? tokenB : tokenA;
				byte[] sal = Keccak.hash(tok0, tok1, new byte[] { stable ? (byte) 1 : (byte) 0 });
				return Optional.of(create2(fabrica, sal));
			}
		}

		return Optional.empty();
	}

	public Optional<Origem> de(byte[] pool, Par par) {
		return porPool.computeIfAbsent(HexFormat.of().formatHex(pool), chave -> identificar(pool, par));
	}

	public long quantasIdentificadas() {
		return porPool.values().stream().filter(Optional::isPresent).count();
	}

	private static Optional<Origem> identificar(byte[] pool, Par par) {

		for (Fabrica fabrica : CONHECIDAS) {

			Optional<Origem> origem = fabrica.v3() ? comoV3(fabrica, pool, par) : comoV2(fabrica, pool, par);

			if (origem.isPresent()) {
				return origem;
			}
		}

		return Optional.empty();
	}

	// Sal do par V2: keccak dos dois endereços crus, ordenados.
	// Solidly inclui o flag `stable` no sal: keccak(token0 ++ token1 ++ stable_byte).
	private static Optional<Origem> comoV2(Fabrica fabrica, byte[] pool, Par par) {

		byte[] sal;
		if (fabrica.solidlyType() == 1) {
			sal = Keccak.hash(par.token0(), par.token1(), new byte[] { 0 });
		} else if (fabrica.solidlyType() == 2) {
			sal = Keccak.hash(par.token0(), par.token1(), new byte[] { 1 });
		} else {
			sal = Keccak.hash(par.token0(), par.token1());
		}

		return Arrays.equals(create2(fabrica, sal), pool)
				? Optional.of(new Origem(fabrica, fabrica.taxaFixa(), OptionalInt.empty()))
				: Optional.empty();
	}

	// Sal da pool V3: keccak de abi.encode(token0, token1, taxa). A taxa está DENTRO do endereço — por
	// isso testar cada taxa da tabela e bater é provar a taxa, e não inferi-la de uma troca.
	private static Optional<Origem> comoV3(Fabrica fabrica, byte[] pool, Par par) {

		for (Map.Entry<Integer, Integer> degrau : fabrica.espacamentoPorTaxa().entrySet()) {

			byte[] sal = Keccak.hash(palavra(par.token0()), palavra(par.token1()), palavra(degrau.getKey()));

			if (Arrays.equals(create2(fabrica, sal), pool)) {
				return Optional.of(new Origem(fabrica, OptionalInt.of(degrau.getKey()), OptionalInt.of(degrau.getValue())));
			}
		}

		return Optional.empty();
	}

	private static byte[] create2(Fabrica fabrica, byte[] sal) {

		byte[] hash = Keccak.hash(new byte[] { PREFIXO_CREATE2 }, fabrica.deployer(), sal, fabrica.hashDoCodigo());

		return Arrays.copyOfRange(hash, 12, 32);
	}

	private static byte[] palavra(byte[] endereco) {

		byte[] palavra = new byte[32];

		System.arraycopy(endereco, 0, palavra, 32 - endereco.length, endereco.length);

		return palavra;
	}

	private static byte[] palavra(int valor) {

		byte[] palavra = new byte[32];

		for (int i = 0; i < 4; i++) {
			palavra[31 - i] = (byte) (valor >>> (8 * i));
		}

		return palavra;
	}

	// Nos V2 a factory é quem executa o CREATE2.
	private static Fabrica v2(String nome, String factory, String hashDoCodigo, OptionalInt taxaFixa) {
		return new Fabrica(nome, false, HexFormat.of().parseHex(factory), HexFormat.of().parseHex(factory),
				HexFormat.of().parseHex(hashDoCodigo), Map.of(), taxaFixa, (byte) 0);
	}

	// Solidly: salt = keccak(token0 ++ token1 ++ stable_byte); solidlyType 1=volatile, 2=stable.
	private static Fabrica v2Solidly(String nome, String factory, String hashDoCodigo, OptionalInt taxaFixa,
			byte solidlyType) {
		return new Fabrica(nome, false, HexFormat.of().parseHex(factory), HexFormat.of().parseHex(factory),
				HexFormat.of().parseHex(hashDoCodigo), Map.of(), taxaFixa, solidlyType);
	}

	private static Fabrica v3(String nome, String factory, String deployer, String hashDoCodigo,
			Map<Integer, Integer> espacamentos) {
		return new Fabrica(nome, true, HexFormat.of().parseHex(factory), HexFormat.of().parseHex(deployer),
				HexFormat.of().parseHex(hashDoCodigo), espacamentos, OptionalInt.empty(), (byte) 0);
	}
}
