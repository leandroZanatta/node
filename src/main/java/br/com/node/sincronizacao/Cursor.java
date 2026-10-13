package br.com.node.sincronizacao;

import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

import br.com.node.catalogo.Catalogo;
import br.com.node.catalogo.Eventos;
import br.com.node.catalogo.Transicao;
import br.com.node.rede.Recibos;
import br.com.node.util.Resources;
import br.com.node.vo.Bloco;
import br.com.node.vo.Log;

// Avança a altura processada, um bloco completo por vez.
//
// A CONTINUIDADE É INVARIANTE, NÃO RESULTADO. O cursor só avança quando o bloco está
// completo E o pai dele é o bloco que acabamos de processar. Não existe caminho em que
// ele passe por cima de um furo — então dá para estar ATRASADO, nunca FURADO.
//
// Com isso morre o maquinário inteiro que o projeto anterior precisou: backfill,
// reparo de lacuna, repedido de recibo, `reserva_mais_velha`, `FORA_DA_JANELA`. Todos
// existiam porque o cursor tinha permissão de passar na frente de dado incompleto.
//
// A MONTAGEM não é dele: a `Frente` enche em paralelo com vários pares, e o cursor só
// pergunta "a próxima altura está montada?". Foi para isto que as duas coisas nasceram
// separadas — a janela de pedidos em voo entrou sem mudar uma linha do avanço, porque
// avançar nunca dependeu de QUEM trouxe o bloco.
//
// E ela não é cache: não tem miss. Se a altura não está montada, o cursor SABE o que
// falta e pede. "Consultar e não achar, então buscar" é o caminho de volta que produz
// o ir-e-voltar; "saber o que falta e pedir" é o trabalho.
//
// DOIS MODOS, UM AVANÇO. `percorrer` anda uma faixa e termina — é instrumento de medição.
// `seguir` persegue a cabeça e NUNCA termina sozinho: no projeto anterior a sincronização
// morria com `lidos == 0` e só voltava com anúncio novo, e uma rodada ruim numa janela sem
// anúncio congelava tudo. Aqui "nada montado" é motivo para esperar, nunca para sair.
//
// REORGANIZAÇÃO: o projeto anterior não tratava reorg em lugar nenhum — dito no próprio
// código — e se protegia gravando só o que estivesse três blocos abaixo da cabeça, o que fazia
// a decisão chegar sempre depois. Aqui, pai diferente do processado RECUA até uma âncora cujo
// hash guardamos e repede a partir dela. Os eventos que movem preço carregam estado ABSOLUTO
// (`Sync` traz a reserva, `Swap` do V3 traz preço e liquidez), então reaplicar a cadeia certa
// sobrescreve o estado do bloco órfão. Não conserta `Mint`/`Burn`, que são deltas: uma pool
// mexida SÓ por liquidez dentro do trecho órfão fica com o delta dele até o próximo `Swap`.
// Mais fundo que a janela, o cursor para — atrasado, em voz alta.
public final class Cursor implements Closeable {

	private static final Logger LOG = Logger.getLogger(Cursor.class.getName());

	// Quanto se espera sem NENHUM avanço antes de desistir da faixa. A frente nunca
	// larga uma faixa — reatribui para sempre — então é aqui que "não dá para seguir"
	// tem de ser dito, e em voz alta. Só vale para `percorrer`: quem segue a cabeça espera.
	private static final Duration PARADA = Duration.ofSeconds(Resources.inteiro("sincronizacao.parada.segundos"));

	// Quantos hashes processados se guardam para ancorar um recuo, e quanto se recua de cada
	// vez. A BSC tem finalidade rápida e reorganiza um ou dois blocos quando reorganiza; 64 é
	// folga de sobra, e passar dele é sinal de par em outra cadeia, não de reorg.
	private static final int JANELA_DE_REORG = Resources.inteiro("sincronizacao.reorg.janela");
	private static final int PASSO_DE_REORG = Resources.inteiro("sincronizacao.reorg.passo");

	private final Frente frente;
	private final Catalogo catalogo;
	private final Consumer<Transicao> aoAplicar;
	private final BiConsumer<List<Log>, Long> aoAplicarTransacao;

	// Escrito pelas threads da frente (`montar`), lido e drenado pela thread que
	// percorre (`avancar`). É o único estado compartilhado do módulo, e é por isso que
	// ele é um mapa concorrente em vez de um `HashMap` com sincronizado em volta.
	private final Map<Long, Bloco> montagem = new ConcurrentHashMap<>();

