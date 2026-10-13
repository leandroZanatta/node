# Confiar no dado de entrada

A sessão anterior terminou com a aritmética provada contra os contratos e cinco defeitos
achados, **todos no dado de entrada e nenhum na conta**. Este documento é o item seguinte:
fazer a entrada merecer a conta. Cada seção diz o que faz, por que assim, o que já custou e o
número que provou.

Antes de escrever uma linha, leu-se o projeto anterior. Três das feridas abaixo ele já tinha
fechado, e a migração reabriu — o que por si só é o achado mais barato deste item.

---

## 1. O 1,41e48 não era fork V3: era a palavra do Mint

O dossiê tinha mostrado uma pool USDT/WBNB com `liquidez = 1,41e48`, dez ordens de grandeza
acima do teto do `uint128`, e a leitura da época foi "fork V3 com outro arranjo de palavras".
Estava errada.

Em hexadecimal os dois valores medidos eram:

```
1410567031761411235199949998352313826428734045462 = 0xf71403673ab04f16bbd05524ab650f4660127d16
 705283515880705617599975540064568320250832694930 = 0x7b8a01b39d58278b5de89d1bf44c70b6870b2e92
```

**160 bits exatos, os dois. São endereços.** E a assinatura explica de onde vieram:

```
Mint(address sender, address indexed owner, int24 indexed tickLower,
     int24 indexed tickUpper, uint128 amount, uint256 amount0, uint256 amount1)
Burn(address indexed owner, int24 indexed tickLower, int24 indexed tickUpper,
     uint128 amount, uint256 amount0, uint256 amount1)
```

No `Mint` o `sender` **não é indexado**, então ocupa a palavra 0 dos dados e o `amount` vai
para a palavra 1. No `Burn` o dono é indexado e o `amount` é a palavra 0. O `Cursor` lia a
palavra 0 dos dois — e todo Mint somava um endereço à liquidez. O preço e o tick fechavam
entre si porque o `Swap` estava sendo lido certo; quem estragava a pool era o Mint seguinte.

O projeto anterior tinha isso escrito, com as mesmas palavras, em `EventoDex.liquidezV3`:
*"o `sender` não indexado empurra o `amount` uma palavra adiante no Mint"*.

**O que mudou.** A posição do `amount` sai de quem chama e passa a morar em
`Eventos.liquidezMovida`, e `Catalogo.aplicarLiquidezV3(log, altura)` decodifica o próprio
evento. A regra que custou o maior falso positivo do projeto estava espalhada no chamador;
agora existe num lugar só.

**Consequência que não se via.** Com a guarda de `uint128` do `EstadoV3`, todo Mint dentro da
faixa corrente fazia a liquidez estourar, e a pool era **removida do catálogo**. Ou seja: a
guarda impedia o lucro fantasma, mas ao custo de apagar do grafo justamente as pools V3 que
recebem liquidez — que são as vivas.

---

## 2. A cicatriz da 7a3eb6e7 estava em outra unidade

A cicatriz dizia: *"a pool 7a3eb6e7 estava gravada com 5 bps e 94.404 amostras, e cobra
1000"*. O node novo prova **500** para ela, e isso parecia repetir o erro.

Não repete, e para ver isso bastou abrir a `SondaDeCurva` do anterior:

```java
BigInteger comTaxa = entra.multiply(BigInteger.valueOf(10_000L - bps));
```

O anterior media em **base 10.000**. "5 bps" é 500 milionésimos — o mesmo número do node
novo. E "cobra 1000" é **1.000 bps: dez por cento**, numa USDT/WBNB de meio milhão de dólares.
O teste do anterior confirma a leitura: *"o que pedíamos acreditando em 5 bps era 10% a mais do
que a pool entrega"*.

Então são duas afirmações incompatíveis para o mesmo par, e com procedências diferentes:

| quem | como mediu | quanto |
|---|---|---|
| inferência do anterior | 94.404 trades de terceiros | 5 bps |
| recuperação exata do novo | troca que fecha exatamente | 5 bps |
| `SondaDeCurva` do anterior | `eth_call` com saldo forjado, chamando `swap` direto | 10% |

