package br.com.node.rlp;

import java.util.List;

public record RlpLista(List<RlpItem> itens) implements RlpItem {
}