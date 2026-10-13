# Operar na cabeça

O item existe para não repetir as feridas de `premissas.md`: frio e quente separados, cache
anelar, anúncio atrasado atrás do catch-up, e sincronização que morre quando não lê nada.
Tudo aqui foi escrito DEPOIS de ler o que o projeto anterior fez com a cabeça — e quase
toda decisão abaixo é uma dessas feridas virada regra.

A pergunta que decidiu o desenho continua valendo: *existe diferença entre o bloco 123
buscado e o bloco 123 recebido por anúncio?* Não. Então o cursor não sabe, e não pode saber,
de onde veio o bloco que está aplicando.

---

## (a) O cursor que não para

`Cursor` agora tem dois modos sobre o MESMO avanço:

- `percorrer(dePartida, quantos, porLote)` — anda uma faixa e termina. É instrumento de
  medição, e continua desistindo depois de `sincronizacao.parada.segundos` sem progresso.
- `seguir(dePartida, cabeca, porLote, leque)` — persegue a cabeça e **nunca termina sozinho**.

A ferida que o segundo modo mata, lida em `NodeService.sincronizar` do old: com `lidos == 0`
a thread de sincronização dava `return`, e quem a reacendia era o anúncio de bloco novo — que
chega para a cabeça, não para o cursor. Uma rodada ruim numa janela sem anúncio congelava o
frio para sempre. Aqui "nada montado" é motivo para esperar dez milissegundos, nunca para sair.

A invariante não mudou: avança só encadeado. Atrasado sim, furado nunca.

### Reorganização

O old dizia no próprio código: *"não há tratamento de reorg em lugar nenhum deste projeto"*.
Ele se protegia gravando só o que estivesse `PROFUNDIDADE = 3` blocos abaixo da cabeça — e
com isso a decisão da via rápida era **sempre** recusada, porque o topo nunca está confirmado.
Medido lá: recibo buscado em 276 ms, decisão em 4.281 ms.

Aqui, pai diferente do processado **recua** `sincronizacao.reorg.passo` blocos até uma âncora
cujo hash o cursor guardou, descarta a montagem inteira (o bloco montado acima da âncora pode
ser justamente o da cadeia órfã) e deixa a frente repedir dali. Recuos seguidos andam mais
fundo; passar de `sincronizacao.reorg.janela = 64` blocos para o cursor com log severo —
reorganização desse tamanho na BSC, que tem finalidade rápida, é par em outra cadeia.

**Por que reaplicar conserta o estado**: os eventos que movem preço carregam estado
ABSOLUTO. `Sync` traz a reserva inteira; `Swap` do V3 traz preço, liquidez e tick. Reaplicar
a cadeia certa sobrescreve o que o bloco órfão escreveu.

**O que reaplicar NÃO conserta**, e fica declarado: `Mint`/`Burn` do V3 são deltas. Uma pool
mexida *só* por liquidez dentro do trecho órfão fica com o delta dele até o próximo `Swap`. O
conserto exige `Catalogo.esquecer(pool)` — ver *O que faltou em `catalogo/`*.

Medido nas duas fases: **zero reorganizações**. O mecanismo está coberto por construção, não
por observação.

---

## (b) O anúncio, e a latência da cabeça

`Lotacao` repassa cada `NewBlockHashes` a um ouvinte recebido em `iniciar` — o mesmo corte de
ciclo da `PortaUdp`: quem lê a conversa é quem vê o anúncio chegar, e ele não pode segurá-lo
até alguém perguntar.

`Cabeca` registra o que a rede anuncia: a altura-alvo, que **só anda por anúncio**, e o
instante do primeiro anúncio de cada altura. Um anúncio mais de
`sincronizacao.cabeca.salto.maximo = 64` blocos à frente do alvo é ignorado: sem esse teto, um
par mentindo mandaria o cursor pedir blocos que não existem, e cada pedido vazio desanima um
par bom.

### Uma montagem só, e por que isso não repete a ferida do anúncio atrasado

