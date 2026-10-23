package br.com.node.exception;

public class RlpException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RlpException(String message) {
		super(message);
	}

	public RlpException(String message, Throwable causa) {
		super(message, causa);
	}

}