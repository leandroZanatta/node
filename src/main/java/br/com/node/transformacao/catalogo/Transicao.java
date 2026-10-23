package br.com.node.transformacao.catalogo;

import java.util.Optional;

import br.com.node.transformacao.nucleo.ReservasV2;
import br.com.node.vo.Log;

public record Transicao(Log log, long altura, int transacao, Optional<ReservasV2> antes) {
}