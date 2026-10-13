package br.com.node.sondagem;

import java.util.Optional;

import br.com.node.conferencia.RpcDeConferencia;

// Liga a `Sonda` de PRODUÇÃO ao RPC de conferência, para o teste exercitar o código que roda de
// verdade — não uma cópia. É o segundo implementador da `Cadeia` (o `Rpc` é o primeiro), e é o que
// justifica `Cadeia` ser interface.
//
// O `Rpc` de produção tem ritmo adaptativo e não serve para um teste que quer resposta rápida e
// determinística; o `RpcDeConferencia` chama direto. A `Sonda` não vê diferença: é o mesmo contrato.
public final class CadeiaDeConferencia implements Cadeia {

	private final RpcDeConferencia rpc = new RpcDeConferencia();

	@Override
	public Optional<String> chamar(String para, String dados, String estadoJson, long altura) {
		return rpc.chamarComEstado(para, dados, estadoJson, altura);
	}

	@Override
	public Optional<String> armazenamento(String contrato, String slot, long altura) {
		return rpc.armazenamento(contrato, slot, altura);
	}

	@Override
	public long cabeca() {
		return rpc.cabeca();
	}
}
