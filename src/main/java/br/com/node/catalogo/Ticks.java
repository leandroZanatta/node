package br.com.node.catalogo;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import br.com.node.nucleo.MapaDeTicks;
import br.com.node.util.Keccak;

// Os MAPAS DE TICKS das pools de liquidez concentrada (V3 de endereço, Uniswap V4, PancakeSwap Infinity CL): a
// foto tirada do contrato e mantida viva pelos eventos de liquidez.
//
// A FOTO é uma leitura em lote (Multicall3 `tryBlockAndAggregate`), em duas voltas: as palavras do bitmap em
// volta do preço — com o NÚMERO DO BLOCO da leitura — e depois o `liquidityNet` de cada tick inicializado NO
// MESMO BLOCO. Três fontes, a mesma forma: a própria pool (V3: `tickBitmap`/`ticks`), o `StateView` da V4
// (`getTickBitmap`/`getTickLiquidity`) e o gerente do Infinity (`getPoolBitmapInfo`/`getPoolTickInfo`) — todas
// conferidas na cadeia em 26/09. A matemática que usa o mapa foi provada contra 6 swaps reais que cruzaram de 1
// a 62 ticks (`TravessiaDeTicksTest`): saída ao wei.
//
// VIVA pelos eventos: cada `Mint`/`Burn`/`ModifyLiquidity` depois do bloco da foto soma no `liquidityNet` das duas
// pontas. Os que chegam enquanto a foto está em voo ficam num anel e são reaplicados sobre ela ao instalar; a
// SEQUÊNCIA de cada evento impede aplicar o mesmo duas vezes (dois no mesmo bloco são dois eventos, não um).
//
// Fora do caminho do bloco: a foto roda numa thread própria, com fila curta que descarta. Sem mapa, a pool cota
// como sempre cotou — até o primeiro tick.
public final class Ticks {

	private static final Logger LOG = Logger.getLogger(Ticks.class.getName());

	// eth_call cru: (para, dados, bloco) → o resultado em hex, sem 0x. `bloco` nulo = "latest".
	@FunctionalInterface
	public interface Chamada {
		Optional<String> chamar(String para, String dados, String bloco);
	}

	private static final String MULTICALL3 = "ca11bde05977b3631167028862be2a173976ca11";
	private static final String STATE_VIEW_V4 = "d13dd3d6e93f276fafc9db9e6bb47c1180aee0c4";
	private static final byte[] TRY_BLOCK_AND_AGGREGATE = seletor("tryBlockAndAggregate(bool,(address,bytes)[])");

	private static final byte[] V3_BITMAP = seletor("tickBitmap(int16)");
	private static final byte[] V3_TICK = seletor("ticks(int24)");
	private static final byte[] V4_BITMAP = seletor("getTickBitmap(bytes32,int16)");
	private static final byte[] V4_TICK = seletor("getTickLiquidity(bytes32,int24)");
	private static final byte[] INF_BITMAP = seletor("getPoolBitmapInfo(bytes32,int16)");
	private static final byte[] INF_TICK = seletor("getPoolTickInfo(bytes32,int24)");

	// Palavras do bitmap de cada lado do preço: 2 → 5 palavras, ±512 ticks comprimidos (±5% de preço num
	// espaçamento 1, ±40% num de 10). É muito mais do que um ciclo de arbitragem desloca.
	private static final int PALAVRAS_DE_CADA_LADO = 2;
	private static final int TICKS_POR_LOTE = 250;
	private static final int ANEL = 8_192;

	private record Vivo(MapaDeTicks mapa, long alturaDaFoto, long sequencia) {
	}

	private record Delta(String pool, int inferior, int superior, BigInteger delta, long altura, long sequencia) {
	}

	private final Chamada rpc;
	private final Map<String, Vivo> vivos = new ConcurrentHashMap<>();
	private final Set<String> emVoo = ConcurrentHashMap.newKeySet();
	private final Deque<Delta> recentes = new ArrayDeque<>();
	private final AtomicLong sequencia = new AtomicLong();
	private final AtomicLong fotos = new AtomicLong();
	private final AtomicLong falhas = new AtomicLong();
	private final AtomicLong deltasAplicados = new AtomicLong();
	private final ThreadPoolExecutor fila = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(1_000), tarefa -> {
				Thread thread = new Thread(tarefa, "fotos-de-ticks");
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.DiscardPolicy());