Ferida nº 3 de `premissas.md`: o anúncio entrava na mesma fila do catch-up frio, e um item com
prazo de 450 ms esperava atrás de milhares com prazo de horas. A resposta de lá foi *duas
faixas separadas na porta de entrada*.

Aqui a frente é **uma**, e a ferida não volta por um motivo de regra de negócio, não de
engenharia: **só se opera na cabeça**. Enquanto o cursor está atrasado não existe decisão a
atrasar; quando ele chega na cabeça, a fila da frente só contém cabeça. As duas classes de
prazo nunca coexistem na fila no instante em que o prazo importa.

E o bloco empurrado entra pela MESMA montagem do cursor. O old tinha a `ViaDeAnuncio`, uma
segunda via que buscava o recibo da cabeça *sem mover o cursor* — e por isso precisou de
`CorrenteDaCabeca` para saber onde a cabeça tinha buracos (51 de 194 blocos que ninguém
serviu). Via única, sem buraco possível.

### Leque: corrida, não fila

A faixa de UM bloco é a da cabeça — na cabeça o alvo anda um por anúncio — e só ela sai em
`leque` cópias para pares diferentes. A montagem é indexada por altura, então a segunda cópia
que chega sobrescreve a primeira com o mesmo bloco. É a doutrina que o old já tinha escrito e
não aplicava no cursor: *o hash do bloco é o mesmo para todo mundo; esperar um de cada vez é
escolher a latência do mais lento.*

A faixa de 16 do catch-up **não** sai em leque: seria pagar a banda quatro vezes por blocos que
nenhum anúncio está esperando.

### Medido

Latência = do primeiro anúncio de uma altura até o recibo dela aplicado. Altura aplicada sem
anúncio próprio não vira amostra. Cada fase segue a cabeça por 90 s na BSC viva:

| | leque 1 | leque 4 |
|---|---|---|
| blocos da cabeça | 204 | 203 |
| mediana | **18.978 ms** | **460 ms** |
| p90 | 36.916 ms | 1.327 ms |
| máximo | 45.478 ms | 3.423 ms |
| reatribuídas | 37 — prazo 13, vazio 6, sessão 18 | 135 — prazo 13, **vazio 106**, sessão 16 |
| entregues | 207 | 781 |

**Com leque 4 a cabeça cabe dentro do bloco**: mediana de 460 ms contra 750 ms de bloco,
41 vezes melhor que o leque 1.

O leque 1 é bimodal — o cursor chegava a ficar 83 blocos atrás e depois voltava a 5 — e a
causa está nos motivos: **13 prazos estourados de 15 s**. Uma cópia só, num par que não
responde, trava o cursor inteiro, porque ele só avança encadeado. É a ferida nº 2 (vazão
refém do pior par) entrando pela cabeça.

O custo do leque 4 também está no número: 106 "cabeçalho vazio". São pares que ainda não
receberam o bloco recém-anunciado — propagação — e cada um é desanimado por 10 s. Não impede a
mediana de 460 ms, mas diz onde o próximo ganho mora.

### O que a medição recomenda e ainda não foi feito

**O prazo de pedido é um só para tudo**: `rlpx.pedido.timeout.ms = 15000`, igual para a faixa
de 16 blocos do catch-up e para o bloco único da cabeça. O old chegou a 2.500 ms para recibo de
um bloco depois de ver 900 ms falhar em 1.824 corridas. Prazo por tamanho de pedido encurtaria
a cauda do leque 4 e tiraria os 15 s do leque 1 — e exige `Sessao.pedir` com prazo por
chamada.

---

## (c) O platô do pool, e a decisão sobre o `Status`

A hipótese em aberto era: o pool para em 8–11 de 25 porque o `Status` anuncia genesis com
dificuldade zero e parecemos leech. A instrução foi medir antes de mentir. A `Lotacao` passou a
contar **por que** cada conversa cai, separado por fase.

### Medido, na mesma corrida (~5,5 min)

