package br.com.node.sessao;
import br.com.node.baixa.p2p.*;
import br.com.node.baixa.protocolo.*;


import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import br.com.node.exception.PeerException;

class PedidosIdTest {

	@Test
	void idsNuncaSeRepetem() {
		assertNotEquals(Pedidos.proximoId(), Pedidos.proximoId());
	}

	@Test
	void idRepetidoNaMesmaConversaFalha() {
		Pedidos pedidos = new Pedidos();
		pedidos.registrar(68);
		assertThrows(PeerException.class, () -> pedidos.registrar(68));
	}
}