	public Ticks(Chamada rpc) {
		this.rpc = rpc;
	}

	public Optional<MapaDeTicks> mapa(byte[] pool) {
		return Optional.ofNullable(vivos.get(hex(pool))).map(Vivo::mapa);
	}

	// Pede a foto se não há mapa, ou se o preço chegou a uma palavra da borda da faixa conhecida.
	// `gerente` nulo = V3 de endereço; senão, o gerente da pool (V4 ou Infinity CL).
	public void garantir(byte[] pool, int tickCorrente, int espacamento, byte[] gerente) {

		if (espacamento <= 0) {
			return;
		}

		String chave = hex(pool);
		Vivo vivo = vivos.get(chave);
		int margem = 256 * espacamento;

		if (vivo != null && tickCorrente - margem >= vivo.mapa().tickMinimo()
				&& tickCorrente + margem <= vivo.mapa().tickMaximo()) {
			return;
		}

		if (emVoo.add(chave)) {
			fila.execute(() -> {
				try {
					fotografar(pool, tickCorrente, espacamento, gerente);
				} catch (RuntimeException e) {
					falhas.incrementAndGet();
					LOG.fine(() -> "foto de ticks falhou em " + chave + ": " + e.getMessage());
				} finally {
					emVoo.remove(chave);
				}
			});
		}
	}

	// Thread do cursor: um evento de liquidez de [inferior, superior) com `delta` (com sinal).
	public void aoMudarLiquidez(byte[] pool, int inferior, int superior, BigInteger delta, long altura) {

		String chave = hex(pool);
		long seq = sequencia.incrementAndGet();

		synchronized (recentes) {
			recentes.addLast(new Delta(chave, inferior, superior, delta, altura, seq));
			while (recentes.size() > ANEL) {
				recentes.removeFirst();
			}
		}

		vivos.computeIfPresent(chave, (k, vivo) -> {
			if (altura <= vivo.alturaDaFoto() || seq <= vivo.sequencia()) {
				return vivo;
			}
			deltasAplicados.incrementAndGet();
			return new Vivo(vivo.mapa().com(inferior, superior, delta, altura), vivo.alturaDaFoto(), seq);
		});
	}

	private void fotografar(byte[] pool, int tickCorrente, int espacamento, byte[] gerente) {
		fotografar(pool, tickCorrente, espacamento, gerente, null);
	}

	// Síncrono, num bloco dado (nulo = o mais recente): para o teste conferir a foto contra a cadeia.
	Optional<MapaDeTicks> fotografarAgora(byte[] pool, int tickCorrente, int espacamento, byte[] gerente, String bloco) {
		fotografar(pool, tickCorrente, espacamento, gerente, bloco);
		return mapa(pool);
	}

