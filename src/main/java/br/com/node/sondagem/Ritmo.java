package br.com.node.sondagem;

// Quantas chamadas por segundo o parque público de RPC aguenta — medido, não configurado.
//
// CICATRIZ do old: um teto fixo (20/s) não sabe nada sobre o outro lado. Alto demais derruba os
// endpoints (a ~190/s, 13 de 14 nós devolveram 403 em duas horas); baixo demais deixa a fila parada. E
// o certo muda com a hora, o provedor e quem mais bate no mesmo IP.
//
// A forma é a da parede: encolhe rápido, cresce devagar, lembra onde bateu.
//   - uma recusa CORTA PELA METADE: recusa é dano feito, e meia medida prolonga o dano;
//   - um segundo inteiro limpo sobe UMA chamada: crescer por porcentagem acha a próxima parede num
//     salto, e o salto é o que derruba;
//   - na parede, só a ultrapassa depois de uma janela limpa longa que prove que ela cedeu.
//
// Sem relógio próprio: o instante entra por parâmetro, e é o que torna a regra conferível num teste em
// vez de numa tarde.
public final class Ritmo {

	private static final int MINIMO = 1;
	private static final int TETO = 50;

	// Quanto tempo limpo na parede antes de arriscar uma chamada a mais que ela.
	private static final long JANELA_ALEM_DA_PAREDE_MS = 30_000;

	private int porSegundo = 5;
	private int parede = TETO;
	private long ultimoCrescimentoMs;
	private long paredeDesdeMs;

	public synchronized long intervaloMs() {
		return 1000L / porSegundo;
	}

	// Uma recusa (429, 403, "rate limit"): corta pela metade e marca a parede no ritmo em que se bateu.
	public synchronized void recusou(long agoraMs) {
		parede = porSegundo;
		paredeDesdeMs = agoraMs;
		ultimoCrescimentoMs = agoraMs;
		porSegundo = Math.max(MINIMO, porSegundo / 2);
	}

	// Uma chamada limpa: no máximo uma subida por segundo. Abaixo da parede, sobe livre; na parede, só
	// sobe depois de uma janela longa sem recusa, e leva a parede junto.
	public synchronized void passou(long agoraMs) {

		if (agoraMs - ultimoCrescimentoMs < 1000) {
			return;
		}

		ultimoCrescimentoMs = agoraMs;

		if (porSegundo < parede) {
			porSegundo++;
		} else if (parede < TETO && agoraMs - paredeDesdeMs >= JANELA_ALEM_DA_PAREDE_MS) {
			parede++;
			porSegundo++;
			paredeDesdeMs = agoraMs;
		}
	}

	public synchronized int porSegundo() {
		return porSegundo;
	}
}