| queda | vezes |
|---|---|
| abertura · inalcançável | 318 |
| abertura · derrubou: peers demais | 263 |
| abertura · conexão caiu | 184 |
| abertura · outra cadeia no Status | 69 |
| abertura · derrubou: peer inútil | 68 |
| abertura · derrubou: erro de subprotocolo | 13 |
| abertura · Snappy produziu 0 bytes | 6 |
| conversa · conexão caiu | 29 |
| conversa · derrubou: peers demais | 18 |
| conversa · derrubou: peer inútil | 2 |
| **mudez** | **0** |

Códigos que chegaram e não tratamos: `NewPooledTransactionHashes` 12.463, `Transactions` 995,
`NewBlock` 71 — e **`GetBlockHeaders` (código 19): zero.**

### O que isso diz

**O platô é de ABERTURA, não de conversa.** ~922 aberturas falharam contra 51 quedas depois
de aberta, e nenhuma por mudez: conversa aberta fica aberta.

**Pelo menos 83% das falhas de abertura independem do `Status`.** Inalcançável e conexão caída
são rede; "peers demais" o geth decide na camada p2p, antes de ver qualquer mensagem eth.

**E nenhum par nos desafiou.** O old documentou o mecanismo de reprovação de leech: o par
confere a cabeça anunciada pedindo o cabeçalho dela de volta, e lista vazia é
`Disconnect(peer inútil)` (`ArmazemCabecalhos` do old). Com zero `GetBlockHeaders` chegando,
esse desafio não está acontecendo.

O que sobra para o `Status` explicar são os 68 "peer inútil" — 7% das falhas de abertura. A
primeira contagem jogava fora o PASSO do aperto em que o par derrubou; ela agora o guarda, e é
o passo que decide: "peer inútil" **antes do `Hello`** não pode ser sobre parecermos leech,
porque o par ainda nem viu o nosso `Status`.

### Decisão: o `Status` não muda

A medição não aponta para ele, e a instrução foi mudar só se apontasse.

Se um dia apontar, existe valor honesto — e existe uma armadilha, as duas lidas no old:

- **Dificuldade total observada, não calculada.** `DificuldadeDaRede` (commit `f315370`)
  aprende o par (bloco, dificuldade acumulada) do `NewBlock` — único lugar do protocolo que traz
  os dois juntos — e transporta até a nossa cabeça somando **1 por bloco**, que é o piso (na
  Parlia o bloco vale 1 ou 2). Errar por baixo custa parecer atrás; errar por cima é ser
  escolhido como fonte de uma cadeia que não guardamos. Chegam ~13 `NewBlock` por minuto: há
  observação disponível.
- **Anunciar cabeça obriga a servir o cabeçalho dela.** Sem responder o `GetBlockHeaders` do
  desafio, anunciar cabeça real troca "leech" por "peer inútil" certo.

---

## (d) Persistência

### Taxa provada e contradição: persistem

`repository/TaxasRepository` e a tabela `taxas` em `esquema.sql`.

Taxa de pool é imutável, e provar uma custa esperar a pool ser usada com saída máxima — em 256
blocos, 45% das pools chegam a isso. Reaprender a cada start é jogar fora exatamente o trabalho
que mais demora.

A ferida lida em `PoolsRepository` do old: taxa gravada por procedência "medida", com contagem
de amostras, e a on-chain sobrescrevendo a medida — foi assim que a `7a3eb6e7` ficou com 5 bps
e 94.404 amostras cobrando 1000. Aqui as regras moram no **banco**, onde não dependem de quem
escreve o próximo `INSERT`:

- `taxa % 100 = 0` — taxa quebrada não entra, nem se alguém tentar;
- contraditória não tem taxa;
- **contradição é permanente**: gravar uma segunda taxa diferente não substitui, contradiz; e
  regravar a original depois não reabilita. Isso é decidido num `on conflict` só, para que duas
  threads gravando taxas diferentes ao mesmo tempo não escapem da regra.

`TaxasRepositoryTest` prova as quatro regras contra o Postgres de verdade — inclusive que o
banco **recusa** 2501.

### Estado de reserva: não persiste — e o motivo

