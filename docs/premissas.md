# Premissas — matar o problema em vez de remendar o sintoma

Quatro perguntas sobre o projeto anterior. Cada uma tem causa-raiz própria, e
cada remendo que existia lá morre por causa de uma premissa daqui.

---

## 1. Por que precisávamos de cache anelar?

Por dois motivos que ficaram confundidos num mecanismo só.

**Motivo declarado:** respondíamos `vazio` a quase todo pedido, virávamos leech,
e o par nos evictava. O anel era para servir cabeçalho e recibo que já tínhamos
processado.

**Motivo real, e mais grave:** *o plano frio lia cabeçalho e recibo de cada bloco
e jogava fora depois de processar.* Quando alguém precisava daquilo de novo,
buscava de novo. O anel é o remendo de **descartar o que já se pagou para ler**.

E há um terceiro efeito, que é o que você chama de pular para frente e para
trás: um **cache tem miss**. Miss abre um caminho de volta — consulta, não achou,
vai buscar. Esse caminho de volta é o ir-e-voltar. Não é o cache que causa; é o
*caminho de fallback* que ele legitima.

> **Isso não é cache, é janela.** Janela não tem miss: se não está nela, a
> resposta é "não tenho", e acabou. Sem caminho de volta, não há ida-e-volta.

E servir o par deixa de ser cache: vira **obrigação de retenção**, dimensionada
pelo que o protocolo espera que a gente consiga servir — não por esperança.

---

## 2. Por que saíamos de 400 blocos/s para 2 ou 3?

Repare na forma da queda: ela é **bimodal**. Não degradou, desabou. Vazão bimodal
é sempre assinatura de **espera em série** por alguém.

A cadeia já estava documentada: leech → o par evicta → o socket morre em silêncio
→ zumbi ocupa um dos 25 slots para sempre → a rodada pergunta a quem não existe →
timeout de 3 s → a rodada quebra no primeiro buraco → a thread de sync morre com
`lidos==0`.

Mas a causa arquitetural é anterior a tudo isso: **a rodada era síncrona e o
ritmo era ditado pelo pior par.** Com rodada, a vazão é função do mais lento e do
primeiro buraco. Com 25 slots e alguns zumbis, o mais lento é infinito.

> **Nenhum estágio espera por um par específico.** Não existe rodada: existe
> janela de pedidos em voo, cada um com prazo, e quem não responde perde o pedido
> para outro. A vazão passa a degradar *linear* com a qualidade dos pares, em vez
> de desabar.

Aumentar o teto de 25 não conserta: zumbi ocupa qualquer teto.

**Feito.** `sincronizacao/Frente` é essa janela: faixas em voo, arrendamento de sessão
por pedido, e reatribuição de quem falha ou entrega vazio. O `Cursor` não conhece par
nenhum — pede pela frente e recebe bloco montado. Ver *A janela de pedidos em voo* em
`arquitetura.md`.

---

## 3. Por que atrasávamos bloco que chegava por callback?

Esta é a mais reveladora. O anúncio da cabeça chega empurrado, de graça, no
instante em que o bloco existe. E mesmo assim chegávamos atrasados.

**Causa:** o anúncio entrava na mesma fila do catch-up frio. Um item com prazo de
450 ms ficava atrás de milhares de itens com prazo de horas. É o erro-raiz na
forma mais pura — classes de prazo dividindo a mesma faixa.

E o agravante é exatamente o que você descreveu: *o código sabia o que era quente
e o que era frio, e decidia item a item.* Esse ponto de decisão **é** o defeito.
Toda vez que existe um `if` escolhendo o plano, a latência do item passa a
depender de um desvio em tempo de execução — e ninguém consegue mais raciocinar
sobre o pior caso.

> **O prazo é propriedade do caminho, não do item.** Duas faixas fisicamente
> separadas, decididas na *porta de entrada*, e nada troca de faixa depois.
> Nenhuma linha de código em lugar nenhum pergunta "isto é quente ou frio?" — a
> resposta é a porta por onde entrou.

Anúncio da cabeça entra pela faixa curta. Histórico entra pela faixa longa. Não
existe passagem entre elas.

---

## 4. Por que tínhamos furos, e dado de sincronização velha estando na cabeça?

São duas doenças, não uma.

### Furos

A rodada quebrava no primeiro buraco, e o conserto do buraco era um mecanismo
*separado e posterior* — backfill, reparo, retentativa de recibo. Tudo isso
existe porque **ninguém era dono do intervalo**. Pedido era disparado e
esquecido; completude era propriedade emergente, torcida.

> **Continuidade é invariante, não resultado.** Existe um cursor, e ele só avança
> quando o bloco está completo. O cursor nunca passa por cima de um furo.

Com isso, furo deixa de ser possível: dá para estar **atrasado**, nunca
**furado**. E morre junto todo o maquinário de backfill, reparo de lacuna e
repedido de recibo — ele só existia porque o cursor tinha permissão de passar na
frente de dado incompleto.

### Dado velho decidindo na cabeça

O plano frio continuava processando o atraso e **escrevendo resultado**, e a
escrita não era ordenada por "isto ainda é a cabeça?". Daí nasceram
`CorrenteDaCabeca`, `ForaDaCabecaNaoAvalia`, `DecidirNoTopo` — três remendos para
a mesma falta.

Duas premissas matam isso, e a segunda é mais forte que a primeira:

> **Todo registro carrega a altura em que vale**, e o consumidor de prazo curto
> não *consegue* construir uma decisão sem ela. Não é filtro que alguém lembra de
> aplicar: é o tipo que não compila sem.

> **O frio não fala com o quente. O frio publica snapshot.** O frio produz o
> catálogo — rotas, pools, taxas, provas. O quente decide sobre *catálogo
> imutável + cabeça*. Não existe caminho por onde dado histórico alcance a
> decisão, então não existe o que filtrar.

A segunda é a que fecha a porta. A primeira só a tranca.

---

## As premissas, juntas