As duas primeiras observam TERCEIROS, que passam por roteador. A terceira simulava o que o
executor do anterior fazia: chamar o par direto. É a mesma forma do buraco que o anterior
achou para imposto de token — o BlackSwan media zero por log e cobrava 1300 bps de quem não
passa por roteador.

O juiz é o invariante K: quem chama escolhe a saída e o par só confere, então **a saída
aceita limita por cima a taxa cobrada daquele chamador**. O `TaxaSobSuspeitaTest` coleta as
trocas da pool com estado anterior da mesma transação e imprime, por chamador, o maior degrau
de taxa ainda compatível com a saída aceita.

**MEDIDO**, 768 blocos, 33 trocas com estado anterior da mesma transação:

```
por chamador: trocas · menor teto · taxas exatas
0x5260f8edb4c383cd69ceb8afc042e290c1ff490a ·  5 · 500 · {500=5}
0x6f1b659dc19ac4f6c65c261b96e622bb2a756efa · 28 · 500 · {500=28}
```

**Todas as 33 fecham exatamente em 500, nas duas direções, e o teto compatível é 500 em
todas** — o K aceitou o máximo de uma pool de 5 bps, e 600 já daria menos que a saída aceita.
Para esses chamadores a pool cobra 5 bps, sem margem.

E o número que decide não é o 500: é o **dois**. A USDT/WBNB mais arbitrável da BSC negociou
33 vezes em 768 blocos por dois chamadores, nenhum deles o roteador da PancakeSwap, e nenhum
robô chamando o par direto — que é o que qualquer arbitrador faz, e o que o executor do
anterior fazia. Uma pool de 5 bps nesse par estaria cheia de robôs.

**Replicado numa segunda janela**, 768 blocos independentes: 15 trocas, os mesmos dois
chamadores (11 e 4), todas em 500 com teto 500.

As três medidas só são verdadeiras juntas se **a taxa depender de quem chama**: 5 bps para os
chamadores habilitados, e mais — a sonda mediu 10% — para quem vai direto ao par. Então as
duas procedências estavam certas, cada uma para o seu chamador, e o erro da cicatriz foi a
frase *"gravada com 5 e cobra 1000"*, que tratou uma das medidas como engano.

**O que isso muda no node.** A prova por log vale para o chamador que a produziu, e o nosso
executor não será nenhum dos dois. Por log, taxa por chamador só aparece quando dois
chamadores DISCORDAM — e é aí que a `Taxas` apaga a pool por contradição. Por isso ela passou a
guardar quem provou cada taxa: a evidência sobrevive à pool ser apagada. Pool em que só
chamadores habilitados negociam, como esta, nunca discorda, e **não há como vê-la por log**. O
fechamento é do item de execução: simular a nossa própria chamada antes de enviar.

---

## 3. O node estava cego para a PancakeSwap V3

A PancakeSwap V3 emite `Swap` com duas palavras de taxa de protocolo no fim:

```
Uniswap V3 / Algebra   Swap(address,address,int256,int256,uint160,uint128,int24)
PancakeSwap V3         Swap(address,address,int256,int256,uint160,uint128,int24,uint128,uint128)
```

Assinatura diferente é `topic0` diferente, e o node novo só escutava a primeira. **A maior V3
da BSC não existia para o catálogo** — sem erro, sem log, sem contador: pool que nunca emite o
evento que se escuta simplesmente nunca aparece.

O anterior tinha as duas (`EventoDex.SWAP_V3_PANCAKE`), com o comentário *"quem só escutasse a
forma da Uniswap ficaria cego para a maior V3 da BSC, sem erro nenhum aparecendo"*. A migração
trouxe uma.

**MEDIDO**, duas corridas de 768 blocos, antes e depois de reconhecer a assinatura:

```
                          sem Pancake    com Pancake
pools V3 no catálogo           266            682
trocas V3 que provam taxa   83 + 422     403 + 3.759   (provada + confirmada)
taxas provadas              670 (38%)      902 (48%)
```

**Duas vezes e meia as pools V3, e nove vezes as provas de taxa V3.** Janelas diferentes não
são comparação perfeita, mas nenhuma variação de janela explica um fator de nove — e a
cobertura passou de 38% para 48% com o denominador crescendo junto.