`Catalogo` promete que todo valor dele vale na cabeça **porque a cobertura do cursor é
contínua**. Gravar reservas sem gravar junto a âncora do cursor (altura e hash) quebra essa
promessa no restart: haveria reservas de uma altura sem prova de que nada mudou entre ela e a
cabeça.

O correto, se valer a pena, é gravar **reservas + altura + hash do cursor na mesma transação**, e
no start retomar o cursor daquela âncora — a continuidade então vale de novo por construção.
Isso exige ler e semear o catálogo inteiro, que não tem API para isso.

---

## (e) O node de produção

`Main` continua sendo a raiz de composição — a decisão anterior de não recriar
`Orquestrador → Node → serviços` como indireção sem dono continua valendo. O que faltava não
era camada, era **responsabilidade**, e ela virou uma classe:

`sincronizacao/Sincronizacao` — *mantém o cursor seguindo a cabeça, numa thread que é dela.*
Três decisões que não cabem em mais ninguém:

- **quando começar**: só depois do primeiro anúncio — antes dele não existe cabeça;
- **de onde**: `sincronizacao.partida.recuo = 256` blocos atrás, para as taxas começarem a se
  provar antes de o cursor chegar na cabeça;
- **em que ordem parar**: o cursor antes da frente, senão a frente fechada deixa o cursor
  esperando bloco que ninguém mais traz.

`Main` agora liga: banco, pares, identidade, porta, descoberta, lotação com o ouvinte da
cabeça, catálogo, taxas, frente, cursor e sincronização — e para na ordem inversa da
dependência, com o banco por último.

### Orçamento de threads, declarado

| dono | threads | teto |
|---|---|---|
| `Descoberta` | 1 de rodada | fixo |
| `Lotacao` | 1 de rodada + `rlpx.tentativas.simultaneas` de abertura + `rlpx.sessoes.ativas` leitoras | 1 + 8 + 25 |
| `Frente` | `sincronizacao.pedidos.em.voo` | 8 |
| `Sincronizacao` | 1 do cursor | 1 |
| `PortaUdp` | 1 de recepção | 1 |

### O que o Main ainda não liga

- **O arbitrador.** Não existe classe de arbitrador de produção. O ponto de inscrição está
  comentado no `Main`: é o mesmo consumidor de `Transicao` que já alimenta `Taxas`, e só recebe o
  que o cursor aplicou. Pela P7 ele recebe o que a **cabeça** produz — e o `Vigia` precisa de um
  índice de ciclos remontado conforme novas taxas se provam, o que é trabalho de `catalogo/` e
  `nucleo/`.
- **A persistência de taxa no laço.** O repositório existe e está provado; ligá-lo ao `Taxas`
  exige os métodos abaixo.

---

## O que faltou em `catalogo/`

Não criei — está fora dos meus limites. É exatamente isto:

1. **`Taxas.aoProvar(BiConsumer<byte[], Integer>)` e `Taxas.aoContradizer(Consumer<byte[]>)`**,
   ou equivalente. Para persistir no instante em que a taxa se prova. Varrer a `Taxas` de fora
   exigiria enumerar o mapa interno; recalcular a prova de fora duplicaria a regra, que é o veto.
2. **`Taxas.semear(Map<String, Integer> provadas, Set<String> contraditorias)`.** Para carregar
   no start o que `TaxasRepository.provadas()` e `.contraditorias()` devolvem. Contraditória
   semeada tem de continuar bloqueando `provar`.
3. **`Catalogo.esquecer(byte[] pool)`.** Para o recuo de reorganização descartar pool mexida só
   por `Mint`/`Burn` dentro do trecho órfão, em vez de deixá-la com o delta errado.

---

## Feridas do old achadas neste item