| # | Premissa | Mata |
|---|---|---|
| P1 | O prazo é do caminho, não do item. Nada troca de faixa. | anúncio atrasado atrás do catch-up; todo `if` de plano |
| P2 | Não existe caminho de volta. Janela, não cache. | cache anelar; ir-e-voltar |
| P3 | Quem lê, entrega uma vez; quem precisa, se inscreve. | releitura do que já se pagou |
| P4 | Nenhum estágio espera por um par específico. | colapso de 400 para 2 blocos/s |
| P5 | Continuidade é invariante, não resultado. | furos; backfill; reparo de lacuna |
| P6 | Todo dado carrega a altura em que vale. | decisão sobre dado velho |
| P7 | O frio não fala com o quente; publica snapshot. | a própria possibilidade de dado velho chegar |

## O que isto contradiz no desenho anterior

`projeto-sincronizacao.md` diz "absolutamente tudo vai para o arbitrador via
evento". **P7 recusa isso.** Mandar tudo significa mandar histórico, e histórico
chegando ao arbitrador é exatamente a doença 4 renascendo com nome novo.

A leitura que sobrevive: *tudo que a **cabeça** produz* vai ao arbitrador por
evento. O que o **histórico** produz vai para o catálogo, e o arbitrador lê o
catálogo como snapshot imutável — nunca como fluxo.

Precisa da sua decisão antes de virar código.

## O teste que prova cada premissa

Premissa sem teste é intenção. Cada uma precisa de um teste de módulo que falhe
se ela for violada:

| Premissa | Como se prova |
|---|---|
| P1 | Nenhum tipo do caminho curto é alcançável a partir do caminho longo — regra de dependência no `ArquiteturaTest`. |
| P2 | Nenhum estágio tem método que possa buscar na rede a partir de uma consulta local. |
| P4 | Com metade dos pares mudos de propósito, a vazão cai perto de metade — não a zero. |
| P5 | Matar pares no meio de uma faixa: o cursor **para**, e a sequência processada não tem buraco. |
| P6 | Um evento sem altura não compila. |
| P7 | O pacote do arbitrador não importa nada do caminho longo. |

---

## 8. O que aprendemos sobre o que é, de fato, oportunidade

Estas não são premissas herdadas do projeto anterior: saíram das medições deste.

**P8 — Taxa só entra provada, nunca suposta.** Só vale a taxa que fecha EXATAMENTE a troca
observada, e só se for redonda. *Por quê:* o projeto anterior gravou a pool `7a3eb6e7…` com 5 bps
por média de 94.404 trades, e o executor dele levou 13 reverts por invariante em 13 num dia.
*Como se prova:* taxa quebrada nunca entra, e provas diferentes se ordenam pelo K (P14) — a regra
original "contradição apaga" estava errada.

*Leitura corrigida da cicatriz* (item 1): o old media em **base 10.000**, então "cobra 1000" é
**10%**, não 0,1%. As duas medidas estavam certas, cada uma para o seu chamador: 5 bps para os
chamadores que usam a pool, 10% para quem chama o par direto. A lição P8 fica; o que ela ganha é
P15.

E um corolário que custou um defeito real: **o estado pendente vale só DENTRO da transação.**
O `Sync` guardado por pool sem limite de transação era casado com o `Swap` de outra transação,
e estado errado mais inversão exata dá taxa errada que FECHA — indistinguível de taxa certa.
O defeito foi real e está corrigido. Mas o 500 que a `7a3eb6e7…` continuou dando depois dele
não era consequência do defeito, nem erro: é a taxa dela para os seus dois chamadores (P15).

**P9 — Pool sem curva não entra no grafo.** O portão é na pool, antes de ligar, não no ciclo
depois de enumerar. *Por quê:* filtrar depois produziu 4.566 ciclos para 274 servirem, e o
custo dos 94% descartados é atraso no único instante que importa. *Como se prova:* o número
de ciclos enumerados é igual ao de cotáveis.

**P10 — O tamanho vem da curva, o lucro vem da pool.** A forma `a·x/(b+c·x)` dimensiona; a
saída é sempre a que o contrato paga, com as truncagens dele. *Por quê:* lucro calculado
pela forma ignora arredondamento, e o arredondamento do V2 é contra quem chama — é lucro que
o contrato não paga. *Como se prova:* para o V2, curva e pool batem wei a wei.

**P11 — Não se procura arbitragem: procura-se o fluxo que a cria.** *Por quê:* das 513
transações com swap em 48 blocos, 400 tinham um swap só, e as 10 arbitragens limpas já
estavam capturadas e todas rendiam menos que o gas. Procurar arbitragem é achar o que outro
capturou. *Como se prova:* o vigia reavalia por pool mexida, não por varredura.

**P12 — Gas é o piso; capital é o critério.** *Por quê:* o maior achado de 1.024 blocos
pedia 103,7 BNB para tirar 0,22 — 740× o gas e 21 pontos-base de retorno. "Acima do gas"
aprova poeira e aprova conta que ninguém toma. *Como se prova:* o corte é conjunto, gas E
retorno mínimo.

**P12b — Só se compara com gas quem lucra na moeda do gas.** *Por quê:* o maior achado de
uma corrida era ciclo de USDT rendendo 0,0118 USDT, comparado contra 0,0003 BNB — e liderava
o relatório sem pagar o próprio gas. *Como se prova:* o filtro de gas só se aplica a ciclo
que parte de WBNB; o retorno em pontos-base, adimensional, vale para todos.

**P12c — Estado impossível se descarta no tipo, não se conserta a jusante.** *Por quê:* uma
pool V3 trouxe `liquidez = 1,41e48` — dez ordens de grandeza acima do uint128 do contrato — e
isso saiu como oportunidade de 1,6 bilhão de pontos-base, atravessando gas e retorno, porque
filtro sabe comparar e não duvidar. *Como se prova:* `EstadoV3` recusa no construtor, e o
catálogo conta a pool descartada em vez de calar.

