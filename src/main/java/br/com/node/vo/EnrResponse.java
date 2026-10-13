package br.com.node.vo;

import java.util.Arrays;
import java.util.Optional;

public record EnrResponse(byte[] hashDoPedido, Optional<ForkId> rede) implements MensagemDiscovery {

	@Override
	public long expiracao() {
		return Long.MAX_VALUE;
	}

	@Override
	public int tipo() {
		return ENR_RESPONSE;
	}

	@Override
	public boolean equals(Object objeto) {
		return objeto instanceof EnrResponse outro && Arrays.equals(hashDoPedido, outro.hashDoPedido)
				&& rede.equals(outro.rede);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(hashDoPedido);
	}
}
