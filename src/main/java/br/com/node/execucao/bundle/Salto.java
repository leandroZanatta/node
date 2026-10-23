package br.com.node.execucao.bundle;

public record Salto(String pool, String entra, String sai, int tipo, int taxaBps, byte[] chave) {

	/** Os tipos como o contrato os numera. */
	public static final int TIPO_V2 = 0;
	public static final int TIPO_V3 = 1;
	public static final int TIPO_V4 = 2;
	public static final int TIPO_INFINITY_CL = 3;

	public Salto {

		if (pool == null || entra == null || sai == null) {
			throw new IllegalArgumentException("travessia sem endereço");
		}

		if (entra.equalsIgnoreCase(sai)) {
			throw new IllegalArgumentException("travessia de um token para ele mesmo: " + entra);
		}

		/*
		 * O contrato lê a taxa num uint16. Acima disso o valor daria a volta e viraria
		 * outro número — uma taxa pequena onde havia uma grande, que passa pela trava
		 * de lucro por baixo.
		 */
		if (taxaBps < 0 || taxaBps > 65_535) {
			throw new IllegalArgumentException("taxa fora do uint16: " + taxaBps);
		}

		if (tipo < TIPO_V2 || tipo > TIPO_INFINITY_CL) {
			throw new IllegalArgumentException("tipo que o contrato não conhece: " + tipo);
		}

		chave = chave == null ? new byte[0] : chave.clone();

		if ((tipo >= TIPO_V4) != (chave.length > 0)) {
			throw new IllegalArgumentException("chave só em pool de gerente, e pool de gerente só com chave");
		}
	}

	public Salto(String pool, String entra, String sai, boolean v3, int taxaBps) {
		this(pool, entra, sai, v3 ? TIPO_V3 : TIPO_V2, taxaBps, new byte[0]);
	}

	public boolean v3() {
		return tipo == TIPO_V3;
	}

	public boolean deGerente() {
		return tipo >= TIPO_V4;
	}
}