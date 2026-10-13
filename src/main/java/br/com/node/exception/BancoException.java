package br.com.node.exception;

public class BancoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public BancoException(String mensagem) {
		super(mensagem);
	}
}
