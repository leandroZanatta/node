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

	
	
	
	
	@Test
	@DisplayName("outra mensagem em claro depois dos dois Hello continua recusada")
	void outraMensagemEmClaroContinuaRecusada() {

		PeerException recusa = assertThrows(PeerException.class,
				() -> conversaAberta().ler(new byte[] { 0x02, (byte) 0xc1, 0x03 }));

		assertTrue(recusa.getMessage().startsWith("Snappy"),
				"a recusa deixou de ser do Snappy: " + recusa.getMessage());
	}

	
	
	private static Rlpx conversaAberta() {

		Hello hello = new Hello(5, "teste", List.of(new Capacidade(Capacidade.ETH, 68)), 30303, new byte[64]);

		Rlpx nossa = new Rlpx();
		Rlpx doPar = new Rlpx();

		nossa.escrever(hello);
		nossa.ler(doPar.escrever(hello));

		return nossa;
	}
}