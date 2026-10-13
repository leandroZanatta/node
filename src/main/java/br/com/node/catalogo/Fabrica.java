package br.com.node.catalogo;

import java.util.Map;
import java.util.OptionalInt;

// Uma factory cuja pool se identifica pelo próprio endereço.
//
// `factory` e `deployer` são campos separados porque nem sempre coincidem: na Uniswap V3 e nos V2 é a
// própria factory que executa o CREATE2; na PancakeSwap V3 quem executa é um PoolDeployer separado
// (`0x41ff9aa7…`), enquanto `factory()` da pool devolve `0x0bfbcf9f…`. Trocar um pelo outro dá um
// endereço que não existe, e a pool some da identificação sem erro nenhum.
//
// `espacamentoPorTaxa` só existe em V3 e é a tabela que a PRÓPRIA factory publica em
// `feeAmountTickSpacing`. `taxaFixa` só existe em V2 de taxa fixa; Biswap e MDEX guardam a taxa por
// par, e para eles ela fica vazia.
//
// Sete campos num registro só, e não é acúmulo: é a identidade inteira de um contrato, e separá-la
// obrigaria a juntar de novo em todo lugar que identifica uma pool.
// solidlyType: 0 = V2 padrão, 1 = Solidly volatile (salt inclui stable=0x00), 2 = Solidly stable (salt inclui stable=0x01).
public record Fabrica(String nome, boolean v3, byte[] factory, byte[] deployer, byte[] hashDoCodigo,
		Map<Integer, Integer> espacamentoPorTaxa, OptionalInt taxaFixa, byte solidlyType) {

	public boolean solidlyStable() {
		return solidlyType == 2;
	}
}
