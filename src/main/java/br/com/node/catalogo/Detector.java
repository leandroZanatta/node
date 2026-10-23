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

public final class Detector {

	private static final byte[] DEPOSIT = Eventos.assinatura("Deposit(address,uint256)");
	private static final byte[] WITHDRAWAL = Eventos.assinatura("Withdrawal(address,uint256)");
	private static final String ZERO = "00".repeat(20);

	private final java.util.function.Function<byte[], java.util.OptionalInt> ladoNativo;

	private static final String COFRE_INFINITY = "238a358808379702088667322f80ac48bad5e6c4";
	private static final byte[] WBNB = HexFormat.of().parseHex("bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");

	public Detector() {
		this(poolId -> java.util.OptionalInt.empty());
	}

	public Detector(java.util.function.Function<byte[], java.util.OptionalInt> ladoNativo) {
		this.ladoNativo = ladoNativo;
	}

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

		Map<String, Integer> ultimaEntradaNaConta = new HashMap<>();
		List<Object[]> desembrulhos = new ArrayList<>();
		Set<String> pagaramNativo = new HashSet<>();

		Set<String> trocadores = new HashSet<>();
		int indice = -1;

		for (Log log : daTransacao) {

			indice++;
			String emissor = HexFormat.of().formatHex(log.endereco());

			if (log.assinadoPor(Eventos.SWAP_V2) || log.assinadoPor(Eventos.SYNC_V2) || Eventos.ehSwapV3(log)
					|| log.assinadoPor(Eventos.SWAP_V4) || log.assinadoPor(Eventos.SWAP_INFINITY_CL)) {
				trocadores.add(emissor);
				if (java.util.Arrays.equals(log.endereco(), Eventos.GERENTE_INFINITY_CL)) {
					trocadores.add(COFRE_INFINITY);
				}
			}

			if (Eventos.ehSwapDeGerente(log) && log.topicos().size() >= 3) {

				java.util.OptionalInt lado = ladoNativo.apply(log.topicos().get(1));
				BigInteger quantia = lado.isPresent() ? Eventos.palavraComSinalGrande(log, lado.getAsInt())
						: BigInteger.ZERO;

				if (quantia.signum() != 0) {

					String wbnb = HexFormat.of().formatHex(WBNB);
					String quem = HexFormat.of().formatHex(Eventos.enderecoDoTopico(log, 2));

					String contraparte = java.util.Arrays.equals(log.endereco(), Eventos.GERENTE_INFINITY_CL)
							? COFRE_INFINITY
							: emissor;

					tokens.add(wbnb);
					enderecos.putIfAbsent(wbnb, WBNB);
					pagaramNativo.add(quem);

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

		for (Object[] d : desembrulhos) {
			String conta = (String) d[0];
			String token = (String) d[1];

			if (pagaramNativo.contains(conta)) {
				continue;
			}

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

			if (endereco.equals(ZERO) || tokens.contains(endereco) || trocadores.contains(endereco)) {
				continue;
			}

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

				if (primeiro != null) {
					return null;
				}
				primeiro = token.getKey();
			}
		}

		return primeiro;
	}
}