*A origem, achada depois:* não era fork V3, era a palavra do `Mint` — o `sender` não indexado
empurra o `amount` para a palavra 1, e o `Cursor` lia a palavra 0, somando um endereço à liquidez.
E a guarda, certa, apagava do catálogo toda pool V3 que recebia liquidez até o decodificador ser
corrigido. **Recusar é necessário; contar o recusado é o que mostra quando se recusa demais.**

**P12d — Dois candidatos que servem não é resposta, é empate.** *Por quê:* o degrau de taxa
do V3 saía por ordem da lista quando vários caíam na folga de um wei, e o degrau amarra o
`tickSpacing` — espaçamento errado faz `aplicar` aceitar entrada que cruza tick, desligando a
única recusa que nos protege. *Como se prova:* empate devolve vazio; a concordância com
terceiros subiu de 7% para 20%.

**P13 — Afirmação sobre dinheiro tem de ser conferível por terceiro.** Enquanto não houver
execução, as duas testemunhas são: a arbitragem que terceiros de fato fizeram nas mesmas
pools, e o dossiê com endereço, taxa e reservas na altura — que qualquer um confere no
explorador. *Por quê:* o projeto anterior afirmava números que ninguém tinha como checar.

**P14 — O K ordena as provas, e o banco guarda evidência, não decisão.** Troca que fecha
exatamente em f prova que a pool cobra NO MÁXIMO f daquele chamador. *Por quê:* nas pools
"contraditórias", 2500 vinha de 9 a 20 chamadores e 3000 de um só robô que fechava 3000 em pools
diferentes; apagá-las jogava fora as mais negociadas. E a regra errada tinha ido parar dentro de
uma tabela — banco que guarda decisão decide com a regra do dia em que gravou. *Como se prova:*
vale a menor taxa provada por >= 2 chamadores distintos; `provas_de_taxa` guarda (pool, taxa,
chamador, fechamentos), e semear reproduz todas as decisões.

**P15 — Taxa provada por log vale para QUEM trocou, e roteador conta como todos.** *Por quê:* a
`7a3eb6e7…` cobra 5 bps dos seus dois chamadores e 10% de quem chama o par direto, e por log isso
é invisível. E a primeira tentativa de detectar isso tirou 68 pools "de um chamador só" que eram
negociadas pelo roteador da PancakeSwap — as mais abertas da BSC. *Como se prova:* pool movimentada
sem nenhum roteador público entre os chamadores sai do grafo; roteador é endereço completo com
procedência, nunca prefixo nem suposição. Fechamento definitivo: simular a nossa própria chamada.

**P16 — Migração reabre ferida fechada: leia o old antes de escrever.** *Por quê:* três das feridas
do item 1 — a palavra do Mint, a assinatura do Swap da PancakeSwap V3, o imposto por direção — o
old já tinha fechado, com comentário, e a reescrita as reabriu em silêncio. Pool que nunca emite o
evento que se escuta simplesmente nunca aparece, sem erro nenhum. *Como se prova:* todo agente lê o
old e o histórico antes da primeira linha.

**P17 — Na cabeça, ninguém espera pelo pior par.** O cursor segue a cabeça e nunca termina sozinho;
o bloco novo sai em leque para vários pares, e cada pedido tem prazo do tamanho dele. *Por quê:*
com leque 1 e prazo único de 15 s, a mediana da cabeça foi 18,9 s — um par travado segurava o cursor
encadeado inteiro. *Como se prova:* leque 4 com prazo por pedido: mediana 363 ms e p90 540 ms, dentro
do bloco de 750 ms.

**P18 — Meça a queda antes de mudar o protocolo.** *Por quê:* a hipótese "parecemos leech por
anunciar genesis" parecia óbvia e mudar o `Status` exigiria fabricar dificuldade. Medido, o platô é de
abertura (922 contra 51 quedas em conversa), nenhum par desafiou a cabeça anunciada, e até o "defeito
do Snappy" era um `Disconnect` em claro cujo motivo se perdia. *Como se prova:* a `Lotacao` conta por
que cada conversa cai, por fase.

**P19 — A pool se identifica pelo endereço, e a identidade vence a troca.** `CREATE2(deployer, sal,
hash do bytecode)` recalculado e batendo prova de que contrato a pool é; em V3 prova a taxa e o
espaçamento, em V2 de taxa fixa prova a taxa. *Por quê:* a conferência achou PancakeSwap V1 e V2 com
taxa provada por troca 3000 (um robô calculando com 0,3%) cobrando 2000 e 2500, e um fork V3 com
espaçamento 2000 onde o mapa universal dizia 200. *Como se prova:* só entra factory provada contra a
cadeia (CREATE2 contra `getPair`/`getPool` reais); `FabricasTest` trava os endereços; a conferência
bate a factory deduzida contra `factory()` de cada pool (774 V2 e 483 V3, zero divergências).

**P20 — Dado confiável é o que bate com o contrato, e o instrumento se confere antes.** *Por quê:* toda
prova anterior era do node sobre si mesmo, e três rodadas da conferência reprovaram pelo INSTRUMENTO
(janela de estado, cabeça do `Status`, lotes lendo estados diferentes) antes de poderem dizer algo do
node. *Como se prova:* `Multicall3Test` exige que o caminho agregado leia o mesmo que a chamada direta,
inclusive a falha; "sem resposta" nunca conta como divergência; e a distância da foto à cabeça é
impressa, para toda falta ter causa legível.

## 9. A próxima etapa: projetar a mempool sobre as reservas

A hora na cabeça deu o veredicto: fenda que paga gás é tomada por terceiro em ≤3 blocos. Ver depois de
confirmado é ver o que já foi capturado. Para ser primeiro, a decisão não pode esperar o bloco — tem
de agir sobre o swap que AINDA está na mempool, projetando o efeito dele nas reservas e avaliando a
arbitragem que ele abre, no mesmo bloco em que ele entra.