As cinco primeiras palavras são idênticas, então a leitura é a mesma: só o reconhecimento muda.
`Eventos.ehSwapV3` responde pelas duas, e é ele que `Cursor`, `Taxas`, `Vigia` e `Detector`
consultam. Mint e Burn têm a mesma assinatura nas duas DEXes.

---

## 4. Empate de truncagem escolhia a taxa por ordem de laço

`ReservasV2.taxaQueFecha` inverte o `getAmountOut` e procura a taxa exata numa janela de sete
milionésimos. Em troca pequena há **empate**: 2497..2503 dão o mesmo wei de saída truncada. O
código devolvia a primeira exata — 2497 —, a `Taxas` recusava por não ser redonda, e a pool de
2500 ficava fora do grafo por ordem de laço, não por evidência.

Agora, havendo um múltiplo de ponto-base entre as exatas, é ele. A janela de sete milionésimos
cabe no máximo um, então isto não inventa escolha: só para de descartar a certa.

O ponto-base (`Pool.PONTO_BASE = 100`) passou a morar na interface, porque dois lugares
precisavam dele e cada um tinha a sua cópia.

---

## 5. Imposto de token: por log, e com o limite dito em voz alta

**O que faz.** `Impostos` compara o que a pool diz que enviou (`Swap`) com o que o destino
recebeu (`Transfer` emitido pelo token), no mesmo recibo. Cicatriz do anterior, YFSX: a pool
enviou 37.583.037 e o destino recebeu 36.455.546 — exatos 3% retidos dentro do `transfer`.

**O portão.** `Cotador.precifica` só aceita par cujos **dois** tokens têm prova de imposto zero.
Token sem observação não é token limpo: é não provado, e não provado não entra em rota — tratar
ausência de prova como ausência de imposto foi o que deixou o YFSX passar. A regra fica no
portão, e não como custo na conta, por uma razão que o anterior mediu: token taxado tem preços
que divergem entre pools justamente porque ninguém arbitra através dele. A divergência é a
sombra do imposto, e dura porque o mercado não a fecha.

**Uma ferida nova no anterior.** O `ProvadorImpostoService` casava o `Swap` com o **primeiro**
repasse da rota pool→destino. Dentro do `swap()` o par transfere imediatamente antes de emitir o
evento, então o repasse certo é o **último**. Numa transação com duas trocas na mesma rota, o
primeiro é o de outro token — diferença que parece imposto e não é.

**O limite, que não se esconde.** A prova observa terceiros, e terceiros passam por roteador,
que muitos tokens taxados isentam. "Limpo" aqui quer dizer **nenhum imposto visto**. Por isso
guarda-se o maior retido e basta uma observação taxada para condenar — mas só a simulação da
nossa própria transferência fecha o buraco, e isso é da execução.

**O que ficou de fora.** Só a COMPRA (pool → destino) é medida, que é o que o anterior provava
por log. A VENDA (origem → pool) não tem um repasse identificável sem saber de antemão o token
de entrada, e adivinhar o repasse seria fabricar prova.

**MEDIDO — o custo do portão**, `GarimpoTest`, 256 blocos de aprendizado:

```
tokens com prova de imposto:   225 limpos · 119 tributados   (35% dos observados cobram)
pools com taxa provada:        263
pools precificáveis:           146   (o portão de imposto tirou 117)
```

**Um terço dos tokens que negociam cobra imposto na compra.** Não é exceção a filtrar por
cortesia: é um terço do livro, e cada um desses tokens produz divergência de preço entre pools
que não é oportunidade. O portão custa 44% das pools com taxa provada, e é o preço certo — a
alternativa é deixar essas pools gerarem achado que o contrato não paga.

---

## 6. Cobertura de taxa: onde ela para, e o que é estrutural

`Taxas` agora conta por que cada troca provou ou não provou taxa. **MEDIDO**, 768 blocos:

```
v2 taxa confirmada                  4570
v2 taxa provada                      594
v2 sem estado anterior na transação 1478   ← estrutural
v2 nenhuma taxa fecha                347   ← o chamador pediu menos
v2 pool já contraditória             443
v2 taxa quebrada                      10
v3 taxa confirmada / provada      422 / 83
v3 nenhum degrau fecha sozinho      1444   ← estrutural
contradições                           7
```