	// UM aviso por BLOCO inteiro aplicado — o garimpo da cauda é daqui. CICATRIZ de 25/09: o garimpo
	// era disparado pelo gancho por TRANSAÇÃO; a primeira tx do bloco o iniciava com 1 de ~77 txs
	// aplicadas, e as outras 76 caíam na trava de "ocupada". Medido no servidor: 120.940 garimpos para
	// 111.843 blocos — um por bloco, sempre na primeira tx, e o resto do bloco só garimpado no SEGUINTE.
	private volatile java.util.function.LongConsumer aoFecharBloco = altura -> {
	};

	// O bloco que chega ACORDA o cursor. Antes ele dormia 10 ms às cegas entre uma olhada e outra.
	private final java.util.concurrent.Semaphore chegou = new java.util.concurrent.Semaphore(0);

	// Os hashes das últimas alturas aplicadas. É a âncora do recuo, e só dele: não se consulta
	// para achar bloco, então não tem miss nem caminho de volta.
	private final Map<Long, byte[]> processados = new HashMap<>();

	// `altura` e `reorganizacoes` são escritas só pela thread que percorre, e lidas por quem
	// acompanha de fora (o teste, o log do node). `parado` é o contrário: escrito por quem
	// fecha, lido pelo laço.
	private volatile long altura;
	private volatile int reorganizacoes;
	private volatile boolean parado;

	private byte[] hashProcessado;
	private long pedidaAte;

	// Quanto tempo esta thread passou DECIDINDO e APLICANDO, e não esperando bloco. É a
	// medida que separa "a rede é lenta" de "nós somos lentos", e as duas pedem desenhos
	// opostos — mais pares em voo contra mais de uma thread aplicando.
	private long nanosAplicando;

	public Cursor(Frente frente, Catalogo catalogo, Consumer<Transicao> aoAplicar) {
		this(frente, catalogo, aoAplicar, (transacao, altura) -> {
		});
	}

	public Cursor(Frente frente, Catalogo catalogo, Consumer<Transicao> aoAplicar,
			BiConsumer<List<Log>, Long> aoAplicarTransacao) {
		this.frente = frente;
		this.catalogo = catalogo;
		this.aoAplicar = aoAplicar;
		this.aoAplicarTransacao = aoAplicarTransacao;
	}

	public long altura() {
		return altura;
	}

	public int reorganizacoes() {
		return reorganizacoes;
	}

	public long milisAplicando() {
		return nanosAplicando / 1_000_000;
	}

	// Processa até `quantos` blocos a partir de `dePartida`, e devolve quantos avançou. O
	// primeiro bloco é a âncora: não há pai nosso para conferir contra ele, e afirmar que há
	// seria inventar. Do segundo em diante, todo avanço é encadeado.
	public int percorrer(long dePartida, int quantos, int porLote) {

		ancorar(dePartida);

		long fim = dePartida + quantos - 1;
		int entregues = frente.entregues();
		long desistirEm = System.currentTimeMillis() + PARADA.toMillis();

		while (altura < fim && !parado) {

			encher(fim, porLote, 1);

			// Entrega também é progresso: a faixa que falta pode estar atrás de outras na fila.
			if (frente.entregues() != entregues) {
				entregues = frente.entregues();
				desistirEm = System.currentTimeMillis() + PARADA.toMillis();
			}

			Bloco proximo = montagem.remove(altura + 1);

			if (proximo != null) {

				if (!avancar(proximo) && !recuar()) {
					break;
				}

				desistirEm = System.currentTimeMillis() + PARADA.toMillis();

			} else if (System.currentTimeMillis() > desistirEm) {

				LOG.warning(() -> "sem avanço em " + PARADA.toSeconds() + "s na altura " + (altura + 1));

				break;

			} else {
				esperar();
			}
		}

		return (int) (altura - (dePartida - 1));
	}

