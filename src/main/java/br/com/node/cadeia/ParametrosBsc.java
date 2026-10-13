package br.com.node.cadeia;

import java.util.HexFormat;

public final class ParametrosBsc {

	public static final long REDE = 56;

	// O discv4 não carrega identidade de rede. A porta é o único indício antes de
	// gastar um handshake, e por isso é parâmetro DA REDE, não constante solta.
	public static final int PORTA = 30311;

	public static final byte[] GENESIS = HexFormat.of()
			.parseHex("0d21840abff46b96c84b2ac9e10e4f5cdaeb5693cb665db62a2f3b02d2d57b5b");

	// Forks por ALTURA, ordenados e sem repetir valor. Berlin, London e Hertz
	// compartilham a altura 31.302.048: contar três vezes muda o CRC e o par
	// desconecta sem dizer por quê.
	public static final long[] FORKS_POR_BLOCO = { 5_184_000L, // MirrorSync
			13_082_000L, // Bruno
			18_907_621L, // Euler
			21_962_149L, // Nano
			22_107_423L, // Moran
			23_846_001L, // Gibbs
			27_281_024L, // Planck
			29_020_050L, // Luban
			30_720_096L, // Plato
			31_302_048L, // Berlin + London + Hertz
			34_140_700L, // Hertzfix
	};

	// Da Shanghai em diante a BSC agenda por TIMESTAMP. A ordem importa: primeiro
	// todos os de bloco, depois todos os de tempo.
	public static final long[] FORKS_POR_TEMPO = { 1_705_996_800L, // Shanghai + Kepler
			1_713_419_340L, // Feynman + FeynmanFix
			1_718_863_500L, // Cancun + Haber
			1_727_316_120L, // HaberFix
			1_727_317_200L, // Bohr
			1_742_436_600L, // Pascal + Prague
			1_745_903_100L, // Lorentz
			1_751_250_600L, // Maxwell
			1_768_357_800L, // Fermi
			1_777_343_400L, // Osaka + Mendel
			1_787_625_000L, // Pasteur
	};

	private ParametrosBsc() {
	}
}
