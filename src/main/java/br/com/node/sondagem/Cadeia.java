package br.com.node.sondagem;

import java.util.Optional;

// O acesso de LEITURA à cadeia de que a sondagem precisa: chamar um contrato num estado (forjado ou
// não), ler um slot cru, e saber a cabeça.
//
// É interface por um motivo concreto, não por antecipação: tem dois implementadores reais — o `Rpc`
// de produção e o adaptador de teste sobre o RPC da conferência. Isso faz a validação da sonda contra
// a cicatriz do BlackSwan exercitar EXATAMENTE o código que roda em produção, em vez de uma cópia.
//
// Só leitura, e por `eth_call`: nada aqui envia transação nem altera a cadeia. E o resultado alimenta o
// enriquecimento, nunca a decisão no instante — a premissa de não depender de RPC para decidir (P2)
// continua de pé, porque quem decide lê o que a sondagem guardou, não esta interface.
public interface Cadeia {

	// `eth_call` com estado forjado (o JSON de override; "{}" para nenhum), numa altura. Vazio quando
	// nenhum nó respondeu — que é diferente de reverter, e a sonda separa os dois.
	Optional<String> chamar(String para, String dados, String estadoJson, long altura);

	// `eth_getStorageAt`: um slot cru, para descobrir onde o token guarda os saldos.
	Optional<String> armazenamento(String contrato, String slot, long altura);

	// A cabeça da rede, para medir perto dela — a janela de estado dos nós públicos é curta.
	long cabeca();
}
