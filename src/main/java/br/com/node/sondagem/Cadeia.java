package br.com.node.sondagem;

import java.util.Optional;

public interface Cadeia {

	Optional<String> chamar(String para, String dados, String estadoJson, long altura);

	Optional<String> armazenamento(String contrato, String slot, long altura);

	long cabeca();
}