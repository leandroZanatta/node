package br.com.node.vo;

public sealed interface MensagemRlpx permits MensagemP2p, MensagemEth, Desconhecida {

	int tipo();
}