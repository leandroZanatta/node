package br.com.node.vo;

// Uma mensagem que chegou e que ainda não sabemos ler.
//
// Existe porque o eth permite ignorar código não implementado, e tratá-lo como
// erro mata sessão saudável: o par começa a mandar mempool e anúncio de bloco no
// instante seguinte ao Status, e nada disso é opcional para ele. Lançar ali fazia
// a conversa durar segundos.
//
// E o contador destes códigos é o roteiro: ele diz qual mensagem a rede realmente
// usa, e portanto qual implementar em seguida.
public record Desconhecida(int codigo, byte[] corpo) implements MensagemRlpx {

	@Override
	public int tipo() {
		return codigo;
	}
}