**P21 — Projeção da mempool: hipótese sobre a cabeça, nunca sobre estado velho.** Já recebemos o
firehose e o descartamos — na hora medimos `NewPooledTransactionHashes` (código 18) e `Transactions`
(código 24) na casa do milhão em "não tratados". Essa é a matéria-prima. Projetar é decodificar o swap
pendente, aplicar `Pool.aplicar(direcao, entrada)` — que já devolve `Passo.depois`, o estado pós-swap —
e avaliar os ciclos sobre esse estado projetado, com o mesmo `Avaliador`/`Vigia` de hoje. A conta já
existe; falta a entrada.

*MEDIDO (AtrasoTest, 20 min, 2.730 blocos seguidos):* 610 arbitragens de terceiros, e **100% das
pools delas o catálogo já conhecia** (1902 de 1902) — não somos cegos, vemos onde o dinheiro passa. O
atraso anúncio→aplicado foi **mediana 332 ms (0,4 bloco), p90 664 ms**; nos blocos com arbitragem,
mediana 344 ms. Ou seja: a latência é boa e ficamos na cabeça — e mesmo assim não pegamos NENHUMA,
porque a arbitragem estava DENTRO do bloco e só a vemos depois de aplicá-lo. Estamos ~1 bloco atrás do
vencedor por construção, e nenhuma otimização de latência muda isso. É a prova de que a saída é
projetar a mempool, e os 340 ms de folga são o orçamento que a projeção tem para calcular.

*A precondição é ESTAR NA CABEÇA, e ela é load-bearing.* Projetar um swap pendente sobre reservas que
não são as do topo é lixo: o resultado descreve uma cadeia que não existe. A hora provou o risco — quando
o grafo explodiu, o cursor caiu para 0,7 bloco/s e ficou atrás da cabeça, e ali qualquer projeção seria
sobre estado vencido. Então a projeção da mempool depende de duas coisas que ainda faltam:

  1. **O avaliador cabe no bloco.** Grafo podado e avaliação incremental, para nunca cair atrás da
     cabeça — senão a base da projeção é velha. É pré-requisito, não otimização.
  2. **Decodificar o swap pendente por CALLDATA, não por log.** O pendente não emitiu evento; o que há é
     a calldata do roteador (ou do swap direto ao par). É a parte nova e difícil — o resto reusa o que
     está pronto.

*E a projeção é OVERLAY, nunca estado gravado.* O confirmado é a base; o projetado é base + pendente, à
parte, e some quando o bloco chega e reconcilia. Misturar os dois é a confusão frio/quente do old
voltando por outro nome (P1): reserva projetada gravada no catálogo é dado que parece verdade e não é.

**P22 — Reserva não vai ao banco; prova vai.** O que persiste é imutável: taxa (`provas_de_taxa`) e
imposto/honeypot (`impostos_provados`). Reserva e estado V3 NÃO persistem. *Por quê:* não sincronizamos
estado histórico — reserva só existe a partir do bloco em que começamos a ver os `Sync`/`Swap` dela, e
gravá-la seria um número que não se sabe se ainda vale (a confusão de "3 alturas diferentes" do old).
No restart, carrega-se a prova; a reserva monta-se de novo do fluxo quente. *Como se prova:* nenhum
repository importa `ReservasV2`/`EstadoV3`, e o esquema não tem coluna de reserva.

**P23 — Provado grada para a lista quente; só a quente atualiza reservas em tempo real.** Uma perna
vive em "a provar" enquanto a sonda a mede (em lote, sem prazo, numa thread própria). Provado tudo —
imposto zero, não honeypot, taxa — ela grada para a lista QUENTE, é salva no banco, e a partir daí seus
pares de arbitragem se atualizam bloco a bloco. Não provado é IGNORADO pelo quente: não se gasta
rastreio de reserva com ativo que não se sabe medir. *Por quê:* a arbitragem só confia no provado, e o
quente pequeno é o que mantém o avaliador dentro do bloco (a hora provou: grafo grande cai atrás da
cabeça). O grafo já é montado só sobre `precifica` (par + taxa + imposto-limpo-forte + não-honeypot),
que É o teste de graduação; falta a graduação empurrar a perna para a quente incrementalmente, em vez
de reconstruir periódico — é o próximo passo, junto de ligar o arbitrador.

## 10. O que a projeção da mempool revelou quando foi construída (e a virada de estratégia)

A P21 foi construída inteira — decodificação de swap pendente, projeção sobre reservas, e o ensaio do
NOSSO contrato `Executor` deployado por `eth_call`/`eth_simulateV1` (zero wei, zero gás). Rodar contra a
BSC derrubou três suposições e trocou a estratégia. Cada número abaixo foi medido, não estimado.

**P24 — O `eth_call` é o oráculo de correção, e é ele que torna o resto seguro.** O lucro de uma
arbitragem sobre um estado é DETERMINÍSTICO e fechado (a Möbius, provada wei-a-wei), e o `eth_call` no
contrato dá a resposta EXATA da EVM de graça. Consequência que muda tudo: erro de taxa/imposto/curva do
nosso modelo **não é bug de correção** — a EVM executa a taxa real e reverte (`SemLucro`/`NaoPagou`/`K`),
então a fenda falsa nunca é acionada, só desperdiça detecção. *Medido:* a `7a3eb6e7` cobra 5 bps dos
routers e 10% de quem chama o par direto (nós); o modelo projeta lucro, o `eth_call` reverte. O gate
pega. Corolário: rede neural/estocástico para "prever lucro" é ferramenta errada — aproximaria uma
função que já se calcula exata. O estocástico só cabe em prever PERSISTÊNCIA (adiante), não o cálculo.