- **`DificuldadeDaRede.em(cabeca)` anuncia por CIMA quando a nossa cabeça está atrás da
  observação.** O método devolve a dificuldade observada sem descontar nada quando
  `cabeca <= origem` — isto é, a dificuldade de um bloco MAIS ALTO que o nosso. É exatamente o
  erro que o próprio comentário da classe diz que não pode acontecer ("por baixo, nunca por
  cima"). Quem reaproveitar a ideia tem de descontar 2 por bloco para baixo.
- **O par desafia a cabeça anunciada** pedindo o cabeçalho dela; lista vazia é "peer inútil"
  (`ArmazemCabecalhos`). Anunciar cabeça e servir o cabeçalho são uma decisão só.
- **`0x10` não é prova de outra rede.** O old queimou 1.704 pares como `OUTRA_REDE` — 188 na
  porta 30311, quase certamente BSC — tratando o catch-all do subprotocolo como veredicto. A
  `Lotacao` daqui penaliza curto e sem persistir; continua certo.
- **O geth da BSC prefere eth/70**, e servir recibo em eth/68 obriga o par a carregar também o
  corpo de cada bloco (commit `ebad391`). O node novo fala só eth/68: todo recibo que pedimos custa
  ao par o caminho com o dobro de I/O. É candidato direto a parte do "cabeçalho vazio" e dos
  prazos.

---

# Continuações (f..i)

As medições abaixo são corridas de ~5 min na BSC viva, uma por configuração. **O ruído entre
corridas é grande** — sem mudar nada, "cabeçalho vazio" do leque 4 foi de 106 para 24 entre duas
corridas —, então o que se afirma é o que um motivo específico explica, não a diferença de total.

## (f) Prazo por pedido

`Sessao.pedir(pedido, resposta, prazoMs)`: o prazo é de quem pede, porque só quem pede sabe o
tamanho. A `Frente` dimensiona com `rlpx.prazo.base.ms = 2500` mais
`rlpx.prazo.por.bloco.ms = 250` por bloco além do primeiro — a base é a do old, que chegou a
2.500 ms para recibo de um bloco depois de ver 900 ms falharem em 1.824 corridas. O
`rlpx.pedido.timeout.ms = 15000` fica só para o aperto de mão.

| leque 4 | antes (duas corridas) | depois |
|---|---|---|
| mediana | 460 · 346 ms | 363 ms |
| **p90** | 1.327 · 1.192 ms | **540 ms** |
| máximo | 3.423 · 5.399 ms | 4.169 ms |
| prazo estourado | 13 | 71 |

| leque 1 | antes | depois |
|---|---|---|
| mediana | 18.978 ms | **684 ms** |
| p90 | 36.916 ms | 4.493 ms |

O p90 do leque 4 caiu à metade e o leque 1 deixou de travar em 15 s. Os prazos estourados
subiram de 13 para 71, e isso é o mecanismo funcionando: o pedido que não volta em 2,5 s é
reatribuído em vez de segurar o cursor.

## (g) O "defeito do Snappy" não era do Snappy

"Snappy produziu 0 bytes, anunciou 449 / 705 / 1473". Os três números são o varint que o
descompressor lê de um **Disconnect em claro**:

```
01 c1 03   →  c1 03 lido como varint = 449   →  Disconnect(peer inútil)
01 c1 05   →  = 705                          →  Disconnect(já conectado)
01 c1 0b   →  = 1473                         →  Disconnect(timeout de leitura)
```

O par recusa logo após o aperto e escreve o motivo antes de ligar a compressão do lado dele. A
queda não era perdida por bug de descompressão: ela **perdia o próprio motivo**.

Conserto em `Rlpx.ler`: só para `Disconnect`, e só depois de o Snappy recusar, o corpo é lido
em claro. Não há ambiguidade possível — corpo RLP em claro começa em `0xc0` ou acima, o que dá
varint de pelo menos 128 com dois bytes de entrada, e o Snappy sempre recusa. Qualquer outra
mensagem em claro continua recusada.

`rede/DisconnectEmClaroTest` prova as duas metades: esses bytes reproduzem **exatamente** as
três mensagens de erro medidas, e agora viram `Disconnect` com o motivo certo.

Na corrida seguinte: **zero** falhas de Snappy, e o motivo recuperado apareceu no mapa
("derrubou antes do Status: já conectado").

## (h) Desanimar menos a propagação: medido, e NÃO entrou

A `Frente` agora separa "cabeçalho vazio" (altura abaixo da maior já entregue: par atrasado) de
"cabeçalho vazio na fronteira" (acima dela: bloco ainda em propagação). A separação funciona —
53 dos 58 vazios do leque 4 eram de fronteira.

Testei desanimar a propagação por 750 ms em vez de 10 s:

| leque 4 | desânimo 10 s | desânimo 750 ms na fronteira |
|---|---|---|
| mediana | 363 ms | 498 ms |
| **p90** | **540 ms** | **9.923 ms** |
| máximo | 4.169 ms | 18.869 ms |

Piorou, e o mecanismo é plausível: o desânimo longo empurra o pedido para quem **já** recebeu o
bloco; o curto devolve à roda justamente quem ainda não recebeu. Uma corrida só não fecha a
questão, mas a instrução era mudar só com medição a favor — então o comportamento voltou ao de
10 s e **ficou só a separação do motivo**, que é diagnóstico sem custo.

## (i) eth/70: não vale agora

Lido no fonte atual do bnb-chain/bsc (`eth/protocols/eth/handlers.go` e `handshake.go`):

- **A premissa do old não se sustenta mais.** O commit `ebad391` dizia que em eth/68 servir
  recibo obriga o par a carregar o corpo do bloco, e que no eth/70 o recibo "sai direto do
  disco". No fonte atual, `serviceGetReceiptsQuery70` **também** chama `GetBodyRLP(hash)` e
  converte. O ganho de I/O que justificava a mudança não existe.
- **O Status do eth/70 exigiria afirmar o que não temos.** Ele leva `EarliestBlock,
  LatestBlock, LatestBlockHash` — a faixa que NÓS servimos — e o par valida
  `EarliestBlock <= LatestBlock` e `LatestBlockHash != 0`. Não servimos faixa nenhuma: declarar
  uma é a mesma mentira de protocolo que se recusou no `Status` do eth/68.
- **O que exige, se um dia valer**: Hello com eth/70 e eth/68; Status nas duas formas (o eth/70
  tirou o `melhorBloco` do meio); **não** trocar `UpgradeStatus` no 70; `GetReceipts` com índice
  de retomada; `Receipts` com o booleano de incompleto; `BlockRangeUpdate` (0x11).
- **O que valeria de verdade**, e é barato de ter por outro caminho: a faixa que o PAR declara
  no Status dele diria quem não tem o bloco antes de pedir — o que ataca exatamente os
  "cabeçalho vazio". Mas isso é leitura do Status alheio, e só existe no eth/70.

Veredito: não implementei. O motivo principal (I/O) está refutado pelo fonte, e o motivo
secundário exige anunciar faixa que não servimos.

## O que as quedas dizem agora, com o passo do aperto

Com o passo preservado, três corridas:

| abertura | corrida 2 | corrida (f+g) | corrida (h) |
|---|---|---|---|
| derrubou antes do Hello: peers demais | 168 | 114 | 228 |
| derrubou antes do Status: **peer inútil** | 96 | 103 | 128 |
| derrubou antes do Status: peers demais | 82 | 59 | 123 |
| derrubou antes do Status: erro de subprotocolo | 40 | 13 | 18 |

**"Peer inútil" antes do Status é grande e estável — 96 a 128 por corrida.** O par já viu o nosso
`Status` quando derruba ali, então a pergunta do `Status` volta, mas a resposta continua sendo
não mexer nele:

- no fonte do `eth/handler.go` da BSC, **cabeça e dificuldade anunciadas não entram na admissão**
  — só contagem de pares, pares por IP e a exigência de extensões de protocolo (`snap`/`bsc`);
- `DiscUselessPeer` ali só sai de `removePeer`, depois do registro.

A hipótese que sobra, e que eu não testei: **o par exige a extensão `bsc`** (ou `snap`) e nós
negociamos só `eth`. É fora do meu escopo e pede leitura do `runBscExtension`/`runSnapExtension`
antes de qualquer código — é o próximo número a medir, não a próxima mudança.