As duas maiores perdas não são alavanca:

- **"v2 sem estado anterior" = 1478, contra 1489 pools V2 no catálogo.** É a primeira troca de
  cada pool: o `Sync` dela devolve estado anterior só se a pool já foi vista. Toda pool perde
  exatamente uma troca, e só começar o cursor mais cedo recupera isso.
- **"v3 nenhum degrau fecha sozinho" = 1444.** A dedução da taxa V3 assume que a troca ficou
  dentro de um tick; troca que cruza tick não fecha em degrau nenhum, e tem de não fechar —
  aceitar seria usar a fórmula de um tick onde valeram vários.

"Nenhuma taxa fecha" (347) é o chamador pedindo menos que o máximo, e essas trocas não dizem
nada sobre a taxa. "Taxa quebrada" caiu para 10 em 5.620 trocas V2 que fecharam — o critério
redondo segue se defendendo.

**O que aumentou a cobertura de verdade**, e nenhum por suposição de taxa de factory:

1. A palavra do Mint: pool V3 que recebia liquidez era apagada do catálogo. Com a correção, **0
   pools descartadas por estado impossível**, e o catálogo de 768 blocos tem **266 pools V3**.
2. A assinatura da PancakeSwap V3 (ver seção 3).
3. O empate de truncagem, que devolvia 2497 no lugar de 2500.

A porcentagem sozinha engana, porque o denominador cresce junto: com as pools V3 que deixaram
de ser apagadas, o catálogo cresce mais depressa que as provas. O número que importa é o
absoluto de pools precificáveis — é ele que vira ciclo.

---

## 7. "Contradição apaga" estava errado: o K ordena as provas

A `Taxas` apagava a pool quando duas trocas fechavam exatamente em taxas diferentes, com a
leitura de que uma das duas vinha de estado errado. Guardando quem provou cada taxa, o censo
mostrou outra coisa:

```
0xd69aeb83…   2500 · 20 chamadores      3000 · 1 chamador  (0xf0b1fba8)
0xf49d9c79…   2500 · 13 chamadores      3000 · 1 chamador  (0xca980f00)
0x16b9a828…   2500 ·  9 chamadores      3000 · 4 chamadores (inclui 0xf0b1fba8)
0xbb0bd5b6…   2500 · 11 chamadores      3000 · 1 chamador  (0x0005ea70)
0xbae8327d…   2500 · 15 chamadores      3000 · 1 chamador  (0x0005ea70)
0x14c59422…   2000 ·  1 chamador        3000 · 1 chamador
```

Os chamadores de 3000 **se repetem em pools diferentes**. Não é estado errado — seria acaso —,
nem taxa por chamador — seria da pool, não do robô. É robô que calcula a saída com a fórmula de
0,3% da Uniswap: pede menos que o máximo de uma pool de 2500, o K aceita, e a troca fecha
exatamente em 3000.

**Por que isso importa: o K só limita por cima.** Troca que fecha em f prova que a pool cobra
no máximo f daquele chamador. Então as provas não se contradizem — elas se ordenam. E a regra
antiga apagava justamente as pools com mais chamadores, que são as mais negociadas: 330 trocas
perdidas em "pool já contraditória" numa corrida só.

**A regra nova.** Uma taxa só, vale. Mais de uma, vale a **menor provada por pelo menos dois
chamadores distintos**: a menor porque o K não deixa prova nenhuma abaixo da taxa real, dois
chamadores porque um só pode ser estado errado. Nenhuma assim — `0x14c59422…`, uma prova contra
outra —, a pool é ambígua e fica fora. Nas cinco primeiras a regra decide 2500.

O limite continua sendo o da seção 2: se a taxa depende do chamador e só os favorecidos
negociam, a menor prova é a taxa DELES. Isso só a simulação da nossa chamada resolve.

**MEDIDO, e sem vender mais do que é.** Na mesma janela, a regra velha provaria 952 pools e a
nova prova 956 — **+4**. Em outra janela, 1.011 contra 1.015. A regra está certa por construção
e salva exatamente as pools mais negociadas, mas **não é alavanca de cobertura**: é a correção
de um erro de raciocínio sobre um punhado de pools.