	// Persegue a cabeça até alguém fechar o cursor. `leque` é quantos pares recebem ao mesmo
	// tempo o pedido do bloco da cabeça: corrida, não fila — o hash do bloco é o mesmo para
	// todo mundo, então a primeira resposta vale, e esperar um de cada vez é escolher a
	// latência do mais lento. Cópias caem na MESMA montagem, que é indexada por altura: a
	// segunda que chega sobrescreve a primeira com o mesmo bloco.
	public void seguir(long dePartida, Cabeca cabeca, int porLote, int leque) {

		ancorar(dePartida);
		cabeca.aoAvancar(chegou::release);

		while (!parado) {

			encher(cabeca.alvo(), porLote, leque);

			Bloco proximo = montagem.remove(altura + 1);

			if (proximo == null) {
				repedirSeTravado();
				esperar();
				continue;
			}

			if (avancar(proximo)) {
				cabeca.aplicada(altura, proximo.cabecalho().instanteMs());
				aoFecharBloco.accept(altura);
				podarAplicados();
				travadoDesde = 0;
				continue;
			}

			if (!recuar()) {
				return;
			}
		}
	}

	// Recebe um bloco montado. Chamado pelas threads da frente, nunca pela que percorre.
	//
	// Bloco de altura JÁ APLICADA não entra. CICATRIZ medida em 23/09 (JFR, amostras de objetos
	// antigos): 213 de 231 eram recibos de bloco (~210 KB em média, até 550 KB) presos aqui. O `seguir`
	// pede a cabeça a LEQUE pares; a primeira cópia é consumida e as atrasadas chegavam para uma altura
	// que o cursor nunca mais retira (ele só tira `altura + 1`) — ~850 MB/h, o estouro de toda noite.
	public void montar(Bloco bloco) {

		long numero = bloco.cabecalho().numero();

		if (numero <= altura) {
			return;
		}

		montagem.put(numero, bloco);
		chegou.release();
	}

	// Quem precisa do estado ANTES de cada transação (a autópsia do mercado fotografa as pools que ela toca).
	private volatile BiConsumer<List<Log>, Long> antesDaTransacao = (logs, altura) -> {
	};

	public void antesDaTransacao(BiConsumer<List<Log>, Long> antesDaTransacao) {
		this.antesDaTransacao = antesDaTransacao;
	}

	public void aoFecharBloco(java.util.function.LongConsumer aoFecharBloco) {
		this.aoFecharBloco = aoFecharBloco;
	}

	// A cópia que passou pelo `montar` no mesmo instante em que o cursor avançou sobre ela. Barata: só
	// varre quando a montagem tem mais do que a frente costuma ter adiantado.
	private void podarAplicados() {

		if (montagem.size() > MONTAGEM_FOLGADA) {
			long aplicada = altura;
			montagem.keySet().removeIf(numero -> numero <= aplicada);
		}
	}

	private static final int MONTAGEM_FOLGADA = 64;

	// REPEDIDO DO BLOCO QUE TRAVA. O cursor aplica em ordem: enquanto `altura + 1` não chega, nada anda. A
	// cabeça dispara, a frente manda um lote a UM par sem leque, e se ele não responde tudo espera o prazo
	// (2,5 s + 250 ms por bloco) antes de outro par tentar. MEDIDO em 24/09 de manhã: "prazo estourado"
	// dobrou em 7 min e o cursor travou 120–180 blocos várias vezes. Passados REPEDIDO_MS sem o próximo
	// bloco, pede-se ele SOZINHO de novo — outro par, a primeira resposta vence (a montagem é por altura).
	private static final long REPEDIDO_MS = 700;
	private long travadoDesde;
	private long ultimoRepedido;
	private long repedidos;

	private void repedirSeTravado() {

		long agora = System.currentTimeMillis();

		if (travadoDesde == 0) {
			travadoDesde = agora;
			return;
		}

		if (agora - travadoDesde >= REPEDIDO_MS && agora - ultimoRepedido >= REPEDIDO_MS && pedidaAte > altura) {
			frente.pedir(new Faixa(altura + 1, 1));
			ultimoRepedido = agora;
			repedidos++;
		}
	}

	public long repedidos() {
		return repedidos;
	}

	@Override
	public void close() {

		parado = true;

		montagem.clear();
	}

	private void ancorar(long dePartida) {

		altura = dePartida - 1;
		hashProcessado = null;
		pedidaAte = altura;

		processados.clear();
		montagem.clear();
	}