**P25 — Projetar o bloco por calldata tem teto baixo; e o pendente não abre a fenda que projetávamos.**
Dois achados que enterram a projeção como FONTE. (a) *Recall medido: ~10-12%* — dos swaps que movem pool
num bloco, só decodificamos ~1/10. O fluxo da BSC é dominado por AGREGADORES (`swap((uint8,address,...)
[],...)`) e contratos PRIVADOS de bots (seletores fora de qualquer base pública). Adicionar métodos de
router padrão mal move a agulha. (b) *No `eth_simulateV1` da sequência `[swap pendente → nosso lance]`, o
veredito COM o pendente é IGUAL ao SEM* (forense caso-a-caso): o pendente não é o que abre a fenda. Ou
seja, a "fenda de N+1" que o modelo projetava era artefato, não dislocação criada pelo pendente. A
precisão de 100% (do que projetamos, confirma) era vazia; o que importava — recall — a gente perde.
*Ancoragem descartada como causa:* nossa reserva bate com a do nó no latest (divergência mediana 0,047%),
então não é estado velho; a fenda é micro ou falsa mesmo.

**P26 — A estratégia é a CAUDA PERSISTENTE: detectar atrás, agir em N+1 sobre o que sobrou, sem corrida.**
Não competimos em velocidade (perdemos: ~1 bloco atrás, sem colocation) nem em recall (perdemos: fluxo
opaco). Competimos em SELEÇÃO: o `eth_call` é um oráculo de EV grátis e honesto, então só disparamos o
que ele validou — `p≈1`, variância baixa, perda por tentativa limitada AO GÁS (o flash swap reverte
atômico, capital nunca sai). O mercado nas fendas grandes/óbvias é eficiente e disputado; o que sobra é
a cauda que os rápidos ignoram (abaixo do limiar de gás deles, ou em pool que não vigiam) e que
PERSISTE ≥1 bloco. Estar um bloco atrás é o SENSOR CERTO para "o que sobrou". *Medido (5h):* 17 backruns
o contrato aceitaria (raros, ~3/h) — o edge existe, é fino, e a consistência vem de recolher a cauda
validada, não de vencer bot. A renda consistente ≈ (taxa de fendas persistentes que pagam gás) × (líquido
médio), e é o que o teste de persistência (`PersistenciaTest`, sim-gated, BNB realizado decodificado do
retorno do contrato) mede antes de qualquer execução real. Executar de verdade é passo à parte, explícito,
com envio privado (relay) para não revelar a oportunidade na mempool pública.

## 11. O que a execução real ensinou (o gás vivo, o USDT, e o portão que virou pré-filtro)

**P27 — Com a simulação na frente de TODO envio, o portão de imposto vira pré-filtro, não a defesa.**
Ao armar a execução real, três coisas se provaram no ar (`ensaio.confirmado`, `projetado_n1`, `disparo`):

- **Gás é vivo, não constante.** O piso usava `GAS` fixo (~1 gwei × 300k = 0,0003 BNB); a BSC roda a
  0,05 gwei. O piso pedia ~34× o gás real e recusava o mercado inteiro (`raiz_numerario`=4097 achados,
  `pagariam_gas`=0). O `CustoDeGas` passou a ler o preço vivo e derivar piso E taxa do MESMO número —
  "3× o gás" virou literal. E a prioridade caiu de 1 gwei p/ 0,1 (cauda não-disputada não precisa furar fila).

- **O numerário não é só WBNB.** O contrato empresta de qualquer pool V2; o piso, medido no token da
  raiz (USDT convertido pela pool canônica WBNB/USDT), abriu os triângulos denominados em USDT — o grosso
  do volume da BSC. `limpo`/WBNB-only eram amarras, não princípios.

- **O caminho confirmado é o sensor errado para LUCRO.** Ele acha 4097 ciclos e todos rendem migalha
  (< piso): no bloco confirmado o lucro já foi capturado. O lucro vive no N+1 (fenda pré-execução), e é lá
  que o `Disparo` foi ligado. O confirmado fica de diagnóstico/cauda.

- **A cobertura estava presa (74 pools) pelo portão de imposto estrito (`limpo`, P8).** Mas P8 nasceu num
  mundo SEM simulação adiante — o old enviava direto do preço local, e não-provado-suposto-limpo foi o
  buraco do YFSX. Hoje TODO envio passa antes pela simulação na cadeia (`eth_call`/`eth_simulateV1`, EVM
  real: imposto, honeypot, taxa exata) E pelo `lucroMinimo` atômico do contrato. A simulação é o filtro
  por-arb, mais forte que a prova prévia. Então o portão (`naoCondenado`) passou a só barrar o que JÁ se
  provou sujo (forte taxado/honeypot, ou evidência fraca de imposto visto); o não-provado ENTRA na
  detecção e a simulação decide. Um **piso de liquidez** (no lado do numerário, ~US$ 30) poda dust/honeypot
  morto e limita o grafo, sem florear as pools mid-depth onde a fenda N+1 é suculenta. O buraco do YFSX
  segue fechado — pela simulação, não pelo portão. `PortaoDeImpostoTest`/`FantasmaDeImpostoTest` foram
  repontados: condenam o provado sujo, admitem o não-provado (a simulação é o guarda). Risco de tudo isso:
  limitado ao gás (flash reverte atômico), com teto diário e auto-kill na primeira arb real.


## 12. A cobertura do decode, medida — e onde ela pode crescer

**P28 — O decode por ABI tem cauda longa; metade da calldata carrega o caminho, e o resto só a EVM sabe.**
Dois medidores, ambos executáveis, sobre blocos recentes (`RecallDaProjecaoTest` diz QUANTO se perde;
`CoberturaDoDecodeTest` diz ONDE e se é alcançável).

- **Recall de V2 = 56,2% (59 de 105 txs que moveram pool V2 em 8 blocos).** Melhor que a cicatriz de
  10-12% da P25, porque o `Roteamento` ganhou as variantes ETH-in e o `RoteamentoUniversal` entrou; e
  oscila com a janela — é medidor, não portão.
- **A perda é cauda longa, não router esquecido:** 46 txs perdidas, **32 seletores e 31 contratos
  distintos**; top-1 = 13% e top-5 ≈ 37% da perda. Não há um decoder a escrever que conserte isso.
