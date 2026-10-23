package br.com.node.execucao.bundle;

import java.time.Duration;

public final class VereditorDeRelay {

	public static final Duration PENA_DE_PROIBICAO = Duration.ofDays(30);
	public static final Duration PENA_DE_ESTADO = Duration.ofHours(3);

	private VereditorDeRelay() {
	}

	public static String motivoDoVeredito(Object veredito) {
		String v = veredito == null ? "" : veredito.toString();
		int i = v.indexOf("execution reverted:");
		return i < 0 ? v : v.substring(i + "execution reverted:".length());
	}

	public static boolean transferenciaFalhou(String motivo) {
		String m = motivo == null ? "" : motivo.trim().toLowerCase(java.util.Locale.ROOT);
		return m.equals("tf") || m.endsWith(": tf") || m.equals("transfer_failed")
				|| m.equals("transferhelper: transfer failed") || m.equals("pancake: transfer_failed");
	}

	public static boolean proibicao(String motivo) {
		String m = motivo.toLowerCase(java.util.Locale.ROOT);
		return m.contains("forbidden") || m.contains("blacklisted") || m.contains("blacklist");
	}

	public static boolean overflow(String motivo) {
		return motivo.toLowerCase(java.util.Locale.ROOT).contains("overflow");
	}

	public static boolean blocoAindaNaoChegou(String corpo) {
		String c = corpo.toLowerCase(java.util.Locale.ROOT);
		return c.contains("block not found") || c.contains("header not found");
	}

	public static boolean estadoIndisponivel(String corpo) {
		String c = corpo.toLowerCase(java.util.Locale.ROOT);
		return c.contains("missing trie node") || c.contains("state is not available") || c.contains("state histories");
	}
}
