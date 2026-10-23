package br.com.node.descoberta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.node.NodeDeTeste;
import br.com.node.cadeia.Crivo;
import br.com.node.vo.ForkId;
import br.com.node.vo.NoRemoto;

class CrivoDeRedeTest {

	private static final int ESPERA_SEGUNDOS = 120;

	@Test
	@DisplayName("reconhece qualquer corte de fork da BSC, e recusa o de outra rede")
	void reconheceOsCortesDaBsc() {

		
		
		ForkId doGenesis = new ForkId(new byte[] { 0x3f, 0x2e, (byte) 0x9a, (byte) 0xe4 }, 5_184_000L);

		
		ForkId deHoje = new ForkId(new byte[] { (byte) 0xd3, (byte) 0x8c, (byte) 0xc2, (byte) 0x82 }, 0L);

		
		ForkId deOutraRede = new ForkId(new byte[] { 0x22, (byte) 0xd5, 0x23, (byte) 0xb2 }, 0L);

		Crivo crivo = Crivo.bsc();

		assertTrue(crivo.aceita(doGenesis), "o fork id do genesis tem de passar");
		assertTrue(crivo.aceita(deHoje), "o corte de hoje tem de passar");
		assertFalse(crivo.aceita(deOutraRede), "hash de outra rede não pode passar");
	}

	@Test
	@DisplayName("encontra na rede um peer da BSC que não estava nas sementes")
	void encontraPeerDaBscForaDasSementes() throws InterruptedException {

		Set<String> daSemente = new HashSet<>();

		NodeDeTeste node = new NodeDeTeste(no -> {
		});

		node.sementes.forEach(semente -> daSemente.add(semente.idHex()));

		try {

			node.iniciar();

			long fim = System.currentTimeMillis() + ESPERA_SEGUNDOS * 1000L;
			List<NoRemoto> novosDaBsc = List.of();

			while (System.currentTimeMillis() < fim && novosDaBsc.isEmpty()) {

				Thread.sleep(1000);

				novosDaBsc = node.descoberta.pares(Crivo.bsc()::aceita).stream()
						.filter(no -> !daSemente.contains(no.idHex())).toList();
			}

			assertFalse(novosDaBsc.isEmpty(), "nenhum peer da BSC fora das sementes em " + ESPERA_SEGUNDOS
					+ "s — redes declaradas: " + node.descoberta.redes().size());

			System.out.println("BSC fora das sementes: " + novosDaBsc.size() + " · primeiro: " + novosDaBsc.get(0));

		} finally {
			node.close();
		}
	}
}