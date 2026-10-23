package br.com.node.transformacao.catalogo;

import java.util.OptionalInt;

public record Origem(Fabrica fabrica, OptionalInt taxa, OptionalInt espacamento) {
}