- **Resolvidos na base pública (4byte):** `4d819a2a` = 1inch V6 `swap((uint8,address,…)[],…)`;
  `b858183f`/`09b81346` = Universal Router `exactInput`/`exactOutput((bytes,address,uint256,uint256))`;
  `6fadcf72` = `forward(address,bytes)`. **Não resolvidos:** `bb6904ae` (14.653 no run, o 2º maior do
  fluxo), `92057d6b`, `fd66ae06`, `bda5d1df`, `0ff1065c`, `09001f9a`… — formato EMPACOTADO de bot
  privado, com os endereços de token em bruto dentro da calldata (em `0x00000001`/`0x00000002` o
  primeiro campo é *command id*, não seletor). Endereços terminando em `484848`, `45000000` são a marca.
- **A perda importa:** ~57% das pools perdidas seguem tocando WBNB ou USDT — não é ruído de cauda
  distante, é fluxo que passa pelos nossos numerários.
- **O que a calldata carrega:** de 30 perdidas, **15 trazem TODOS os tokens** que a tx moveu, 11 trazem
  parte, **4 não trazem nenhum**. Leitor ESTRUTURAL (varrer janelas de 20 bytes e casar com tokens do
  catálogo, sem conhecer ABI) alcança as 15 + parte das 11; as 4 opacas **só simulando**.
- **A EVM responde, e em LOTE.** `eth_simulateV1` existe no nó público e aceita várias txs numa chamada:
  um lote de 4 devolveu, por call, os logs de `Swap` com a pool e os montantes — recall sem ABI, sem
  adivinhar caminho, reusando `Eventos`/`Catalogo` (a mesma via do bloco confirmado). O revert de quem
  não mais se aplica ao `latest` é esperado e é o próprio filtro.

**Decisão:** (1) **instrumentar** — o relatório passou a carregar `seletores_por_contrato`
(`seletor@endereço`), porque seletor sozinho não separa router conhecido de bot privado; (2) **leitor
estrutural** para a metade que carrega os tokens; (3) **simulação em lote** para a metade opaca. O
`eth_call` segue o oráculo de correção (P24), então projeção falsa custa detecção, nunca dinheiro.


## 13. O gás: da estimativa pela metade à calibração medida

A análise de gás tinha ficado pela metade, e não em silêncio: `CustoDeGas` carregava DUAS
implementações coladas (constantes `GAS_SWAP_V2`/`GAS_SWAP_V3` e `estimarGas` declarados duas vezes),
referenciava duas constantes que não existiam (`GAS_FLASH_LOAN`, `GAS_CALLDATA_POR_SALTO`), montava
`eth_estimateGas` com parâmetros que a EVM não aceita (sem `from`/`to`, `data` no lugar do objeto de
chamada), e o `Disparo` chamava duas vezes o nó por despacho e ainda subtraía o piso dele mesmo
(`piso − piso·2/3`, um bloco circular). **O projeto não compilava** (9 erros). Abaixo o que ficou e
as regras que ainda pedem medição.

**P29 — O gás é um TETO ESTRUTURAL calibrado, e quem mede o chute é a EVM.** O modelo é
`21k (tx) + 60k (executor) + 60k (empréstimo) + perna + calldata`, com a perna separada por tipo:
**75k V2 / 120k V3** são TETO, e a calldata entra por EIP-2028 (4 por byte zero, 16 por não-zero),
com teto de 160 bytes por perna quando os bytes ainda não existem (caminho da cauda, antes de
traduzir). As constantes saíram de swaps reais da BSC, medidos numa janela de ~60 blocos:
`eth_gasPrice`=0,05 gwei; swap de 2 hops V2 = ~117k de tx inteira (n=130, p90 ~189k); 3 hops ~214k;
`exactInputSingle` V3 = ~117k de mediana (n=14, p90 ~150k). O par antigo 150k/200k **dobrava** o gás
de uma rota de 3 pernas (600k) e inflava junto o piso e o teto de gasto por tx. *Por quê teto e não
mediana:* superestimar o piso só recusa fenda boa (perder oportunidade não custa gas); subestimar
faz entrar em fenda que não paga o próprio gas. *Como se prova:* `CustoDeGasTest` fixa a estrutura
por perna/tipo, o `gasDaCalldata` byte a byte e o piso como `gás × preço vigente × 3/2`.

**P30 — O `eth_estimateGas` vira o oráculo do gás no despacho, com `from`/`to`/`data` e em
`pending`.** É o único ponto que mede imposto, tick cruzado e slot frio de uma vez — três coisas que
nenhum teto analítico enxerga. O `from` é obrigatório porque o `Executor` só aceita o operador: sem
ele a estimativa reverteria por NaoAutorizado e devolveria vazio. **Uma RPC por despacho**, não duas
(a segunda, que existia, ainda recalculava o piso com a calldata que ela mesma ia substituir). O
número medido corrige as DUAS decisões: o LIMITE (estimativa + 20%) e o PISO — que é **rebaixado**
quando a EVM diz que o gás é menor, e **nunca erguido acima do piso analítico**. Rebaixar ABRE fenda
(é o ganho da medição); erguer exigiria lucro que o modelo não prometeu e fabricaria SemLucro.

**P31 — O `lucroMinimo` do contrato é `max(piso, lucro bruto − piso)`, não `lucro bruto − piso`
puro.** A conta antiga aceitava qualquer lucro acima de `lucro bruto − piso`; quando a fenda
encolhia até perto do piso, esse número virava quase zero e o contrato podia fechar uma arbitragem
que **não pagava o próprio gás** — a promessa do bundle ("INCLUÍDO ⟹ LUCROU") deixava de valer
exatamente no caso em que o piso existe para valer. Exigir só o piso, por outro lado, mataria a
tolerância que absorve o estado andar entre a estimativa e a inclusão. O máximo dos dois cumpre as
duas coisas: nunca abaixo do piso, e nunca mais apertado do que o lucro modelado menos a folga.
*Como se prova:* `RotaDoModeloTest` cobre os dois regimes (fenda com folga preserva a tolerância;
fenda fina cai no piso), e o `PortaoDePisoTest` prova que o portão morde dos dois lados.

