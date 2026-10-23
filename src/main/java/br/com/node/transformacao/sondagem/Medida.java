package br.com.node.transformacao.sondagem;

public record Medida(Motivo motivo, int bps) {

	public enum Motivo {

		MEDIDO,

		SEM_DETENTOR,

		SLOT_NAO_ACHADO,

		SEM_RESPOSTA,

		REVERTEU;

		public boolean definitivo() {
			return this == REVERTEU;
		}
	}

	public static Medida de(Motivo motivo) {
		return new Medida(motivo, 0);
	}

	public boolean mediu() {
		return motivo == Motivo.MEDIDO;
	}
}