package br.com.node.sincronizacao;

// Uma faixa de blocos a buscar. É a unidade de trabalho da frente.
//
// Existe porque o par corta a resposta em ~2 MB e não em contagem: pedir 32 e receber
// 20 é normal, e os 12 que faltaram voltam para a fila como outra faixa. Sem esse tipo
// o "o que faltou" não tem onde ficar, e o jeito de não ter onde é fingir que veio.
public record Faixa(long deAltura, int quantos) {
}
