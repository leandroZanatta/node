package br.com.node.ensaio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import br.com.node.catalogo.Achado;
import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Tarifario;
import br.com.node.nucleo.Direcao;
import br.com.node.nucleo.Par;
import br.com.node.nucleo.Pool;
import br.com.node.nucleo.PoolV3;

public final class RotaDoModelo {

	private RotaDoModelo() {
	}

	public record Chamada(List<Salto> rota, BigInteger montante, BigInteger lucroMinimo) {
	}

	public static Optional<Chamada> traduzir(Achado achado, Catalogo catalogo, Tarifario tarifario,
			BigInteger pisoEmWei) {

		if (achado.ciclo().pernas().size() != achado.travessias().size() || achado.travessias().isEmpty()) {
			return Optional.empty();
		}

		List<Salto> rota = new ArrayList<>();

		for (int i = 0; i < achado.travessias().size(); i++) {

			byte[] pool = achado.ciclo().pernas().get(i).pool();
			br.com.node.nucleo.Travessia travessia = achado.travessias().get(i);

			Optional<Salto> perna = traduzir(pool, travessia, catalogo, tarifario);

			if (perna.isEmpty()) {
				return Optional.empty();
			}

			rota.add(perna.get());
		}

		BigInteger montante = achado.oportunidade().entrada();

		if (montante.signum() <= 0) {
			return Optional.empty();
		}

		BigInteger lucroMinimo = pisoEmWei.max(BigInteger.ZERO);

		return Optional.of(new Chamada(List.copyOf(rota), montante, lucroMinimo));
	}

	private static Optional<Salto> traduzir(byte[] pool, br.com.node.nucleo.Travessia travessia, Catalogo catalogo,
			Tarifario tarifario) {

		if (Catalogo.deGerente(pool)) {
			return traduzirDoGerente(pool, travessia, catalogo);
		}

		Optional<Par> par = catalogo.par(pool);
		Optional<Integer> taxa = tarifario.taxa(pool);

		if (par.isEmpty() || taxa.isEmpty()) {
			return Optional.empty();
		}

		if (par.get().token0().length != 20 || par.get().token1().length != 20) {
			return Optional.empty();
		}

		byte[] entra = travessia.direcao() == Direcao.ZERO_PARA_UM ? par.get().token0() : par.get().token1();
		byte[] sai = travessia.direcao() == Direcao.ZERO_PARA_UM ? par.get().token1() : par.get().token0();

		int taxaBps = taxa.get();

		if (!(travessia.pool() instanceof PoolV3)) {
			if (taxaBps % Pool.PONTO_BASE != 0) {
				return Optional.empty();
			}
			taxaBps = taxaBps / Pool.PONTO_BASE;
		}

		return Optional.of(new Salto(hex(pool), hex(entra), hex(sai), travessia.pool() instanceof PoolV3, taxaBps));
	}

	private static Optional<Salto> traduzirDoGerente(byte[] pool, br.com.node.nucleo.Travessia travessia,
			Catalogo catalogo) {

		Optional<br.com.node.catalogo.ChaveDaPool> chave = catalogo.chaveDaPool(pool)
				.filter(br.com.node.catalogo.ChaveDaPool::modelavel).filter(br.com.node.catalogo.ChaveDaPool::confere);
		Optional<Catalogo.Lados> lados = catalogo.lados(pool);

		if (chave.isEmpty() || lados.isEmpty() || !(travessia.pool() instanceof PoolV3)) {
			return Optional.empty();
		}

		boolean v4 = java.util.Arrays.equals(chave.get().gerente(), br.com.node.catalogo.Eventos.GERENTE_V4);
		byte[] entra = travessia.direcao() == Direcao.ZERO_PARA_UM ? lados.get().token0() : lados.get().token1();
		byte[] sai = travessia.direcao() == Direcao.ZERO_PARA_UM ? lados.get().token1() : lados.get().token0();

		return Optional.of(new Salto(hex(chave.get().gerente()), hex(entra), hex(sai),
				v4 ? Salto.TIPO_V4 : Salto.TIPO_INFINITY_CL, 0, chave.get().abi()));
	}

	private static String hex(byte[] endereco) {
		return HexFormat.of().formatHex(endereco);
	}
}