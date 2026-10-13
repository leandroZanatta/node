package br.com.node.mempool;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import br.com.node.vo.SwapPendente;

// O caderno de layouts: acumula o que o bloco rotulou, aprende o formato de cada contrato e passa a ler
// local — sem RPC. É a peça que faz o sistema APRENDER DURANTE A EXECUÇÃO: cada bloco fechado é uma
// fornada de amostras, e cada amostra é de graça, porque o gabarito é a própria cadeia.
//
// E mantém a regra VIVA, que é o que a separa de uma tabela escrita à mão: toda transação que o bloco
// fecha é uma conferência do que a regra DECLAROU contra o que a cadeia MOVEU. Regra que para de
// acertar expira, e o contrato volta a ser aprendido do zero — porque bot troca de contrato, de formato
// e de rota, e uma regra velha projetaria com confiança sobre um mundo que já mudou.
//
// O `ler` é o caminho QUENTE (uma vez por transação pendente) e por isso não toma lock: é um lookup em
// mapa concorrente. Quem escreve (anotar/revisar/conferir) roda na thread do bloco e sincroniza.
public final class CadernoDeLayouts {

	// Abaixo disto não se julga: uma ou duas conferências não separam azar de regra errada.
	private static final int MINIMO_DE_CONFERENCIAS = 2;

	// Memória limitada por contrato: o formato de HOJE é o que importa, e guardar séculos de histórico
	// faria a regra descrever um mundo que não existe mais.
	private static final int TETO_DE_CONTRATOS = 4_096;

	private final int minimo;
	private final int guardados;

	private final Map<String, List<ExemploDeMovimento>> amostras = new ConcurrentHashMap<>();
	private final Map<String, LayoutAprendido> layouts = new ConcurrentHashMap<>();
	private final Map<String, long[]> placar = new ConcurrentHashMap<>();

	public CadernoDeLayouts(int minimo, int guardados) {

		if (minimo < 1) {
			throw new IllegalArgumentException("consenso mínimo menor que um não é consenso");
		}

		if (guardados < minimo) {
			throw new IllegalArgumentException("guardar menos amostras que o consenso impede de aprender");
		}

		this.minimo = minimo;
		this.guardados = guardados;
	}

	// Lê uma transação pendente pelo layout do contrato, se houver. Vazio quando o contrato ainda não
	// foi aprendido, quando o formato não fecha, ou quando ele nunca expõe o montante — os três casos
	// que mandam a transação para a simulação em vez de inventar projeção.
	public Optional<SwapPendente> ler(byte[] destino, byte[] calldata) {

		LayoutAprendido layout = layouts.get(LayoutAprendido.chave(destino, calldata));

		return layout == null ? Optional.empty() : layout.ler(calldata);
	}

	public synchronized void anotar(ExemploDeMovimento exemplo) {

		if (amostras.size() > TETO_DE_CONTRATOS) {
			amostras.clear();
		}

		List<ExemploDeMovimento> doContrato = amostras
				.computeIfAbsent(LayoutAprendido.chave(exemplo.anunciada().destino(), exemplo.anunciada().calldata()),
						chave -> new ArrayList<>());

		if (doContrato.size() >= guardados) {
			doContrato.remove(0);
		}

		doContrato.add(exemplo);
	}

	// Reaprende quem já tem amostras. Contrato que ainda não atingiu o consenso continua sem layout — e
	// continua sem ele de propósito: regra nascida de duas amostras é palpite com cara de regra.
	public synchronized void revisar() {

		for (Map.Entry<String, List<ExemploDeMovimento>> amostra : amostras.entrySet()) {

			Optional<LayoutAprendido> layout = AprendizDeLayout.aprender(amostra.getValue(), minimo);

			if (layout.isPresent()) {
				layouts.put(amostra.getKey(), layout.get());
			}
		}
	}

	// A conferência contra o gabarito: o que a regra declarou, contra o que o bloco moveu de verdade.
	// Chamado para toda transação que foi incluída e que nós tínhamos anunciado.
	public synchronized void conferir(byte[] destino, byte[] calldata, List<byte[]> tokensMovidosNoBloco) {

		String chave = LayoutAprendido.chave(destino, calldata);
		LayoutAprendido layout = layouts.get(chave);

		if (layout == null) {
			return;
		}

		long[] conta = placar.computeIfAbsent(chave, c -> new long[2]);
		conta[0]++;

		Optional<List<byte[]>> declarados = layout.tokensLidos(calldata);

		if (declarados.isPresent() && mesmoConjunto(declarados.get(), tokensMovidosNoBloco)) {
			conta[1]++;
			return;
		}

		if (conta[0] >= MINIMO_DE_CONFERENCIAS && conta[1] * 2 < conta[0]) {
			layouts.remove(chave);
			amostras.remove(chave);
		}
	}

	// A conferência EM ORDEM: o caminho que a regra lê (com direção) e as pools que ela sabe, contra as
	// trocas que a cadeia fez. A de conjunto (acima) aceitava caminho invertido e token repetido.
	public synchronized void conferir(byte[] destino, byte[] calldata, List<br.com.node.nucleo.Perna> reais,
			boolean ordenada) {

		String chave = LayoutAprendido.chave(destino, calldata);
		LayoutAprendido layout = layouts.get(chave);

		if (layout == null || reais.isEmpty()) {
			return;
		}

		if (!layout.ordenado()) {
			List<byte[]> tokens = new ArrayList<>();
			reais.forEach(p -> {
				tokens.add(p.entra());
				tokens.add(p.sai());
			});
			conferir(destino, calldata, tokens.stream().distinct().toList());
			return;
		}

		long[] conta = placar.computeIfAbsent(chave, c -> new long[2]);
		conta[0]++;

		if (AprendizDeLayout.reproduz(layout, calldata, reais)) {
			conta[1]++;
			return;
		}

		if (conta[0] >= MINIMO_DE_CONFERENCIAS && conta[1] * 2 < conta[0]) {
			layouts.remove(chave);
			amostras.remove(chave);
		}
	}

	/** Quantos contratos o caderno lê hoje — para o relatório dizer que o aprendizado produziu algo. */
	public int aprendidos() {
		return layouts.size();
	}

	/**
	 * O placar por contrato@método: <code>[declarados, confirmados]</code>. É a acurácia viva, medida
	 * contra o bloco, e não contra nós mesmos.
	 */
	public Map<String, long[]> placar() {

		Map<String, long[]> copia = new LinkedHashMap<>();

		placar.forEach((chave, conta) -> copia.put(chave, new long[] { conta[0], conta[1] }));

		return copia;
	}

	// Conjunto, não ordem: a ordem que o leitor devolve é a da calldata, e quem valida se ela é um
	// caminho possível é o mapa de pools adiante — a conferência aqui é sobre QUAIS tokens se moveu.
	private static boolean mesmoConjunto(List<byte[]> um, List<byte[]> outro) {

		if (outro == null) {
			return false;
		}

		Set<String> a = um.stream().map(valor -> HexFormat.of().formatHex(valor)).collect(Collectors.toSet());
		Set<String> b = outro.stream().map(valor -> HexFormat.of().formatHex(valor)).collect(Collectors.toSet());

		return a.equals(b);
	}
}
