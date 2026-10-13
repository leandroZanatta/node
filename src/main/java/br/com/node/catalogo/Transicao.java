package br.com.node.catalogo;

import java.util.Optional;

import br.com.node.nucleo.ReservasV2;
import br.com.node.vo.Log;

// Um log aplicado, com a altura em que vale e o estado que ele substituiu.
//
// Carrega o ANTERIOR porque quem prova taxa precisa da transição, não do estado — e
// obrigá-lo a manter cópia própria faria nascer um segundo dono do mesmo estado, que
// é como o projeto anterior acabou com três alturas diferentes.
//
// E carrega a TRANSAÇÃO porque a assinatura de uma arbitragem só fecha dentro dela: é
// ali que o fluxo líquido por token tem de dar zero em tudo menos no lucro.
public record Transicao(Log log, long altura, int transacao, Optional<ReservasV2> antes) {
}