**Regras em aberto (revisar com medição, não com opinião):**

- **A taxa do flash loan não está no modelo.** O contrato empresta via `swap` do primeiro par e
  devolve com a taxa; o lucro medido na cadeia é o bruto do ciclo MENOS essa taxa, que o
  `Oportunidade.lucro()` não conhece. Numa entrada de 1 BNB a 25 bps isso é ~0,0025 BNB — **sete
  vezes** o piso de ~0,00036 BNB. Consequência: com `lucroMinimo = piso` a rota fina pode reverter
  na cadeia por SemLucro mesmo estando certa no modelo. Reverter no relay não custa gas, mas é
  detecção perdida; o número a medir é quantas fendas morrem por aí.
- **`USDT_POR_BNB = 600` fixo** é o fallback quando a pool canônica não responde. Se o BNB subir
  muito, o piso em USDT fica baixo e entra arb fina demais; se cair, o piso fica alto e recusa
  fenda boa. O caminho bom (razão de reservas da canônica) já existe; falta medir se o fallback
  chega a ser usado com a canônica no catálogo.
**P36 — A gorjeta parte de ZERO: o preço vigente É o `eth_gasPrice`, sem tip fixo somado.** Havia
um 0,1 gwei fixo em cima do mercado: o piso cobrava 0,15 sobre um mercado de 0,05 (3x) e a tx
assinada pagava 0,1 onde a moda do bloco paga 0,05 (2x) — MEDIDO em recibos reais
(`effectiveGasPrice == maxPriorityFeePerGas` em 5 de 5 txs tipo-2, com `baseFeePerGas` = 0 em 12 de
12 blocos: na BSC a tx paga exatamente a gorjeta). A gorjeta sobe via `gorjeta()` SOMENTE quando
o bloco pedir (inclusão disputada); o placar de `abaixo_piso` e o resíduo da inclusão dizem
quando. O `maxFee` assinado é 2x o preço vigente — cobre subida sem pagar 3x o mercado parado.
*Como se prova:* `CustoDeGasTest.gorjetaSobeSobDemanda` (zero inicial, sobe, nulo/negativo voltam
a zero).
- **O portão do `Backrun` usa o teto, não a medição** (não há RPC por fenda no caminho em massa).
  Isso é deliberado: o teto grande erra para o lado de recusar. O número a acompanhar é `abaixo_piso`
  contra `recebidos` — se o teto barra demais, a resposta é calibrar, não pôr RPC no caminho quente.

## 14. O que o log pediu: quatro defeitos medidos, quatro correções

O log não se lê, se confronta. A corrida de 9,3 h (16.865 achados → 16.538 abaixo do piso → 320
deduplicados → 7 despachos, **todos** recusados) e a corrida viva do gás novo deram o diagnóstico
abaixo. Cada item nasceu de um número do log e de uma medida na cadeia, e cada um virou código com
teste.

| sintoma medido no log | causa | correção |
|---|---|---|
| 7 de 7 despachos mortos em `Pancake: OVERFLOW` | reserva V2 vinda de log NÃO-autenticado | **P32**: teto `uint112` é estado impossível → descarta e conta |
| 292 achados de 1,1e12 wei (1/75 do piso) ocupando o grafo | catálogo aceitava pool rasa; o piso é de VALOR, não de profundidade | **P33**: piso de liquidez por perna, no lado do numerário |
| nenhum `gasUsed` nosso em lugar nenhum | a estimativa nunca foi confrontada com a EVM | **P34**: recibo → placar de desvio (medido × estimado) |
| `ciclos_sem_v2_na_ponta` = 41% dos ciclos negativos | a SPFA devolvia UM ciclo; a ponta V3 matava o BLOCO | **P35**: segunda busca, riscando só a ponta que não empresta |

**P32 — Reserva V2 acima do `uint112` é log inventado, não estado: descarta e CONTA. Zero ENTRA.**
O par UniswapV2 guarda cada reserva num `uint112`; acima disso ele mesmo estoura com
`Pancake: OVERFLOW`. Logo o valor que estourou a cadeia **não veio de par nenhum** — veio de um
contrato alheio emitindo o topic0 do `Sync` com payload escolhido, porque log é dado
NÃO-AUTENTICADO e o `Sync` não diz quem pode emiti-lo. MEDIDO: os 7 despachos de uma corrida de 9 h
morreram todos em `OVERFLOW`, e a varredura dos `Sync` reais da BSC não achou reserva acima de 7,1e26
(0,00001% do teto) — nenhuma reserva real chega perto, então recusar não custa nada e a recusa é
exata. É o mesmo princípio que o V3 já tinha (`impossivel → IllegalArgumentException`), e o comentário
do catálogo diz por que ele fica: "é o único filtro que DUVIDA" — gás e retorno só sabem comparar, e o
estado inventado atravessava os dois.

