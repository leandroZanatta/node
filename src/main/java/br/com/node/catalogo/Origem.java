package br.com.node.catalogo;

import java.util.OptionalInt;

// De que factory uma pool vem, provado pelo endereço, e o que essa factory fixa nela.
//
// Em V3 a taxa entra no sal do CREATE2, então achar a pool com uma das taxas da tabela é provar a
// taxa E o espaçamento de uma vez. Em V2 o sal só tem os tokens: a identidade prova a factory, e a
// taxa só vem junto quando a factory a fixa no bytecode do par.
public record Origem(Fabrica fabrica, OptionalInt taxa, OptionalInt espacamento) {
}
