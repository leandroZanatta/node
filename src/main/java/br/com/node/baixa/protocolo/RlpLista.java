package br.com.node.baixa.protocolo;

import java.util.List;

public record RlpLista(List<RlpItem> itens) implements RlpItem {
}