package br.com.node.sondagem;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import br.com.node.util.Keccak;

public final class Sonda {

	private static final String ENDERECO_DA_SONDA = "00000000000000000000000000000000000005a1";
	private static final String CODIGO = carregar();

	private static final byte[] MEDIR = seletor("medir(address,address,uint256)");
	private static final byte[] MEDIR_COMPRA = seletor("medirCompra(address,address,uint256,bool)");
	private static final byte[] SALDO_DE = seletor("balanceOf(address)");
	private static final byte[] TOKEN0 = seletor("token0()");
	private static final byte[] TOKEN1 = seletor("token1()");
	private static final byte[] RESERVAS = seletor("getReserves()");

	private static final int SLOTS = 20;

	private static final BigInteger FOLGA_DE_TAXA = BigInteger.valueOf(9_000);

	private final Cadeia cadeia;

	private final ConcurrentMap<String, Integer> slots = new ConcurrentHashMap<>();

	public Sonda(Cadeia cadeia) {
		this.cadeia = cadeia;
	}

	public Medida venda(byte[] token, byte[] destino, byte[] detentor, long altura) {

		Optional<Integer> slot = slotDe(token, detentor, altura);

		if (slot.isEmpty()) {
			return Medida.de(motivoDoSlot(token, detentor, altura));
		}

		BigInteger valor = BigInteger.TEN.pow(18);
		String estado = comSaldo(token, ENDERECO_DA_SONDA, slot.get(), valor.multiply(BigInteger.TWO));
		byte[] dados = juntar(MEDIR, palavra(token), palavra(destino), palavra(valor));

		return retido(cadeia.chamar(hex(ENDERECO_DA_SONDA), hex(dados), estado, altura), valor);
	}

	public Medida compra(byte[] pool, byte[] token, long altura) {

		Optional<byte[]> reservas = ler(pool, RESERVAS, altura);
		Optional<byte[]> zero = ler(pool, TOKEN0, altura);
		Optional<byte[]> um = ler(pool, TOKEN1, altura);

		if (reservas.isEmpty() || reservas.get().length < 64 || zero.isEmpty() || um.isEmpty()) {
			return Medida.de(Medida.Motivo.SEM_RESPOSTA);
		}

		byte[] token0 = fim20(zero.get());
		boolean saiEhToken0 = Arrays.equals(token, token0);
		byte[] entra = saiEhToken0 ? fim20(um.get()) : token0;

		BigInteger reserva0 = new BigInteger(1, Arrays.copyOfRange(reservas.get(), 0, 32));
		BigInteger reserva1 = new BigInteger(1, Arrays.copyOfRange(reservas.get(), 32, 64));
		BigInteger reservaSai = saiEhToken0 ? reserva0 : reserva1;
		BigInteger reservaEntra = saiEhToken0 ? reserva1 : reserva0;

		if (reservaSai.signum() <= 0 || reservaEntra.signum() <= 0) {
			return Medida.de(Medida.Motivo.SEM_RESPOSTA);
		}

		Optional<Integer> slot = slotDe(entra, pool, altura);

		if (slot.isEmpty()) {
			return Medida.de(motivoDoSlot(entra, pool, altura));
		}

		BigInteger entrada = reservaEntra.divide(BigInteger.valueOf(20_000));
		BigInteger comTaxa = entrada.multiply(FOLGA_DE_TAXA);
		BigInteger pedido = comTaxa.multiply(reservaSai)
				.divide(reservaEntra.multiply(BigInteger.valueOf(10_000)).add(comTaxa));

		if (entrada.signum() <= 0 || pedido.signum() <= 0) {
			return Medida.de(Medida.Motivo.SEM_RESPOSTA);
		}

		Optional<byte[]> saldo = ler(entra, juntar(SALDO_DE, palavra(pool)), altura);

		if (saldo.isEmpty() || saldo.get().length < 32) {
			return Medida.de(Medida.Motivo.SEM_RESPOSTA);
		}

		BigInteger saldoPar = new BigInteger(1, saldo.get());
		String estado = comSaldo(entra, HexFormat.of().formatHex(pool), slot.get(), saldoPar.add(entrada));
		byte[] dados = juntar(MEDIR_COMPRA, palavra(pool), palavra(token), palavra(pedido),
				palavra(BigInteger.valueOf(saiEhToken0 ? 1 : 0)));

		return retido(cadeia.chamar(hex(ENDERECO_DA_SONDA), hex(dados), estado, altura), pedido);
	}

