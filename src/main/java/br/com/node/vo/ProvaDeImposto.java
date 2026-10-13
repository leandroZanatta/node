package br.com.node.vo;

// Uma prova de imposto: o que a sonda mediu para (pool, token), em pontos-base. -1 é honeypot —
// transferência que reverte, pior que taxa, e nunca limpo.
//
// Mora em `vo` porque atravessa `catalogo` (que a produz, medindo) e `repository` (que a guarda),
// dois pacotes que não se conhecem. É a prova que grada uma perna de "a provar" para "provado": só
// entra no arbitrável quem tem imposto provado zero, taxa provada e não é honeypot — e, provado,
// fica salvo, para não revetar a cada restart.
//
// `compra` diz a DIREÇÃO: falso é a venda (transferir AO par — o que a sonda mede direto; -1 = honeypot);
// verdadeiro é a compra (o par enviando o token). Sem a direção o imposto só servia para barrar a perna; com
// ela, entra na conta.
public record ProvaDeImposto(byte[] pool, byte[] token, int bps, boolean compra) {
}
