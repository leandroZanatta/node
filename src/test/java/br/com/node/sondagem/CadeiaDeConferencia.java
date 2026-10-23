package br.com.node.sondagem;
import br.com.node.baixa.rpc.*;
import br.com.node.transformacao.sondagem.*;


import java.util.Optional;

import br.com.node.conferencia.RpcDeConferencia;

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