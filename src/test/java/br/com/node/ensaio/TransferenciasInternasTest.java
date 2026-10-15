package br.com.node.ensaio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class TransferenciasInternasTest {

	@Test
	void achaTransferenciaFeitaPeloContratoDentroDaTransacao() {
		// Como a transação vencedora de fato se parece: topo com valor 0; um subcall do contrato manda BNB ao
		// endereço de pagamento da BlockRazor (1266…); o resto são chamadas sem valor.
		String trace = """
				{"type":"CALL","from":"0x2222","to":"0x5894","value":"0x0",
				 "calls":[
				  {"type":"CALL","from":"0x5894","to":"0x238a","value":"0x0","calls":[]},
				  {"type":"CALL","from":"0x5894","to":"0x1266c6be60392a8ff346e8d5eccd3e69dd9c5f20","value":"0x1019e0ef1c0c9"}
				 ]}
				""";
		List<TransferenciasInternas.Transferencia> achadas = TransferenciasInternas.doTrace(
				JsonParser.parseString(trace).getAsJsonObject());

		assertEquals(1, achadas.size());
		assertEquals("5894", achadas.get(0).de());
		assertEquals("1266c6be60392a8ff346e8d5eccd3e69dd9c5f20", achadas.get(0).para());
		assertEquals(new BigInteger("1019e0ef1c0c9", 16), achadas.get(0).valorWei());
	}
}
