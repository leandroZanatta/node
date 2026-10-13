package br.com.node.catalogo;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import br.com.node.util.Keccak;
import br.com.node.vo.Log;

// Reconhece os logs que mudam o estado de uma pool, e lê os campos deles.
//
// As assinaturas são CALCULADAS do texto, nunca copiadas em hexadecimal: constante de
// hash errada não dá erro, dá evento que nunca casa — e o sintoma é uma pool que
// simplesmente nunca atualiza.
//
// `Mint` e `Burn` estão aqui porque foram o que faltou. No V2 eles emitem `Sync`
// junto e o Sync basta; no V3 não existe Sync, e ignorá-los faz a liquidez ativa
// derivar entre dois swaps — que é ler aporte de liquidez como movimento de preço.
public final class Eventos {

	public static final byte[] SYNC_V2 = assinatura("Sync(uint112,uint112)");
	public static final byte[] SWAP_V2 = assinatura("Swap(address,uint256,uint256,uint256,uint256,address)");
	public static final byte[] SWAP_V3 = assinatura("Swap(address,address,int256,int256,uint160,uint128,int24)");

	// O mesmo swap na PancakeSwap V3, com duas palavras de taxa de protocolo no fim — e portanto
	// OUTRO topic0. Assinatura diferente é evento diferente: escutando só a forma da Uniswap, o
	// node ficava cego para a maior V3 da BSC sem erro nenhum aparecer. O anterior já sabia disso
	// (`EventoDex.SWAP_V3_PANCAKE`), e a migração trouxe uma assinatura só.
	public static final byte[] SWAP_V3_PANCAKE = assinatura(
			"Swap(address,address,int256,int256,uint160,uint128,int24,uint128,uint128)");
	public static final byte[] MINT_V3 = assinatura("Mint(address,address,int24,int24,uint128,uint256,uint256)");
	public static final byte[] BURN_V3 = assinatura("Burn(address,int24,int24,uint128,uint256,uint256)");
	public static final byte[] TRANSFER = assinatura("Transfer(address,address,uint256)");

	// UNISWAP V4 E PANCAKESWAP INFINITY CL: um contrato só — o GERENTE — guarda todas as pools, e cada
	// uma é um `poolId` (tópico 1), não um endereço. Medido em 25/09 no servidor: 88% das arbitragens
	// alheias passavam por pool fora do catálogo, e os dois gerentes eram os contratos mais citados
	// (493 e 64 em 1.033). Identidade conferida na cadeia: o da V4 responde `protocolFeeController()`,
	// o do Infinity responde `vault()` = 0x238a…e6c4.
	public static final byte[] GERENTE_V4 = java.util.HexFormat.of()
			.parseHex("28e2ea090877bf75740558f6bfb36a5ffee9e9df");
	public static final byte[] GERENTE_INFINITY_CL = java.util.HexFormat.of()
			.parseHex("a0ffb9c1ce1fe56963b0321b32e7a0302114058b");

	// Os montantes vêm do ponto de vista de QUEM CHAMA — o contrário do V3: o que entra na pool é
	// NEGATIVO. E o `fee` é a taxa TOTAL cobrada no swap, em milionésimos (no Infinity já inclui a de
	// protocolo, que vem de novo na palavra 6). Medido em 25/09 contra 1.235 pares de swaps
	// consecutivos reais, sem cruzar tick: entrada negativa em 1.235 e taxa implícita pelo preço
	// igual à do evento em 1.235.
	public static final byte[] SWAP_V4 = assinatura("Swap(bytes32,address,int128,int128,uint160,uint128,int24,uint24)");
	public static final byte[] SWAP_INFINITY_CL = assinatura(
			"Swap(bytes32,address,int128,int128,uint160,uint128,int24,uint24,uint16)");

	// A MESMA assinatura nos dois gerentes, e com os ticks no CORPO (palavras 0 e 1), não nos tópicos
	// como o Mint/Burn do V3. A liquidez vem com sinal na palavra 2: positiva põe, negativa tira.
	public static final byte[] MODIFICAR_LIQUIDEZ = assinatura("ModifyLiquidity(bytes32,address,int24,int24,int256,bytes32)");

	// O nascimento da pool, com o `PoolKey`. Formas DIFERENTES nos dois gerentes (a ordem de fee e
	// hooks troca, e o Infinity empacota espaçamento e permissões em `parameters`): quem lê é `ChaveDaPool`.
	public static final byte[] INICIALIZAR_V4 = assinatura(
			"Initialize(bytes32,address,address,uint24,int24,address,uint160,int24)");
	public static final byte[] INICIALIZAR_INFINITY_CL = assinatura(
			"Initialize(bytes32,address,address,address,uint24,bytes32,uint160,int24)");

