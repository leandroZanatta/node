package br.com.node.rede;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.exception.PeerException;
import br.com.node.vo.Capacidade;
import br.com.node.vo.Disconnect;
import br.com.node.vo.Hello;
import br.com.node.vo.MensagemRlpx;

// O "defeito do Snappy" que derrubava aberturas, com as cargas que a rede mandou.
//
// Medido na lotação: "Snappy produziu 0 bytes, anunciou 449", "anunciou 705" e "anunciou 1473".
// Os três números são o varint que o descompressor lê de um Disconnect EM CLARO — o par recusa
// logo depois do aperto e escreve o motivo antes de ligar a compressão do lado dele:
//
//   01 c1 03   →  varint 449   →  Disconnect(peer inútil)
//   01 c1 05   →  varint 705   →  Disconnect(já conectado)
//   01 c1 0b   →  varint 1473  →  Disconnect(timeout de leitura)
//
// O teste prova as duas metades: que ESSES bytes produzem exatamente as mensagens de erro que
// foram medidas, e que agora eles viram o motivo da queda em vez de apagá-lo.
class DisconnectEmClaroTest {

	private static final Map<Integer, Integer> ANUNCIADO_POR_MOTIVO = Map.of(0x03, 449, 0x05, 705, 0x0b, 1473);

	@Test
	@DisplayName("Disconnect em claro depois dos dois Hello vira o motivo, não erro de Snappy")
	void disconnectEmClaroViraOMotivo() {

		ANUNCIADO_POR_MOTIVO.forEach((motivo, anunciado) -> {

			byte[] corpoEmClaro = { (byte) 0xc1, motivo.byteValue() };

			PeerException comoFalhava = assertThrows(PeerException.class, () -> Snappy.descomprimir(corpoEmClaro));

			assertEquals("Snappy produziu 0 bytes, anunciou " + anunciado, comoFalhava.getMessage(),
					"os bytes não reproduzem a falha medida — então não são eles");

			MensagemRlpx lida = conversaAberta().ler(new byte[] { 0x01, (byte) 0xc1, motivo.byteValue() });

			assertEquals(motivo, assertInstanceOf(Disconnect.class, lida).motivo());
		});
	}

	// A tolerância é SÓ do Disconnect. Qualquer outra mensagem em claro depois do aperto continua
	// sendo quadro que não sabemos ler — aceitá-la seria ler lixo como se fosse protocolo.
	@Test
	@DisplayName("outra mensagem em claro depois dos dois Hello continua recusada")
	void outraMensagemEmClaroContinuaRecusada() {

		PeerException recusa = assertThrows(PeerException.class,
				() -> conversaAberta().ler(new byte[] { 0x02, (byte) 0xc1, 0x03 }));

		assertTrue(recusa.getMessage().startsWith("Snappy"), "a recusa deixou de ser do Snappy: " + recusa.getMessage());
	}

	// Uma conversa com os dois Hello trocados: é só a partir daí que a compressão vale.
	private static Rlpx conversaAberta() {

		Hello hello = new Hello(5, "teste", List.of(new Capacidade(Capacidade.ETH, 68)), 30303, new byte[64]);

		Rlpx nossa = new Rlpx();
		Rlpx doPar = new Rlpx();

		nossa.escrever(hello);
		nossa.ler(doPar.escrever(hello));

		return nossa;
	}
}
