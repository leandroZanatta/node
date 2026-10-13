package br.com.node.ensaio;

// Uma perna da rota, na forma que o contrato `Executor` espera.
//
// É a tradução do que decidimos para o que se executa, e cada campo existe porque
// a falta dele já custou dinheiro em algum lugar (herdado do old):
//  - `entra` e `sai` dão o SENTIDO. A pool não o guarda — ela tem `token0` e
//    `token1` — e trocá-los produz uma rota válida que anda para o lado errado;
//  - `taxaBps` vem por parâmetro porque presumir 30 para todo mundo foi o erro
//    que o próprio contrato documenta ter custado caro. Pancake cobra 25, Biswap
//    10, e a conta do lucro mínimo depende disso;
//  - `tipo` escolhe a matemática e a forma de pagar. A mesma rota com a curva
//    errada erra por ordens de grandeza, não por margem;
//  - `chave` só existe na pool de GERENTE (V4, Infinity CL): o `PoolKey` em ABI,
//    porque lá `pool` é o gerente de todas e a pool mesma é a chave.
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
		 * O contrato lê a taxa num uint16. Acima disso o valor daria a volta e
		 * viraria outro número — uma taxa pequena onde havia uma grande, que passa
		 * pela trava de lucro por baixo.
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

	// As pools de endereço, como sempre foram.
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