**A recusa NÃO pega o ZERO, e isso foi decidido por teste que falhou.** A primeira versão recusava
`reserva == 0` junto ("zero é pool sem lastro"), e a suíte de rede quebrou em TRÊS lugares por causa
disso: `ConferenciaNaCadeiaTest` (a conta conferida não fechava), `CursorTest` (o catálogo aprendia
menos pares) e `TaxaSobSuspeitaTest` (a prova de taxa pegava chamadores de OUTRA pool). A cadeia
causal é uma só: quando a liquidez é RETIRADA o par emite `Sync(0,0)`, que é o fato mais FORTE que
existe sobre aquela pool — é assim que se fica sabendo que ela esvaziou. Recusá-lo deixava o catálogo
com a última reserva não-nula e a pool sem `conhece(pool)`, e daí o resto se contamina. O projeto
anterior já tinha pago por esse erro, com número: a regra `reserva > 0` de lá deixou a pool
`08bb0ff2…` guardando 22,1 WBNB e 307 milhões de tokens como FATO enquanto a cadeia devolvia 0 e 0 —
e o grafo via **19% a 24% de retorno em duas pernas**, o valor de uma pool que não existe mais. Fenda
inventada é pior que fenda nenhuma: a primeira custa um envio, a segunda contamina o portão. Quem
recusa pool sem lastro é a CURVA, que não se constrói com `x = 0`, e ela recusa no lugar certo
(`Cotador` não cota, `ligarV2` não liga). *Como se prova:* `CatalogoTest` (5 testes) — reserva boa
entra e devolve o estado anterior; acima do teto é descartada **e contada**; zero **entra e apaga o
estado velho**; payload curto não vira `Sync` de dois `uint112` (a palavra ausente lida como ZERO é o
mesmo erro por outra porta).

**P33 — Piso de liquidez é no lado do NUMERÁRIO, e a profundidade que importa está nas PONTAS.**
Um piso de poeira não poda a poeira. MEDIDO no log vivo: dos 410 achados de uma janela curta, 292
tinham raiz WBNB e **todos** estavam abaixo do piso — com mediana de 1,1e12 wei, isto é 1/75 do piso
e US$ 0,0007. Isso não é fenda fina: é pool rasa produzindo micro-ciclo que nunca fecha com lucro, e
que paga o custo de estar no grafo todo bloco. O piso é aplicado só onde o numerário está (WBNB e
USDT, 18 casas na BSC, endereços canônicos), porque é ele que precifica o lucro e o empréstimo. Pool
**sem** numerário passa de propósito: é perna do miolo, e a profundidade que decide está nas pontas.
V3 também passa — sem reservas V2, a própria curva a filtra. O valor é BAIXO de propósito (~US$ 30 de
profundidade no numerário): pool mid-depth é justo onde um swap grande abre a fenda N+1 suculenta, e
podá-la seria perder o que mais rende. *Como se prova:* `precifica` devolve falso para a rasa com
numerário, e o funil da cauda mostra o efeito no `pools_no_grafo`. *Em aberto:* o valor exato é ponto
de operação, não verdade — subir se a poeira voltar ao topo do funil, descer se o `pools_no_grafo`
cair junto com os achados.

**P34 — O gás medido fecha o laço: recibo → `gasUsed`, confrontado com a estimativa que decidiu o
envio.** A estimativa de gás era, até aqui, calibrada por medição de TERCEIROS (swaps de router da
BSC) e pelo `eth_estimateGas` — mas nenhuma execução NOSSA tinha sido comparada com o gás que de fato
se gastou. Sem esse número, "estimativa precisa" é argumento; com ele, é conta. O `MedidorDeGas` roda
em thread própria, **fora do caminho de envio** (o `Disparo` não espera nada), busca o recibo em até 5
tentativas de 3 s (~4 blocos da BSC) e registra `medido`, `estimado` e `sucesso`. Dois detalhes que
valem o código: (1) recibo ausente é `sem_recibo`, **não** revert — bundle não incluído e tx pendente
não são falha e não podem contaminar o placar; (2) o gás de uma tx que REVERTEU também foi pago (só
acontece no envio público, onde revert paga) e por isso também ensina, mas é contado à parte. O placar
publica mediana de medido/estimado e o **PIOR** desvio, porque o sinal importa: desvio positivo é
gasto acima do estimado, o lado que estoura o limite; negativo é estimativa folgada, o lado que
recusa fenda boa. *Como se prova:* `MedidorDeGasTest` fixa a conta sem rede (medianas, pior desvio e
`revertidas`), e `ReciboDoGasTest` prova a leitura do recibo — é onde um `status` lido ao contrário
transforma revert em sucesso, e onde `result: null` de tx ainda não incluída viraria "reverteu".

**P35 — Quando o ciclo que a SPFA achou não pode ser ENTRADO, ela busca de novo riscando só a ponta
V3.** A SPFA devolve **um** ciclo qualquer — e "o primeiro que ela achou" não é "o melhor" nem, pior,
"o entrável". MEDIDO: 41% dos ciclos negativos de uma corrida de 9 h (`ciclos_sem_v2_na_ponta` = 161 de
394) foram recusados porque a perna que entra no numerário era V3, e a saída seria o contrato reverter
com `EmprestimoNaoV2` (medido no `run-armado`: 1 dos 7 envios morreu exatamente assim). Cada recusa
dessas jogava o **bloco inteiro** fora sem saber se havia outro ciclo ali. A correção não filtra o
grafo (o V3 no miolo soma, e é onde mora a arb que o mercado vence): ela risca AQUELA aresta — só a
ponta, não o miolo — e procura outra vez, dentro da mesma chamada. E mede as duas coisas separadas,
porque elas pedem ações opostas: `ciclos_sem_v2_na_ponta` (nenhuma busca achou alternativa: problema de
ENTRADA) e `ciclos_repescados` (a segunda busca achou: bloco recuperado). Zero persistente em
`repescados` diz que a segunda busca pode sair; acima de zero, era cobertura sendo perdida. *Em aberto,
e é decisão de contrato, não de código:* emprestar em V3 (a segunda busca é paliativo, não cura) ou
escolher a raiz de modo que a ponta seja V2 por construção. *Como se prova:* `GrafoDePrecosTest` tem o
bloco com DOIS ciclos negativos — um que só entra por V3 e outro que entra por V2: a rota devolvida tem
de começar numa V2, com `repescados` = 1 e `sem_v2_na_ponta` = 0, e o teste de recusa pura (só o ciclo
de V3) mantém o contador antigo em 1.

O que estes quatro itens NÃO resolvem, e continua em aberto no §13: a **taxa do flash loan**, que
segue fora do modelo de lucro e é a única hipótese capaz de derrubar uma rota que o modelo aprovou.