	private Eventos() {
	}

	public static boolean ehInicializacaoDeGerente(Log log) {
		return Arrays.equals(log.endereco(), GERENTE_V4) && log.assinadoPor(INICIALIZAR_V4)
				|| Arrays.equals(log.endereco(), GERENTE_INFINITY_CL) && log.assinadoPor(INICIALIZAR_INFINITY_CL);
	}

	// Log é dado NÃO-AUTENTICADO — qualquer contrato emite o topic0 que quiser. Então o evento só conta
	// vindo do gerente certo, e o `Swap` de um gerente não vale se tiver a forma do outro.
	public static boolean ehSwapDeGerente(Log log) {
		return log.topicos().size() >= 2
				&& (Arrays.equals(log.endereco(), GERENTE_V4) && log.assinadoPor(SWAP_V4)
						|| Arrays.equals(log.endereco(), GERENTE_INFINITY_CL) && log.assinadoPor(SWAP_INFINITY_CL));
	}

	public static boolean ehLiquidezDeGerente(Log log) {
		return log.topicos().size() >= 2 && log.assinadoPor(MODIFICAR_LIQUIDEZ)
				&& (Arrays.equals(log.endereco(), GERENTE_V4) || Arrays.equals(log.endereco(), GERENTE_INFINITY_CL));
	}

	// O endereço vem alinhado à direita numa palavra de 32 bytes.
	public static byte[] enderecoDoTopico(Log log, int indice) {

		byte[] topico = log.topicos().get(indice);

		return topico.length < 20 ? new byte[0] : Arrays.copyOfRange(topico, topico.length - 20, topico.length);
	}

	// Palavra com sinal em complemento de dois, sem estreitar para long: os montantes do
	// `Swap` do V3 são int256 e cabem em BigInteger, não em long.
	public static BigInteger palavraComSinalGrande(Log log, int indice) {

		int inicio = indice * 32;
		byte[] dados = log.dados();

		if (dados.length < inicio + 32) {
			return BigInteger.ZERO;
		}

		return new BigInteger(Arrays.copyOfRange(dados, inicio, inicio + 32));
	}

	public static BigInteger palavra(Log log, int indice) {

		int inicio = indice * 32;
		byte[] dados = log.dados();

		if (dados.length < inicio + 32) {
			return BigInteger.ZERO;
		}

		return new BigInteger(1, Arrays.copyOfRange(dados, inicio, inicio + 32));
	}

	// Inteiro com sinal: o ABI encaixa int24 numa palavra de 32 bytes com extensão de
	// sinal, e ler isso sem sinal transforma tick negativo — metade dos casos — num
	// número gigante.
	public static long palavraComSinal(Log log, int indice) {

		int inicio = indice * 32;
		byte[] dados = log.dados();

		if (dados.length < inicio + 32) {
			return 0;
		}

		return new BigInteger(Arrays.copyOfRange(dados, inicio, inicio + 32)).longValueExact();
	}

	// A liquidez que um Mint põe ou um Burn tira, já com sinal.
	//
	// As posições são DIFERENTES nos dois eventos, e é aqui que isso mora para ninguém mais
	// precisar saber: o `Mint` carrega o `sender` NÃO indexado na frente, então o `amount` é a
	// palavra 1; no `Burn` o dono é indexado e o `amount` é a palavra 0.
	//
	// Custou o maior falso positivo do projeto. O cursor lia a palavra 0 dos dois, então todo
	// Mint somava um ENDEREÇO de 160 bits à liquidez — 1,41e48 numa pool USDT/WBNB, que é
	// `0xf714…7d16` em hexadecimal — e isso saía como oportunidade de 1,6 bilhão de
	// pontos-base. O projeto anterior já tinha fechado esta ferida em `EventoDex.liquidezV3`, e
	// a migração a reabriu.
	public static BigInteger liquidezMovida(Log log) {
		return log.assinadoPor(MINT_V3) ? palavra(log, 1) : palavra(log, 0).negate();
	}

	// As cinco primeiras palavras dos dados são idênticas nas duas formas, então quem lê preço,
	// liquidez e tick lê igual — só quem RECONHECE o evento precisa saber que são duas.
	public static boolean ehSwapV3(Log log) {
		return log.assinadoPor(SWAP_V3) || log.assinadoPor(SWAP_V3_PANCAKE);
	}

	// O tick vem indexado no Mint e no Burn, então sai do tópico, não do corpo.
	public static long tickDoTopico(Log log, int indice) {

		byte[] topico = log.topicos().get(indice);

		return new BigInteger(topico).longValueExact();
	}

	static byte[] assinatura(String texto) {
		return Keccak.hash(texto.getBytes(StandardCharsets.US_ASCII));
	}
}
