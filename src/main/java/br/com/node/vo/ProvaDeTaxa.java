package br.com.node.vo;

// Mora em `vo` porque atravessa dois pacotes que não se conhecem: `catalogo` a produz e
// `repository` a guarda. Em `catalogo`, o repositório teria de depender do domínio.
//
// Uma evidência de taxa: quantas trocas de um chamador fecharam exatamente nesta taxa, nesta
// pool.
//
// É a unidade que se guarda, e não a taxa decidida. A decisão muda quando chega evidência nova
// — 2500 provado por vinte chamadores vence 3000 provado por um —, então gravar a decisão
// congelaria a regra no banco e ela passaria a morar em dois lugares. Gravando a evidência, a
// regra mora só em `Taxas`, e reaplicá-la ao semear dá a mesma decisão que se tinha.
public record ProvaDeTaxa(byte[] pool, int taxa, String chamador, long fechamentos) {
}