	private void fotografar(byte[] pool, int tickCorrente, int espacamento, byte[] gerente, String blocoInicial) {

		boolean v4 = gerente != null && Arrays.equals(gerente, Eventos.GERENTE_V4);
		boolean infinity = gerente != null && Arrays.equals(gerente, Eventos.GERENTE_INFINITY_CL);
		String alvo = v4 ? STATE_VIEW_V4 : infinity ? hex(Eventos.GERENTE_INFINITY_CL) : hex(pool);

		int comprimido = Math.floorDiv(tickCorrente, espacamento);
		int palavra = comprimido >> 8;
		int primeira = palavra - PALAVRAS_DE_CADA_LADO;
		int ultima = palavra + PALAVRAS_DE_CADA_LADO;

		List<byte[]> pedidosDeBitmap = new ArrayList<>();
		for (int w = primeira; w <= ultima; w++) {
			pedidosDeBitmap.add(gerente == null ? concat(V3_BITMAP, palavraComSinal(w))
					: concat(v4 ? V4_BITMAP : INF_BITMAP, pool, palavraComSinal(w)));
		}

		Optional<Resposta> bitmaps = multicall(alvo, pedidosDeBitmap, blocoInicial);

		if (bitmaps.isEmpty() || bitmaps.get().dados().size() != pedidosDeBitmap.size()) {
			falhas.incrementAndGet();
			return;
		}

		long bloco = bitmaps.get().bloco();
		List<Integer> inicializados = new ArrayList<>();

		for (int i = 0; i < pedidosDeBitmap.size(); i++) {

			byte[] dado = bitmaps.get().dados().get(i);
			if (dado == null || dado.length < 32) {
				falhas.incrementAndGet();
				return;
			}

			BigInteger bits = new BigInteger(1, Arrays.copyOfRange(dado, 0, 32));
			for (int bit = bits.getLowestSetBit(); bit >= 0; bit = proximoBit(bits, bit)) {
				inicializados.add(((primeira + i) * 256 + bit) * espacamento);
			}
		}

		Map<Integer, BigInteger> liquidas = new HashMap<>();
		String noBloco = "0x" + Long.toHexString(bloco);

		for (int desde = 0; desde < inicializados.size(); desde += TICKS_POR_LOTE) {

			List<Integer> lote = inicializados.subList(desde, Math.min(inicializados.size(), desde + TICKS_POR_LOTE));
			List<byte[]> pedidos = new ArrayList<>();
			for (int t : lote) {
				pedidos.add(gerente == null ? concat(V3_TICK, palavraComSinal(t))
						: concat(v4 ? V4_TICK : INF_TICK, pool, palavraComSinal(t)));
			}

			Optional<Resposta> resposta = multicall(alvo, pedidos, noBloco);

			if (resposta.isEmpty() || resposta.get().dados().size() != pedidos.size()) {
				falhas.incrementAndGet();
				return;
			}

			for (int i = 0; i < lote.size(); i++) {
				byte[] dado = resposta.get().dados().get(i);
				if (dado == null || dado.length < 64) {
					falhas.incrementAndGet();
					return;
				}
				// Nos três contratos o `liquidityNet` é a SEGUNDA palavra da resposta (int128 com sinal).
				liquidas.put(lote.get(i), new BigInteger(Arrays.copyOfRange(dado, 32, 64)));
			}
		}

		int tickMinimo = primeira * 256 * espacamento;
		int tickMaximo = ((ultima + 1) * 256 - 1) * espacamento;
		MapaDeTicks foto = MapaDeTicks.de(liquidas, tickMinimo, tickMaximo, bloco);
		String chave = hex(pool);

		// Instala e reaplica, no mesmo passo atômico da chave, os eventos posteriores à foto que chegaram
		// enquanto ela estava em voo.
		vivos.compute(chave, (k, velho) -> {
			Vivo vivo = new Vivo(foto, bloco, 0);
			List<Delta> depois = new ArrayList<>();
			synchronized (recentes) {
				for (Delta d : recentes) {
					if (d.pool().equals(chave) && d.altura() > bloco) {
						depois.add(d);
					}
				}
			}
			for (Delta d : depois) {
				vivo = new Vivo(vivo.mapa().com(d.inferior(), d.superior(), d.delta(), d.altura()), bloco, d.sequencia());
			}
			return vivo;
		});

		fotos.incrementAndGet();
	}

	private record Resposta(long bloco, List<byte[]> dados) {
	}

	// `tryBlockAndAggregate(false, calls)`: (bloco, hash, [(sucesso, dados)]). Chamada que falha vem com
	// dados nulos — quem lê recusa a foto inteira.
	private Optional<Resposta> multicall(String alvo, List<byte[]> dados, String bloco) {

		byte[] calldata = codificarMulticall(alvo, dados);
		Optional<String> resultado = rpc.chamar(MULTICALL3, HexFormat.of().formatHex(calldata), bloco);

		return resultado.flatMap(r -> {
			try {
				return Optional.of(decodificarMulticall(HexFormat.of().parseHex(r.startsWith("0x") ? r.substring(2) : r)));
			} catch (RuntimeException e) {
				br.com.node.util.Falhas.anotar("resposta do multicall de ticks", e);
				return Optional.empty();
			}
		});
	}

