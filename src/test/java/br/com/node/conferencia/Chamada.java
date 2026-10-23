package br.com.node.conferencia;
import br.com.node.baixa.sync.*;
import br.com.node.baixa.rpc.*;
import br.com.node.transformacao.catalogo.*;


public record Chamada(String para, String dados) {
}