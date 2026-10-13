package br.com.node.vo;

// Um bloco como o cursor precisa dele: o cabeçalho, que prova a continuidade, e os
// recibos, de onde sai tudo o que muda uma pool. Sem corpo — o corpo só serviria para
// ler calldata, e para bloco fechado o log diz o que ACONTECEU em vez do que foi
// pedido.
public record Bloco(Cabecalho cabecalho, byte[] recibos) {
}