	static byte[] codificarMulticall(String alvo, List<byte[]> dados) {

		byte[] endereco = HexFormat.of().parseHex(alvo);
		int n = dados.size();
		ByteArrayOutputStream elementos = new ByteArrayOutputStream();
		int[] offsets = new int[n];

		for (int i = 0; i < n; i++) {
			offsets[i] = n * 32 + elementos.size();
			byte[] d = dados.get(i);
			elementos.writeBytes(palavra(endereco));
			elementos.writeBytes(palavra(BigInteger.valueOf(0x40)));
			elementos.writeBytes(palavra(BigInteger.valueOf(d.length)));
			elementos.writeBytes(Arrays.copyOf(d, (d.length + 31) / 32 * 32));
		}

		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		saida.writeBytes(TRY_BLOCK_AND_AGGREGATE);
		saida.writeBytes(palavra(BigInteger.ZERO));
		saida.writeBytes(palavra(BigInteger.valueOf(0x40)));
		saida.writeBytes(palavra(BigInteger.valueOf(n)));
		for (int offset : offsets) {
			saida.writeBytes(palavra(BigInteger.valueOf(offset)));
		}
		saida.writeBytes(elementos.toByteArray());

		return saida.toByteArray();
	}

	static Resposta decodificarMulticall(byte[] r) {

		long bloco = inteiro(r, 0).longValueExact();
		int inicioDoArranjo = inteiro(r, 64).intValueExact();
		int n = inteiro(r, inicioDoArranjo).intValueExact();
		int base = inicioDoArranjo + 32;
		List<byte[]> dados = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			int elemento = base + inteiro(r, base + i * 32).intValueExact();
			boolean sucesso = inteiro(r, elemento).signum() != 0;
			int inicioDosBytes = elemento + inteiro(r, elemento + 32).intValueExact();
			int tamanho = inteiro(r, inicioDosBytes).intValueExact();
			dados.add(sucesso ? Arrays.copyOfRange(r, inicioDosBytes + 32, inicioDosBytes + 32 + tamanho) : null);
		}

		return new Resposta(bloco, dados);
	}

	public Map<String, Object> resumo() {
		Map<String, Object> resumo = new LinkedHashMap<>();
		resumo.put("pools_com_mapa", vivos.size());
		resumo.put("fotos", fotos.get());
		resumo.put("falhas", falhas.get());
		resumo.put("deltas_aplicados", deltasAplicados.get());
		resumo.put("na_fila", fila.getQueue().size());
		return resumo;
	}

	private static int proximoBit(BigInteger bits, int atual) {
		BigInteger resto = bits.shiftRight(atual + 1);
		return resto.signum() == 0 ? -1 : atual + 1 + resto.getLowestSetBit();
	}

	private static BigInteger inteiro(byte[] r, int desde) {
		return new BigInteger(1, Arrays.copyOfRange(r, desde, desde + 32));
	}

	private static byte[] palavra(BigInteger valor) {
		byte[] cru = valor.toByteArray();
		byte[] p = new byte[32];
		int n = Math.min(cru.length, 32);
		System.arraycopy(cru, cru.length - n, p, 32 - n, n);
		return p;
	}

	private static byte[] palavra(byte[] endereco) {
		byte[] p = new byte[32];
		System.arraycopy(endereco, 0, p, 32 - endereco.length, endereco.length);
		return p;
	}

	// Inteiro com sinal numa palavra: extensão de sinal com 0xff para negativo (int16 e int24 no ABI).
	private static byte[] palavraComSinal(int valor) {
		byte[] p = new byte[32];
		if (valor < 0) {
			Arrays.fill(p, (byte) 0xff);
		}
		byte[] cru = BigInteger.valueOf(valor).toByteArray();
		System.arraycopy(cru, 0, p, 32 - cru.length, cru.length);
		return p;
	}

	private static byte[] concat(byte[]... partes) {
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		for (byte[] parte : partes) {
			saida.writeBytes(parte.length == 32 || parte.length == 4 ? parte : palavra(parte));
		}
		return saida.toByteArray();
	}

	private static byte[] seletor(String assinatura) {
		return Arrays.copyOf(Keccak.hash(assinatura.getBytes(StandardCharsets.US_ASCII)), 4);
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}
}
