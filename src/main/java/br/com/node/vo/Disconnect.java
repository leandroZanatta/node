package br.com.node.vo;

public record Disconnect(int motivo) implements MensagemP2p {

	@Override
	public int tipo() {
		return DISCONNECT;
	}

	// Conferido um a um contra `p2p/peer_error.go` do bnb-chain/bsc. A tabela
	// anterior estava errada de 0x03 em diante, e o erro cegava justamente para o
	// defeito que mais importa: o 0x0b é "ficamos calados tempo demais", e aparecia
	// como "peer inútil".
	public String descricao() {

		return switch (motivo) {
		case 0x00 -> "pedido do peer";
		case 0x01 -> "erro de rede";
		case 0x02 -> "quebra de protocolo";
		case 0x03 -> "peer inútil";
		case 0x04 -> "peers demais";
		case 0x05 -> "já conectado";
		case 0x06 -> "versão de p2p incompatível";
		case 0x07 -> "identidade inválida";
		case 0x08 -> "cliente encerrando";
		case 0x09 -> "identidade inesperada";
		case 0x0a -> "conectado a si mesmo";
		case 0x0b -> "TIMEOUT DE LEITURA (ficamos calados tempo demais)";
		case 0x10 -> "erro de subprotocolo";
		default -> "desconhecido (" + motivo + ")";
		};
	}
}