	private Medida retido(Optional<String> retorno, BigInteger pedido) {

		if (retorno.isEmpty()) {
			return Medida.de(Medida.Motivo.SEM_RESPOSTA);
		}

		byte[] bytes = HexFormat.of().parseHex(retorno.get().substring(2));

		if (bytes.length < 64) {
			return Medida.de(Medida.Motivo.REVERTEU);
		}

		BigInteger chegou = new BigInteger(1, Arrays.copyOfRange(bytes, 32, 64));

		if (chegou.compareTo(pedido) > 0) {
			return Medida.de(Medida.Motivo.REVERTEU);
		}

		return new Medida(Medida.Motivo.MEDIDO,
				pedido.subtract(chegou).multiply(BigInteger.valueOf(10_000)).divide(pedido).intValueExact());
	}

	private Optional<Integer> slotDe(byte[] token, byte[] detentor, long altura) {

		Integer conhecido = slots.get(hex(token));

		if (conhecido != null) {
			return Optional.of(conhecido);
		}

		Optional<byte[]> saldo = ler(token, juntar(SALDO_DE, palavra(detentor)), altura);

		if (saldo.isEmpty()) {
			return Optional.empty();
		}

		BigInteger quanto = new BigInteger(1, saldo.get());

		if (quanto.signum() == 0) {
			return Optional.empty();
		}

		for (int slot = 0; slot < SLOTS; slot++) {

			Optional<String> guardado = cadeia.armazenamento(hex(token), chaveDoSaldo(detentor, slot), altura);

			if (guardado.isEmpty()) {
				return Optional.empty();
			}

			if (new BigInteger(guardado.get().substring(2), 16).equals(quanto)) {
				slots.put(hex(token), slot);
				return Optional.of(slot);
			}
		}

		return Optional.empty();
	}

	private Medida.Motivo motivoDoSlot(byte[] token, byte[] detentor, long altura) {

		Optional<byte[]> saldo = ler(token, juntar(SALDO_DE, palavra(detentor)), altura);

		if (saldo.isEmpty()) {
			return Medida.Motivo.SEM_RESPOSTA;
		}

		return new BigInteger(1, saldo.get()).signum() == 0 ? Medida.Motivo.SEM_DETENTOR
				: Medida.Motivo.SLOT_NAO_ACHADO;
	}

	private String comSaldo(byte[] token, String detentor, int slot, BigInteger valor) {
		return "{\"" + hex(ENDERECO_DA_SONDA) + "\":{\"code\":\"0x" + CODIGO + "\"},\"" + hex(token)
				+ "\":{\"stateDiff\":{\"" + chaveDoSaldo(HexFormat.of().parseHex(detentor), slot) + "\":\""
				+ palavra32(valor) + "\"}}}";
	}

	private static String chaveDoSaldo(byte[] detentor, int slot) {
		return "0x" + HexFormat.of().formatHex(Keccak.hash(palavra(detentor), palavra(BigInteger.valueOf(slot))));
	}

	private Optional<byte[]> ler(byte[] para, byte[] dados, long altura) {
		return cadeia.chamar(hex(para), hex(dados), "{}", altura).map(hex -> HexFormat.of().parseHex(hex.substring(2)));
	}

	private static byte[] juntar(byte[]... partes) {

		int total = 0;
		for (byte[] parte : partes) {
			total += parte.length;
		}

		byte[] junto = new byte[total];
		int pos = 0;
		for (byte[] parte : partes) {
			System.arraycopy(parte, 0, junto, pos, parte.length);
			pos += parte.length;
		}

		return junto;
	}

	private static byte[] seletor(String assinatura) {
		return Arrays.copyOf(Keccak.hash(assinatura.getBytes(StandardCharsets.US_ASCII)), 4);
	}

	private static byte[] palavra(byte[] bytes) {

		byte[] palavra = new byte[32];

		System.arraycopy(bytes, 0, palavra, 32 - bytes.length, bytes.length);

		return palavra;
	}

	private static byte[] palavra(BigInteger valor) {

		byte[] cru = valor.toByteArray();
		byte[] palavra = new byte[32];
		int copiar = Math.min(cru.length, 32);

		System.arraycopy(cru, cru.length - copiar, palavra, 32 - copiar, copiar);

		return palavra;
	}

	private static String palavra32(BigInteger valor) {
		return "0x" + HexFormat.of().formatHex(palavra(valor));
	}

	private static byte[] fim20(byte[] palavra) {
		return Arrays.copyOfRange(palavra, palavra.length - 20, palavra.length);
	}

	private static String hex(byte[] bytes) {
		return "0x" + HexFormat.of().formatHex(bytes);
	}

	private static String hex(String semPrefixo) {
		return "0x" + semPrefixo;
	}

	private static String carregar() {

		try (InputStream in = Sonda.class.getResourceAsStream("/sonda-runtime.hex")) {

			if (in == null) {
				throw new IllegalStateException("sonda-runtime.hex ausente nos resources");
			}

			String hex = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();

			return hex.startsWith("0x") ? hex.substring(2) : hex;

		} catch (IOException e) {
			throw new IllegalStateException("falha lendo sonda-runtime.hex: " + e.getMessage());
		}
	}
}