	// Pede para a frente tudo o que ainda cabe na janela dela. Quem sabe o que falta é o
	// cursor; quem sabe quanto aguenta é a frente. Nenhum dos dois adivinha o do outro.
	//
	// A faixa de UM bloco é a da cabeça — na cabeça o alvo anda um por anúncio — e é só ela que
	// sai em leque. Mandar em leque a faixa de 16 do catch-up seria pagar a banda quatro vezes
	// por blocos que nenhum anúncio está esperando.
	private void encher(long fim, int porLote, int leque) {

		while (pedidaAte < fim && frente.folga() > 0) {

			int lote = (int) Math.min(porLote, fim - pedidaAte);

			Faixa faixa = new Faixa(pedidaAte + 1, lote);

			for (int copia = 0; copia < (lote == 1 ? leque : 1); copia++) {
				frente.pedir(faixa);
			}

			pedidaAte += lote;
		}
	}

	private void esperar() {

		try {
			chegou.drainPermits();
			if (!montagem.containsKey(altura + 1)) {
				chegou.tryAcquire(10, java.util.concurrent.TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			parado = true;
		}
	}

	// Aplica o bloco se ele encadeia. Falso quer dizer UMA coisa só: o pai não é o que
	// processamos — reorganização, ou par em outra cadeia.
	private boolean avancar(Bloco bloco) {

		if (hashProcessado != null && !Arrays.equals(bloco.cabecalho().hashDoPai(), hashProcessado)) {

			LOG.warning(() -> "pai diferente do processado na altura " + (altura + 1));

			return false;
		}

		altura++;
		hashProcessado = bloco.cabecalho().hash();

		processados.put(altura, hashProcessado);
		processados.keySet().removeIf(guardada -> guardada <= altura - JANELA_DE_REORG);

		long comecou = System.nanoTime();

		aplicar(bloco.recibos(), altura);

		nanosAplicando += System.nanoTime() - comecou;

		return true;
	}

	// Recua para uma âncora de hash conhecido e deixa a frente repedir dali. Recuar descarta a
	// montagem inteira: bloco montado acima da âncora pode ser justamente o da cadeia órfã.
	private boolean recuar() {

		long ancora = altura - PASSO_DE_REORG;
		byte[] hashDaAncora = processados.get(ancora);

		if (hashDaAncora == null) {

			LOG.severe(() -> "reorganização mais funda que " + JANELA_DE_REORG + " blocos na altura " + (altura + 1)
					+ ": o cursor para atrasado em vez de seguir furado");

			return false;
		}

		LOG.warning(() -> "recuando de " + altura + " para " + ancora);

		reorganizacoes++;

		montagem.clear();
		processados.keySet().removeIf(guardada -> guardada > ancora);

		altura = ancora;
		hashProcessado = hashDaAncora;
		pedidaAte = ancora;

		return true;
	}

	private void aplicar(byte[] recibos, long emQueAltura) {

		List<List<Log>> porTransacao = Recibos.porTransacao(recibos);

		for (int transacao = 0; transacao < porTransacao.size(); transacao++) {

			List<Log> logsDaTransacao = porTransacao.get(transacao);

			antesDaTransacao.accept(logsDaTransacao, emQueAltura);

			for (Log log : logsDaTransacao) {

				Optional<br.com.node.nucleo.ReservasV2> antes = Optional.empty();

				if (log.assinadoPor(Eventos.SYNC_V2)) {
					antes = catalogo.aplicarSync(log, emQueAltura);
				} else if (Eventos.ehSwapV3(log)) {
					catalogo.aplicarSwapV3(log, emQueAltura);
				} else if ((log.assinadoPor(Eventos.MINT_V3) || log.assinadoPor(Eventos.BURN_V3))
						&& log.topicos().size() >= 4) {
					catalogo.aplicarLiquidezV3(log, emQueAltura);
				} else if (Eventos.ehSwapDeGerente(log)) {
					catalogo.aplicarSwapDeGerente(log, emQueAltura);
				} else if (Eventos.ehLiquidezDeGerente(log)) {
					catalogo.aplicarLiquidezDeGerente(log, emQueAltura);
				} else if (Eventos.ehInicializacaoDeGerente(log)) {
					catalogo.aplicarInicializacao(log, emQueAltura);
				}

				aoAplicar.accept(new Transicao(log, emQueAltura, transacao, antes));
			}

			aoAplicarTransacao.accept(logsDaTransacao, emQueAltura);
		}

		// Aprender o par das pools que estes Transfer revelam é do catálogo, não do cursor.
		catalogo.inferirPares(porTransacao);
	}
}
