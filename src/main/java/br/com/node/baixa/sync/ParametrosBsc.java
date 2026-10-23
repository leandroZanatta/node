package br.com.node.baixa.sync;

import java.util.HexFormat;

public final class ParametrosBsc {

	public static final long REDE = 56;

	public static final int PORTA = 30311;

	public static final byte[] GENESIS = HexFormat.of()
			.parseHex("0d21840abff46b96c84b2ac9e10e4f5cdaeb5693cb665db62a2f3b02d2d57b5b");

	public static final long[] FORKS_POR_BLOCO = { 5_184_000L, 13_082_000L, 18_907_621L, 21_962_149L, 22_107_423L,
			23_846_001L, 27_281_024L, 29_020_050L, 30_720_096L, 31_302_048L, 34_140_700L, };

	public static final long[] FORKS_POR_TEMPO = { 1_705_996_800L, 1_713_419_340L, 1_718_863_500L, 1_727_316_120L,
			1_727_317_200L, 1_742_436_600L, 1_745_903_100L, 1_751_250_600L, 1_768_357_800L, 1_777_343_400L,
			1_787_625_000L, };

	private ParametrosBsc() {
	}
}