### O portão por chamador, e as duas versões que não se sustentaram

A 7a3eb6e7 pediu um portão: pool em que a taxa provada é de chamadores específicos não serve
para o nosso executor. A regra escrita é **pool movimentada (≥ 10 fechamentos) com até dois
chamadores, nenhum deles roteador público**. Chegar nela custou duas versões erradas, medidas:

| versão | o que tirava | por que não se sustenta |
|---|---|---|
| ≥ 10 fechamentos e ≤ 2 chamadores | 68 pools | quase todas tinham como único chamador `0x10ed43c7…`, o roteador V2 da PancakeSwap. O chamador do `Swap` é quem chamou o PAR, e o roteador chama em nome de todos: pool só com ele é a **mais aberta** que existe (`0x9a43b714…`, 135 fechamentos) |
| "aberto" = chamador visto em ≥ 10 pools | 4 pools | **soltou a própria 7a3eb6e7**: o chamador dela também aparece em muitas pools. Amplitude não prova abertura |
| roteador público por endereço | 18 pools | 7a3eb6e7 dentro, nenhuma pool com roteador entre os chamadores |

**Roteador é o que está provado como roteador**, por endereço completo, com procedência: o mapa
`RoteadorDaFactory.ROUTERS` do projeto anterior (PancakeSwap V1 e V2, Biswap, ApeSwap,
BakerySwap, MDEX, SushiSwap), que ele usava para `getAmountsOut` e tinha teste próprio. O
anterior não identificou nenhum agregador, então nenhum entra. `0x8f10b468…`, `0x07964f13…`,
`0x11111605…`, `0xf9ffcdc5…` e `0xaaaaab5a…` não aparecem em lugar nenhum dele, e aqui **contam
como chamador comum** — não viram roteador por suposição.

Consequência que precisa estar dita: se um desses for agregador público, as pools que só ele
negocia saem do grafo sem motivo. É o lado seguro do erro, e é conferível: as 18 pools tiradas
na corrida estão impressas pelo `TaxaSobSuspeitaTest`, com par, taxa, fechamentos e chamadores.
O teste também afirma o controle do outro lado: nenhuma pool tirada tem roteador público entre
os chamadores.

---

## 8. Cruzar tick no V3: fora, e por quê

