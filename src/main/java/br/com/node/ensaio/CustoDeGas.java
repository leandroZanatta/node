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

public final class CustoDeGas {

	private static final Logger LOG = Logger.getLogger(CustoDeGas.class.getName());

	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
	private static final byte[] USDT = HexFormat.of().parseHex("55d398326f99059ff775485246999027b3197955");
	private static final byte[] BUSD = HexFormat.of().parseHex("e9e7cea3dedca5984780bafc599bd69add087d56");
	private static final byte[] USDC = HexFormat.of().parseHex("8ac76a51cc950d9822d68b83fe1ad97b32cd580d");
	private static final byte[] U = HexFormat.of().parseHex("ce24439f2d9c6a2289f741120fe202248b666666");

	private static final byte[] POOL_WBNB_U = HexFormat.of().parseHex("108752b2a22c731ede3edac2205c63ae553e221a");

	private static final byte[] POOL_WBNB_USDT = HexFormat.of().parseHex("172fcd41e0913e95784454622d1c3724f546f849");

	private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);
	private static final BigInteger Q192 = BigInteger.ONE.shiftLeft(192);

	private static final BigInteger TAXA_FLASH_BPS = BigInteger
			.valueOf(java.util.Optional.ofNullable(System.getenv("TAXA_FLASH_BPS")).map(String::trim)
					.filter(v -> v.matches("\\d{1,5}")).map(Long::parseLong).orElse(0L));

	private volatile BigInteger usdtPorBnb;

	private volatile BigInteger uPorBnb;

	private static final long GAS_BYTE_ZERO = 4L;
	private static final long GAS_BYTE_NAO_ZERO = 16L;

	private static final long GAS_BASE_TX = 21_000L;

	private static final long GAS_EXECUTOR_FIXO = 60_000L;

	private static final long GAS_EMPRESTIMO = 60_000L;

	private static final long GAS_PERNA_V2 = 75_000L;
	private static final long GAS_PERNA_V3 = 120_000L;

	private static final long GAS_PERNA_GERENTE = 150_000L;

	private static final long GAS_CALLDATA_POR_PERNA_GERENTE = 13L * 32L * 16L;

	private static final long GAS_CALLDATA_POR_PERNA = 160L * GAS_BYTE_NAO_ZERO;

	private static final BigInteger MARGEM_DEN = BigInteger.valueOf(100);
	private static final BigInteger MARGEM_NUM = BigInteger
			.valueOf(java.util.Optional.ofNullable(System.getenv("PISO_MARGEM")).map(String::trim).flatMap(texto -> {

				long centesimos = Math.round(Double.parseDouble(texto.replace(',', '.')) * 100);
				if (centesimos < 100) {
					throw new IllegalStateException("PISO_MARGEM abaixo de 1,0: " + texto);
				}
				return java.util.Optional.of(centesimos);
			}).orElse(100L));
	public static final long GAS_TX_PAGAMENTO_BUILDER = 21_000L;
	private static final BigInteger GAS_PAGAMENTO_BUILDER = BigInteger.valueOf(GAS_TX_PAGAMENTO_BUILDER);

	private final RpcDoEnsaio rpc;
	private final Catalogo catalogo;

	private volatile BigInteger mercado;

	private volatile BigInteger gorjeta = BigInteger.ZERO;

	public CustoDeGas(RpcDoEnsaio rpc, Catalogo catalogo) {
		this.rpc = rpc;
		this.catalogo = catalogo;
	}

	public void atualizar() {

		try {
			comMercadoMedido(rpc.precoDeGas());
		} catch (RuntimeException e) {

			br.com.node.util.Falhas.anotar("preço do gás (eth_gasPrice)", e);
		}

		atualizarCambio();
	}

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
		BigInteger uR = wbnbEhToken0 ? reservas.get().reserva1() : reservas.get().reserva0();

		if (wbnbR.signum() <= 0) {
			return Optional.empty();
		}

		return Optional.of(uR.divide(wbnbR));
	}

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

		if (reservas.isPresent() && reservas.get().reserva0().signum() > 0 && reservas.get().reserva1().signum() > 0) {

			BigInteger usdt = usdtEhToken0 ? reservas.get().reserva0() : reservas.get().reserva1();
			BigInteger wbnb = usdtEhToken0 ? reservas.get().reserva1() : reservas.get().reserva0();

			return Optional.of(usdt.divide(wbnb));
		}

		Optional<EstadoV3> estado = catalogo.estado(POOL_WBNB_USDT);

		return estado.isEmpty() ? Optional.empty() : cambioDoV3(estado.get().sqrtPrecoX96(), usdtEhToken0);
	}

	public static Optional<BigInteger> cambioDoV3(BigInteger sqrtPrecoX96, boolean usdtEhToken0) {

		if (sqrtPrecoX96 == null || sqrtPrecoX96.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger quadrado = sqrtPrecoX96.multiply(sqrtPrecoX96);

		if (quadrado.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger usdtPorBnb = usdtEhToken0 ? Q192.divide(quadrado) : quadrado.divide(Q192);

		return usdtPorBnb.signum() > 0 ? Optional.of(usdtPorBnb) : Optional.empty();
	}

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

	public Optional<Double> bnbWeiParaUsd(BigInteger bnbWei) {
		BigInteger cambio = usdtPorBnb;
		if (cambio == null || cambio.signum() <= 0 || bnbWei == null || bnbWei.signum() <= 0) {
			return Optional.empty();
		}

		return Optional.of(new java.math.BigDecimal(bnbWei).multiply(new java.math.BigDecimal(cambio))
				.divide(java.math.BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.DOWN).doubleValue());
	}

	public java.util.Optional<BigInteger> cambioUsdtPorBnb() {
		return java.util.Optional.ofNullable(usdtPorBnb);
	}

	public BigInteger maxTaxa() {
		return precoEfetivo().multiply(BigInteger.TWO);
	}

	public BigInteger prioridade() {
		return precoEfetivo();
	}

	public void gorjeta(BigInteger gorjeta) {
		this.gorjeta = gorjeta == null || gorjeta.signum() < 0 ? BigInteger.ZERO : gorjeta;
	}

	public BigInteger estimarGasDasTravessias(List<Travessia> travessias) {
		return estimarGasDasTravessias(travessias, List.of());
	}

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

	public BigInteger estimarGas(List<Salto> rota) {

		if (rota == null || rota.isEmpty()) {
			return BigInteger.valueOf(GAS_BASE_TX);
		}

		int v3 = contarV3(rota);
		int gerente = contarGerente(rota);
		return estrutural(rota.size() - v3 - gerente, v3, gerente);
	}

	public BigInteger estimarGas(List<Salto> rota, byte[] calldata, boolean semLoan) {

		if (rota == null || rota.isEmpty()) {
			return BigInteger.valueOf(GAS_BASE_TX);
		}

		if (calldata == null || calldata.length == 0) {
			return estimarGas(rota);
		}

		int v3 = contarV3(rota);
		int gerente = contarGerente(rota);
		long gasEmprestimo = semLoan ? 0L : GAS_EMPRESTIMO;

		return BigInteger.valueOf(GAS_BASE_TX + GAS_EXECUTOR_FIXO + gasEmprestimo)
				.add(BigInteger.valueOf(GAS_PERNA_V2).multiply(BigInteger.valueOf(rota.size() - v3 - gerente)))
				.add(BigInteger.valueOf(GAS_PERNA_V3).multiply(BigInteger.valueOf(v3)))
				.add(BigInteger.valueOf(GAS_PERNA_GERENTE).multiply(BigInteger.valueOf(gerente)))
				.add(gasDaCalldata(calldata));
	}

	public BigInteger estimarGas(List<Salto> rota, byte[] calldata) {
		return estimarGas(rota, calldata, false);
	}

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

	private static BigInteger estrutural(int pernasV2, int pernasV3, int pernasGerente) {

		return BigInteger.valueOf(GAS_BASE_TX + GAS_EXECUTOR_FIXO + GAS_EMPRESTIMO)
				.add(BigInteger.valueOf(GAS_PERNA_V2 + GAS_CALLDATA_POR_PERNA).multiply(BigInteger.valueOf(pernasV2)))
				.add(BigInteger.valueOf(GAS_PERNA_V3 + GAS_CALLDATA_POR_PERNA).multiply(BigInteger.valueOf(pernasV3)))
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

	public BigInteger custoDoGas(byte[] tokenRaiz, BigInteger gasEstimado) {
		return converterDoBnb(tokenRaiz, precoEfetivo().multiply(gasCalibrado(gasEstimado)));
	}

	public BigInteger custoDaTransferenciaBuilder(byte[] tokenRaiz) {
		return converterDoBnb(tokenRaiz, precoEfetivo().multiply(GAS_PAGAMENTO_BUILDER));
	}

	private volatile int fatorDeGasMilesimos = 1000;

	public void fatorDeGas(int milesimos) {
		this.fatorDeGasMilesimos = Math.max(500, Math.min(1500, milesimos));
	}

	public int fatorDeGasMilesimos() {
		return fatorDeGasMilesimos;
	}

	public int margemDoPisoCentimos() {
		return MARGEM_NUM.intValueExact();
	}

	private BigInteger gasCalibrado(BigInteger gasEstimado) {
		return gasEstimado.multiply(BigInteger.valueOf(fatorDeGasMilesimos)).divide(BigInteger.valueOf(1000));
	}

	public BigInteger taxaDoEmprestimo(BigInteger montante) {
		return montante == null || montante.signum() <= 0 ? BigInteger.ZERO
				: montante.multiply(TAXA_FLASH_BPS).divide(BigInteger.valueOf(10_000));
	}

	public Optional<BigInteger> piso(byte[] tokenRaiz, BigInteger montante, BigInteger gasEstimado) {

		if (tokenRaiz == null || gasEstimado == null || gasEstimado.signum() <= 0) {
			return Optional.empty();
		}

		if (!Arrays.equals(tokenRaiz, WBNB) && !Arrays.equals(tokenRaiz, USDT) && !Arrays.equals(tokenRaiz, U)
				&& !Arrays.equals(tokenRaiz, BUSD) && !Arrays.equals(tokenRaiz, USDC)) {
			return Optional.empty();
		}

		BigInteger gasDaArbitragem = gasCalibrado(gasEstimado);
		BigInteger custoGasBnb = precoEfetivo().multiply(gasDaArbitragem.multiply(MARGEM_NUM).divide(MARGEM_DEN));
		BigInteger custoGasRaiz = converterDoBnb(tokenRaiz, custoGasBnb);

		return Optional.of(custoGasRaiz.add(taxaDoEmprestimo(montante)).add(BigInteger.ONE));
	}

	private BigInteger converterDoBnb(byte[] tokenRaiz, BigInteger emBnb) {
		if (Arrays.equals(tokenRaiz, WBNB))
			return emBnb;
		if (Arrays.equals(tokenRaiz, U))
			return emU(emBnb);
		return emUsdt(emBnb);
	}

	public Optional<BigInteger> deBnb(byte[] tokenRaiz, BigInteger bnb) {

		if (Arrays.equals(tokenRaiz, WBNB))
			return Optional.of(bnb);
		if (Arrays.equals(tokenRaiz, USDT) || Arrays.equals(tokenRaiz, BUSD) || Arrays.equals(tokenRaiz, USDC)) {
			return Optional.of(emUsdt(bnb));
		}
		if (Arrays.equals(tokenRaiz, U))
			return Optional.of(emU(bnb));
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

		BigInteger umBnbEmUsdt = emUsdt(BigInteger.TEN.pow(18));

		return umBnbEmUsdt.signum() > 0 ? Optional.of(quantia.multiply(BigInteger.TEN.pow(18)).divide(umBnbEmUsdt))
				: Optional.empty();
	}

	public Optional<BigInteger> paraBnbSeDisponivel(byte[] tokenRaiz, BigInteger quantia) {

		if (tokenRaiz == null || quantia == null) {
			return Optional.empty();
		}

		if (Arrays.equals(tokenRaiz, WBNB)) {
			return Optional.of(quantia);
		}

		Optional<BigInteger> umBnbEmRaiz;
		if (Arrays.equals(tokenRaiz, U)) {
			umBnbEmRaiz = cambioUDisponivel();
		} else if (Arrays.equals(tokenRaiz, USDT) || Arrays.equals(tokenRaiz, BUSD) || Arrays.equals(tokenRaiz, USDC)) {
			umBnbEmRaiz = cambioUsdtDisponivel();
		} else {
			return Optional.empty();
		}

		return umBnbEmRaiz.filter(cambio -> cambio.signum() > 0)
				.map(cambio -> quantia.multiply(BigInteger.TEN.pow(18)).divide(cambio));
	}

	private Optional<BigInteger> cambioUsdtDisponivel() {
		Optional<BigInteger> medido = cambioDaCanonica();
		if (medido.isPresent()) {
			usdtPorBnb = medido.get();
			return medido;
		}
		return Optional.ofNullable(usdtPorBnb);
	}

	private Optional<BigInteger> cambioUDisponivel() {
		Optional<BigInteger> medido = cambioDaPoolU();
		if (medido.isPresent()) {
			uPorBnb = medido.get();
			return medido;
		}
		return Optional.ofNullable(uPorBnb);
	}

	private BigInteger emUsdt(BigInteger valorBnb) {

		Optional<BigInteger> cambio = cambioUsdtDisponivel();
		if (cambio.isEmpty()) {
			throw new IllegalStateException("câmbio BNB/USDT nunca medido: a canônica ainda não está no catálogo");
		}
		return valorBnb.multiply(cambio.get());
	}

	private BigInteger emU(BigInteger valorBnb) {

		Optional<BigInteger> cambio = cambioUDisponivel();
		if (cambio.isEmpty()) {
			throw new IllegalStateException("câmbio BNB/U nunca medido: pool WBNB/U ainda não está no catálogo");
		}
		return valorBnb.multiply(cambio.get());
	}

}