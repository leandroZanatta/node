package br.com.node.sessao;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import br.com.node.exception.PeerException;

// A frente e a mempool numeravam pedidos cada uma com o seu contador, e o pedido 68 de uma respondia pelo 68
// da outra na mesma conversa. Um contador só para o processo, e id repetido falha alto em vez de trocar.
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