O anterior também recusava cruzar (`PoolV3`: *"quanto dá para entrar sem cruzar a fronteira da
faixa"*); não havia mapa de ticks para trazer.

Cruzar exige o `liquidityNet` de cada tick inicializado entre o preço atual e o de destino. Isso
é estado acumulado desde a **criação** da pool, e só se reconstrói por log se o cursor tiver
visto todos os `Mint`/`Burn` da vida dela. Os pares servem recibo de ~100 mil blocos para trás,
não de milhões — então por log não fecha. Os caminhos são ler o estado do contrato (`ticks()` e
`tickBitmap()` por `eth_call`, contra a premissa de não usar RPC para o que o node dá), ou
sincronização de estado (snap). Até lá o teto da faixa é o que torna a curva segura: entrada
limitada, nunca prometida.

---

## 9. Persistência: guardar evidência, não decisão

A persistência feita no item 2 embutiu no banco a regra "contradição é permanente": gravar
uma segunda taxa contradiz, e regravar não reabilita. A seção 7 derrubou essa regra, e o motivo
mostra por que ela não pode morar no banco. **Decisão muda quando chega evidência**: 2500 de
vinte chamadores vence 3000 de um, e a mesma pool pode passar de ambígua a decidida. Banco que
guarda a taxa decidida decide com a regra do dia em que gravou.

Então o banco guarda a **evidência**, e a regra que decide mora num lugar só: `Taxas`.

### A API

```java
record ProvaDeTaxa(byte[] pool, int taxa, String chamador, long fechamentos)

taxas.aoProvar(Consumer<ProvaDeTaxa> ouvinte)   // cada fechamento exato e redondo, fechamentos = 1
taxas.semear(List<ProvaDeTaxa> provas)          // reaplica a regra sobre a evidência agregada
```

Não existe `aoContradizer`: contradição deixou de ser evento. Ambiguidade é consequência da
evidência, e se recalcula sozinha ao semear.

O `TaxaSobSuspeitaTest` prova a ida e volta: agrega os fechamentos por (pool, taxa, chamador)
como a tabela faria, semeia uma `Taxas` nova, e exige que **nenhuma** pool decida diferente —
nem a taxa, nem `fechadaAChamadores`, que depende da contagem de fechamentos e de chamadores.

### A tabela

```sql
create table provas_de_taxa (
    pool         bytea    not null check (length(pool) = 20),
    taxa         integer  not null check (taxa >= 0 and taxa < 1000000 and taxa % 100 = 0),
    chamador     bytea    not null check (length(chamador) = 20),
    fechamentos  bigint   not null check (fechamentos > 0),
    primary key (pool, taxa, chamador)
);

-- gravar: soma, nunca substitui
insert into provas_de_taxa (pool, taxa, chamador, fechamentos) values ($1, $2, $3, 1)
on conflict (pool, taxa, chamador) do update set fechamentos = provas_de_taxa.fechamentos + 1;

-- semear: tudo, e a decisão sai do código
select pool, taxa, chamador, fechamentos from provas_de_taxa;
```

Três regras que a tabela precisa honrar, cada uma com o motivo:

- **Sem coluna de taxa decidida, sem coluna "contraditória".** Qualquer uma delas é a regra
  gravada, e a próxima mudança de regra deixa o banco mentindo.
- **Chamador é parte da chave.** O ponto inteiro do modelo é que a mesma taxa provada por dois
  chamadores vale mais que por um, e que pool movimentada com dois chamadores só é suspeita.
  Sem o chamador, as duas regras são impossíveis de reaplicar.
- **O `check` de ponto-base é do domínio, não do formato.** `Taxas` já recusa taxa quebrada
  antes de emitir a prova; o `check` garante que nenhum outro caminho de escrita a introduza.

O `chamador` é o endereço de 20 bytes em hexadecimal no Java (o primeiro tópico indexado do
`Swap`); `?` só aparece em log sem tópico, e esse nunca chega a `provar`.

---

## 10. O caminho inteiro depois deste item, e o que ainda não fecha

`GarimpoTest`, 256 blocos de aprendizado e 256 de vigia:

```
catálogo: 708 V2 + 399 V3 · 0 V3 descartadas por estado impossível
taxas provadas: 559 de 1.107 pools (50%) · 1 ambígua · 7 fechadas a chamadores
impostos: 263 tokens limpos · 164 tributados
grafo: 261 pools precificáveis · 2.794 ciclos sobre 130 pools
vigia: 11.157 achados · 0 acima do gas em BNB · 0 acima de 30 bp pagando gas
retorno: mediana 5 bp · maior 199 bp
confirmação: 34% dos achados tocam pool que terceiros arbitraram em até 3 blocos
```

Três coisas que precisam estar ditas:

**A vigia de 48 blocos numa corrida anterior não foi configuração.** O teste pede 256 e pediu
256; naquela corrida o cursor parou pelo prazo de parada com o pool em 5 sessões, e na seguinte,
com o mesmo código, andou os 256. É instabilidade de pares, e é do item 2.

**O custo de avaliar está subindo com a cobertura**, e é para acompanhar:

```
                       ciclos   pools indexadas   custo por bloco
antes deste item          374               34          ~1,4 ms
com Pancake V3          2.674              112         37,7 ms
última corrida          2.794              130         55,5 ms
```

Ainda cabe no bloco de 750 ms, mas cresceu quarenta vezes com sete vezes mais ciclos: não é
linear. É o preço de a PancakeSwap V3 existir para o catálogo, e o próximo passo de cobertura
precisa vir junto com olhar para isso.

**O maior achado da última corrida é suspeito, e o dossiê mostra por quê.** Os três primeiros
passam por `0x2ab8a4dd…7777` como token do meio, entre uma V3 de 1% e uma V2. Endereço terminado
em 7777 é exatamente o padrão dos impostores que o `Par` documenta. Ele passou pelo portão de
imposto porque não teve imposto **visto** na compra — e a seção 5 já diz que "limpo" é "nenhum
imposto visto", não "sem imposto". São 34 bp sobre 27 USDT: pequeno, e não conta como
oportunidade até alguém conferir o token.
