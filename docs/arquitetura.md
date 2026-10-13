# Node BSC — arquitetura do módulo de descoberta

Este documento existe porque o código não carrega javadoc. O que está aqui é o
que **não se lê no código**: por que cada peça é assim, o que já custou caro, e
o número que provou. O código diz *o quê*; isto diz *por quê*.

Medido em 12/09/2026, contra a rede real.

## O mapa

```
vo/          valores, um tipo por arquivo, sem nada aninhado
rlp/         Rlp · RlpItem · RlpBytes · RlpLista · RlpLeitor
util/        Resources · Identidade · Keccak · NodeKey · Secp256k1
cadeia/      ParametrosBsc · Forks · Crivo      — a identidade da rede
kademlia/    Distancia · TabelaDeRotas          — a estrutura da DHT
rede/        PortaUdp · Envelope · Discv4       — bytes e socket
sessao/      Sessao · Sessoes · Lotacao
             ConexaoUdp · ConexaoTcp · Vinculo  — a conversa com um par
descoberta/  Descoberta · Sementes              — a política de varredura
repository/  PeersRepository · Postgres · Trabalho
```

## Um projeto, um módulo, separação física no fonte

Não há multi-módulo nem projetos separados, e não é falta de rigor: se tudo
termina em **um processo**, módulo Maven entrega artefato separado (que não se
usa) e imposição do grafo (que é o que importa). Só a segunda vale — e ela não
precisa de módulo.

A pergunta que mudaria isso não é "módulo ou pacote": é **um processo ou
vários**, e quem decide é o orçamento de latência. O caminho de 45 ms não pode
dividir escalonador com trabalho frio; quando esse dia chegar, a resposta é
binário separado, e o módulo vem junto de graça. Até lá, um módulo.

O que substitui o pom é `dependencias.txt`: o contrato de o que cada pacote pode
importar. `ArquiteturaTest` reprova o `mvn test` quando o código importa o que
não está declarado, e quando o grafo tem ciclo. Acrescentar uma aresta ali é um
ato consciente, do mesmo tamanho de editar um pom.

Verificado que morde: reintroduzir `kademlia -> descoberta` quebra o build com o
arquivo e a aresta no nome.

O grafo é acíclico e a ordem do mapa acima é a ordem das dependências: `vo` não
depende de nada, `descoberta` fica no topo.

`TabelaDeRotas` começou dentro de `descoberta` e criou um **ciclo**:
`Sessao` precisava dela para responder FindNode, e `Descoberta` precisava de
`Sessao`. A tabela não é política de varredura — é estrutura do protocolo, um
nível abaixo das duas. Com módulos Maven o ciclo não teria compilado; com
pacotes, passou despercebido até alguém desenhar o grafo.

Duas threads no módulo inteiro: a de recepção (dentro de `PortaUdp`) e a de
rodada (dentro de `Descoberta`). Sessão **não tem thread, não tem socket e não
bloqueia** — é estado. A versão anterior gastava duas threads por peer, com dois
pools sem teto.

## Por que a descoberta não funcionava

Quatro defeitos, nenhum deles no protocolo:

1. **O socket descartava datagrama de estranho.** Havia um mapa
   `endereço → conexão` e um `if (conexao == null) continue`. No discv4 **não
   existe conexão**: um Ping chega de quem nunca contactamos, e é assim que se
   entra na tabela dos outros. Todo Ping de estranho ia no lixo.
2. **Nunca respondíamos FindNode.** A vizinhança que alimentaria a resposta
   ficava vazia para sempre, porque o único método que a preenchia nunca era
   chamado. Um geth que pergunta e não é respondido tira o par da tabela dele.
3. **O alvo do FindNode ia errado.** Era `peer.getId().getBytes()` — os 128
   bytes ASCII do hexadecimal onde o protocolo pede os 64 bytes crus da chave.
   Só foi possível errar assim porque a identidade do nó andava como `String`.
4. **Não havia tabela de rotas.** Sem ela não há o que responder, nem para onde
   apontar uma busca.

Somados: o node era **invisível na DHT por construção**, sem um único erro no
log. O codec estava correto o tempo todo — foi a primeira coisa verificada
(um Ping fecha em 129 bytes e a assinatura recupera o próprio id).

## Decisões, uma a uma

### `PortaUdp` — uma porta, sem registro por par
Entrega ao ouvinte **todo** datagrama, venha de quem vier. A decisão de ignorar
é de quem entende de protocolo, nunca do socket. Cada entrega vai isolada num
`try`: a porta fica exposta à internet e um pacote malformado de um estranho não
pode emudecer o node.

### `Envelope` e `Discv4` — separados de propósito
São duas razões de mudar diferentes: campo novo numa mensagem mexe no `Discv4`;
troca de curva ou de hash mexe no `Envelope`. Juntas passavam de duzentas linhas
— foi assim que a classe anterior virou o lugar onde ninguém mais olhava.

A identidade do remetente **não é declarada** em campo nenhum: é recuperada da
assinatura. Forjar um remetente exige a chave privada dele.

A checagem de campos é sempre "ao menos N", nunca "exatamente N". O geth manda
campos extras (a sequência do ENR no Ping); exigir tamanho exato faria o node
recusar todo pacote de um geth atual e ficar mudo sem erro aparente.

### `MensagemDiscovery` selada — e já se pagou
Ao acrescentar os dois tipos do EIP-868, o compilador apontou sozinho cada lugar
que precisava decidir o que fazer com eles. Nenhum caiu num `default` silencioso
— que foi exatamente como o FindNode se perdeu antes.

### `Sessao` — um handshake, que é do node
Não existe handshake UDP e handshake TCP. Existe *o* handshake: nos
apresentarmos e ficarmos provados para o outro lado. As conexões são detalhe
interno, e a escolha de qual usar sai do **tipo da mensagem**, resolvida pelo
compilador na sobrecarga de `enviar`. Quando o RLPx entrar, ele ganha uma
sobrecarga ao lado da atual e nenhum `if` de transporte nasce.

A versão anterior expunha `handshakeUDP()`, `handshakeTCP()`,
`handshakeCompleto()`, `usarUDP()` e `usarTCP()` — cinco métodos públicos com
**zero chamadas**, oferecendo ao chamador uma escolha que nunca foi dele. Pior:
o lado TCP mandava pacote discv4 com prefixo de tamanho, coisa que node Ethereum
nenhum fala.

O handshake **não bloqueia**: a prova chega num datagrama, depois, na thread de
recepção. Bloquear custaria uma thread por peer.

`receber` **devolve** os vizinhos aprendidos em vez de chamar de volta quem criou
a sessão. Sem callback, sem ciclo, sem interface de um implementador só.

### `Vinculo` — a prova de endpoint
O Pong precisa ecoar o hash exato do Ping que o provocou. Sem conferir o eco,
qualquer um provaria endpoint respondendo a um Ping que nunca viu. Um geth só
aceita FindNode de quem tem prova válida; sem vínculo, nossos pacotes são
descartados **em silêncio** — o modo de falha mais caro que existe.

Validade de 12h é o valor do geth. Usar mais do que o par usa é ficar convencido
de um vínculo que o outro lado já esqueceu.

Quando um estranho nos pinga, respondemos o Pong **e** pedimos a nossa prova de
volta se ainda não a temos. Não gera laço: o segundo Ping encontra vínculo já
válido e para. É isso que nos torna visíveis.

### `Distancia` — o keccak no meio não é detalhe
No devp2p a distância **não** é medida sobre a chave pública: é sobre o
`keccak256` dela. Medir sobre os 64 bytes crus dá uma tabela que parece
funcionar — insere, ordena, responde — e que aponta para o lado errado da rede
em toda busca. Não quebra teste nenhum: só faz a varredura nunca crescer.

`comparar` usa o XOR byte a byte, não o balde. Balde é grosso — dezenas de nós
dividem o mesmo — e responder FindNode com "algum dos que caem por ali" faz o
par nos avaliar como fonte ruim.

### `TabelaDeRotas` — é de quem provou, não de quem foi mencionado
Um nó só entra depois do Pong. Essa é a regra do Kademlia, e é o que impede a
tabela de virar repetidora de endereço que ninguém confirmou. A primeira versão
inseria quem era *aprendido* — o inverso — e o sintoma foi `0 nós na tabela` com
sessões vinculadas.

Ela não pinga, não persiste, não fala com rede e não sabe que horas são. Balde
cheio recusa o recém-chegado, o que já favorece o nó estável — que é a intenção
da regra original. A disputa do Kademlia (pingar o mais antigo) exigiria socket
aqui dentro; quando valer a pena, `inserir` devolve o antigo para quem tem
socket resolver.

### `Descoberta` — não persiste
Entrega cada nó novo a um `Consumer` e segue. É o que deixa o módulo inteiro se
exercitar contra a rede real sem banco nenhum, e o que mantém esquema de tabela
fora daqui.

Quem diz se um nó é novo é o mapa de sessões — ele já existe, já é indexado por
identidade e já tem teto. Um segundo conjunto de "sondados" seria a mesma
verdade em dois lugares; no node anterior ele crescia sem limite e nunca
esquecia ninguém.

O alvo de cada busca é **sorteado**. Perguntar sempre pela própria identidade
devolve sempre a mesma vizinhança.

### `Sementes` — o único ponto de inclinação
O discv4 é um DHT compartilhado e **não tem identidade de rede**. A semente é o
único lugar onde dá para inclinar a varredura. A lista anterior eram peers
avulsos em 30303/30307, e a tabela de roteamento deles vinha dominada por
Polygon e PulseChain. Os bootnodes da BSC atendem na porta **30311**.

## "Ei node, tu é de quê?"

Isso **não** é responsabilidade do handshake — o handshake prova *endereço*, a
pergunta é de *identidade de cadeia*. São validades diferentes: o endereço muda
quando o nó se move, a rede muda quando ele atualiza de fork.

A resposta vem por UDP, sem TCP nenhum: `ENRRequest`/`ENRResponse` (EIP-868). O
registro publicado traz a chave `eth` com o fork id (EIP-2124), cujo hash deriva
do genesis — genesis diferente, hash diferente.

Pré-requisito verificado na prática: o bootnode da BSC só devolveu o registro
depois que **nós** respondemos o Ping dele.

Não conferimos a assinatura interna do registro. O envelope do pacote já prova
que quem respondeu é dono da chave; a assinatura interna protege o registro
quando ele é **repassado por terceiros**, coisa que ainda não fazemos. Quando
fizermos, ela passa a ser obrigatória.

## Números

Varredura de 75s a partir das 6 sementes, porta efêmera:

```
descobertos = 417    vinculados = 407    declararam rede = 240 (58%)
redes distintas na mesma DHT = 37

ForkId[0x22d523b2→0]          73 pares (30%)
ForkId[0x07c9462e→0]          48 pares (20%)
ForkId[0x62ebcfaf→0]          35 pares (15%)
ForkId[0xd38cc282→0]          14 pares (6%)
ForkId[0x3f2e9ae4→5184000]    13 pares (5%)   ← BSC
```

Por porta anunciada, numa varredura de 30s: 43% em 30303, **6% em 30311**. As
duas medidas concordam — a BSC é uma fatia pequena do DHT, e por isso filtrar é
obrigatório antes de gastar TCP com alguém.

## Configuração

`Resources` resolve cada chave em três lugares, nesta ordem: **variável de
ambiente → propriedade `-D` → arquivo**. A chave de ambiente é a mesma em
maiúsculas com `.` virando `_`: `node.porta.udp` é sobrescrita por
`NODE_PORTA_UDP`. Um `node.properties` ao lado do executável sobrescreve o
embutido no jar, chave a chave — o externo não precisa repetir o que não muda.

| Chave | Por que o valor |
|---|---|
| `node.chave.arquivo` | A identidade é a chave pública. Trocar de chave é trocar de identidade na DHT: quem nos conhecia perde a referência. Gerado na primeira execução, permissão `rw-------`. |
| `node.porta.udp` | A porta que anunciamos no Ping. Se não for alcançável de fora, ninguém prova nosso endpoint e sumimos das tabelas alheias. |
| `descoberta.intervalo.segundos` | 300s era tempo demais: quando os peers nos evictam em bloco, ficamos sem candidato novo por rodadas inteiras. |
| `descoberta.vinculo.horas` | 12h é o valor do geth. Usar mais do que o par usa é ficar convencido de um vínculo que ele já esqueceu. |
| `descoberta.nos.por.balde` | 16 é o *k* do discv4. |
| `descoberta.nos.por.pacote` | Um nó ocupa perto de 90 bytes e o datagrama tem 1280. Acima de 12 por pacote, ele é descartado inteiro. |

**Sobre `postgres.senha` estar no arquivo:** decisão consciente do dono do
projeto — nada aqui vai para ambiente público, e a conveniência local vale mais
que o rito. Fica registrado para que ninguém confunda com descuido. Se um dia
este código sair da máquina, o mecanismo para tirar a senha daqui já existe e
não custa nada: deixar a chave vazia no embutido e pôr o valor num
`node.properties` na raiz, ignorado pelo git — o externo sobrescreve o embutido
chave a chave.

## O handshake do node, medido

Caminho completo, contra a BSC real, em 12/09/2026:

```
discv4 → ENR → crivo → TCP → ECIES → quadros → Hello → Status → UpgradeStatus → GetReceipts → Receipts
```

```
Geth/v1.8.0-alpha  eth/68  fork=0xd38cc282→0   GetReceipts: 559 ms · 53.389 bytes
Geth/v1.8.0-alpha  eth/68  fork=0xd38cc282→0   GetReceipts: 275 ms · 22.863 bytes
```

### **A medida decide o caminho quente: o recibo não cabe na janela do bloco.**

A janela é de 450 ms e a volta de um `GetReceipts` do bloco da cabeça custou
**275 a 559 ms** — com o par escolhido entre os que declaram exatamente o nosso
corte de fork, e nós anunciando `td=0` e o genesis como cabeça, isto é, como
leech. Um par bem-visto e próximo seria mais rápido, mas a ordem de grandeza está
dada: **esperar recibo no caminho de decisão é perder o bloco.**

Então, e isto fecha a dúvida que ficou em aberto:

- **caminho quente:** corpo do bloco (chega de graça no `NewBlock`) + mempool +
  catálogo, e a reserva sai da **simulação** dos swaps. Zero volta de rede.
- **caminho frio:** recibo, por `GetReceipts`, para provar imposto, achar pool
  nova e construir catálogo. Fora do orçamento de 45 ms.

### Outras três medidas da mesma corrida

- **`Disconnect[motivo=4]` — "peers demais" — em 10 de 12 tentativas.** Os nós da
  BSC estão cheios. Sustentar 25 sessões ativas exige tentar muitas: a taxa de
  aceitação medida foi de ~20%.
- **Par em fork anterior recusa, e é correto que recuse.** Um Geth cujo ENR
  declarava `0xce18f5d3` (Pascal+Prague) derrubou com "erro de subprotocolo": ele
  não conhece o nosso hash. Filtrar candidatos por *mesmo corte* elevou o
  sucesso de 0 para 3 em 12.
- **`Código eth ainda não tratado: 8`** — `NewPooledTransactionHashes` chegou sem
  ser pedido. É a mempool entrando empurrada, como o desenho previa.

### Duas armadilhas de protocolo que custaram uma rodada cada

- **RLP não-canônico.** `BigInteger.ZERO.toByteArray()` devolve `[0x00]`, e o
  escalar zero em RLP é a **string vazia**. Mandar o byte zero rendeu
  `Disconnect` "quebra de protocolo" — sem nenhuma pista de qual campo.
- **`UpgradeStatus` (0x0b).** A BSC troca essa mensagem logo depois do Status no
  eth/68, e não responder rende "erro de subprotocolo". O eth/70 **não** a troca:
  mandá-la ali seria o erro simétrico.

Anunciamos só `eth/68` de propósito — é a versão cujo formato de Status está
implementado. O eth/70 acrescenta a faixa de blocos servida, e anunciá-lo sem
produzi-la seria mentir no protocolo.

## As 25 conversas, e por que o teste reprovou duas vezes

O pool de 25 é onde o projeto anterior morreu. O teste de módulo não mede quantas
abrem — mede se elas **se mantêm**, que é a propriedade que faltava. Ele reprovou
duas vezes antes de passar, e cada reprovação achou um defeito de desenho:

### Reprovação 1 — o critério de catalogar não é o critério de conectar

`Crivo.bsc()` responde *"é da BSC?"*. A `Lotacao` usava isso para escolher com
quem conversar, e abriu **zero** em 120 s.

O motivo: um par num corte de fork **anterior** é BSC e recusa a conversa — ele
não conhece o nosso hash, e recusar é o comportamento correto pelo EIP-2124. E
bootnode é BSC e não fala eth.

São duas perguntas diferentes sobre o mesmo fork id, e o crivo responde as duas
sem ganhar um "e" na ficha — quem escolhe o conjunto de cortes aceitos é a
fábrica:

```
Crivo.bsc()           todos os cortes      "é da BSC?"      catalogar par
Crivo.corteDeHoje()   só o corte atual     "vai conversar?" abrir conversa
```

### Reprovação 2 — código desconhecido não é erro, é roteiro

Com o critério certo, abriu 3 — e caiu a zero em **10 segundos**, muito antes da
tolerância de mudez de 45 s. Logo, algo lançava.

Era o nosso próprio codec: `PeerException("Código eth ainda não tratado")`. No
instante seguinte ao Status o par manda mempool (`0x08`), transações (`0x02`) e
anúncio de bloco — e nada disso é opcional para ele. Cada mensagem que não
sabíamos ler matava a conversa.

O eth permite ignorar código não implementado. Tratá-lo como erro é aplicar a nós
mesmos a mentalidade de "quebra de protocolo". Então existe
{@code Desconhecida(codigo, corpo)} na família selada, e a `Lotacao` **conta** o
que chega assim — o que transforma o defeito em roteiro: esses números dizem qual
mensagem a rede realmente usa, e portanto qual implementar em seguida.

### O que passou

```
ativas depois de abrir: 2
   t=+0s  2      t=+15s  3      t=+35s  2
mínimo sustentado: 2
```

Subir para 3 no meio da sustentação é a reposição funcionando: o mesmo laço que
detecta a morte repõe, e não existe caminho em que a lotação caia e ninguém tenha
a tarefa de recompô-la.

E os motivos de término, que agora aparecem no log com o nome certo:

```
derrubou: peer inútil              ← lido como leech por td=0; MEDIDO depois que não é
derrubou: erro de subprotocolo     ← borda de incompatibilidade de fork
```

A leitura do primeiro estava errada, e a correção é do item 2 (`docs/cabeca.md`, seção c): 96 a
128 "peer inútil" por corrida chegam ANTES do nosso `Status`, então não podem ser sobre a cabeça
que anunciamos — o par ainda nem a viu. No `handler.go` da BSC a admissão confere as extensões
`snap`/`bsc`, e nós negociamos só `eth`: é a hipótese que sobra, ainda não testada. Ele só é legível porque a
tabela de motivos do `Disconnect` foi conferida contra `p2p/peer_error.go`: a
anterior estava errada de `0x03` em diante, e chamava de "peer inútil" o
`0x0b`, que é "ficamos calados tempo demais".

### O orçamento de threads, declarado

Uma de rodada da descoberta, uma de recepção UDP, uma de rodada da lotação,
oito de abertura (que bloqueiam no connect) e **uma de leitura por conversa
ativa**. Nada por par conhecido — o node anterior gastava duas threads por peer,
com dois pools sem teto, para fazer menos do que isto.

## Sincronização: os números que decidem o desenho

Medido em 12/09/2026 contra `Geth/v1.8.0-alpha`, cabeça `121.537.290`.

### Curva de lote do `GetReceipts` — o limite é BYTE, não contagem

| lote pedido | vieram | ms | bytes | kB/bloco | ms/bloco |
|---|---|---|---|---|---|
| 1 | 1 | 147 | 49.805 | 48,6 | 147,0 |
| 8 | 8 | 691 | 425.407 | 51,9 | 86,4 |
| 32 | 32 | 1.354 | 2.053.975 | 62,7 | 42,3 |
| 128 | **33** | 940 | 2.110.566 | 62,5 | **28,5** |

Pedimos 128 e vieram 33. O limite é o `softResponseLimit = 2 * 1024 * 1024` do
handler do eth **no geth**: ele enche a resposta até cruzar 2 MiB e para. Com ~60 kB
por bloco na BSC, isso dá 33.

Não é gRPC (não há gRPC em parte nenhuma deste caminho — é devp2p sobre TCP cru), e
não é regra do devp2p. Há tetos de contagem no mesmo arquivo (`maxReceiptsServe =
1024`, `maxBodiesServe = 112`), mas o byte amarra primeiro. O quadro RLPx comporta
16 MB, então não é ele.

**Consequência:** 2 MB é escolha de implementação de CLIENTE, e o `Hello` diz qual
cliente é. O orçamento de lote tem de ser **aprendido por par** — pede, observa
quantos vieram e quantos bytes, ajusta — e não fixado em 2 MB. Fixar seria adivinhar
o cliente alheio.

Ganho do lote: **147 → 28,5 ms/bloco, 5x.** Por sessão, 35 blocos/s.

### Poda — e isto responde de que altura o cursor parte

| profundidade | cabeçalho | recibo |
|---|---|---|
| 0 | sim | 88.907 bytes |
| 1.000 | sim | 61.482 bytes |
| 100.000 | sim | 170.900 bytes |
| 1.000.000 | **não** | — |
| 10.000.000 | **não** | — |

O horizonte de retenção está entre 100 mil e 1 milhão de blocos: **1 a 9 dias**.

> **Sincronizar desde a criação da factory é impossível pelo p2p.** Os pares não
> têm. Não é escolha nossa — é o que a rede guarda.

E a restrição coincide com o requisito: pool que não negociou em dias é pó (74% dos
ciclos da autópsia tinham pool com menos de 10 unidades), e token sem imposto provado
é token que não negociou. As pools que importam são as que aparecem na janela que os
pares servem. Morre com isso o debate "bootstrap desde o genesis" e a dependência de
RPC que ele arrastava.

### Dimensionamento

```
bootstrap da janela retida:  100.000 blocos × 60 kB = ~6 GB → ~8 min a 100 Mbps
regime na cabeça:            2,2 blocos/s × 60 kB   = ~132 kB/s
```

**O gargalo é banda, não slot.** Uma sessão puxa 2,1 MB/s; 21 slots pediriam 44 MB/s
(350 Mbps). Num link de 100 Mbps, ~6 slots saturam. A faixa longa se dimensiona por
orçamento de banda — senão os slots brigam entre si e a faixa curta paga a conta.

### O que ainda não foi medido

A latência da cabeça com leque: do `NewBlockHashes` chegar até o recibo na mão, com
leque de 1 e de 4. Precisa de `NewBlockHashes` tratado dentro de sessão viva mais a
orquestração do leque — isso já é metade do cursor, e improvisar dentro de um harness
de medição seria construir o cursor sem ficha.

## O cursor, e a resposta sobre curva não-provada

Medido em 13/09/2026, 32 blocos contíguos a partir de 121.574.808.

```
continuidade: ok               181 pools V2 · 30 pools V3 no catálogo
legibilidade: 642 leituras V2 e 91 V3, todas com altura dentro da faixa

451 swaps com estado anterior conhecido
   igualdade exata com taxa REDONDA:    356  (79%)
   igualdade exata com taxa quebrada:     0
   sem igualdade (chamador pediu menos):  95  (21%)
   taxas: {2500=351, 10000=2, 20000=1, 500=2}
```

### A resposta: praticamente nada do fluxo V2 está em curva não-provada

Antes do cursor, a mesma medição dizia **70% em "invariante violada"**, com taxas
quebradas: 2440, 2140, 1728, 2303. Depois do cursor: **zero taxas quebradas**, e
todas redondas.

A diferença não era o mercado — era o meu instrumento, e ele teve quatro defeitos
antes de acertar:

1. **varredura de taxa de 10 em 10** — taxa não múltipla de dez caía em "outra curva"
2. **teste simétrico** — no V2 o par NÃO calcula a saída: quem chama a especifica e o
   par só verifica a invariante K. Saída menor que o máximo é o chamador pedindo
   menos, não a pool tendo outra curva
3. **taxa recuperada de um swap pequeno** — o truncamento deixa várias taxas
   empatadas, e um milionésimo de erro produz "violação" na oitava casa
4. **logs processados em ordem DESCENDENTE de bloco** — eu pedia cabeçalhos com
   `reverso=true` e aplicava os lotes nessa ordem, então o "Sync anterior" era o
   estado **posterior**. Este era o que importava.

O quarto é o mesmo erro do projeto anterior numa escala menor: **estado errado, e a
taxa absorvendo o erro.** E é por isso que a prova de curva é *produto* do cursor, não
pré-requisito dele — não há como provar curva a partir de janela sem estado contínuo.

### Taxa redonda como critério

Com estado certo, a taxa recuperada é redonda. Com estado errado, ela sai quebrada
porque absorve o erro. **Taxa quebrada é sinal de estado errado, não de curva
exótica** — e isso dá um teste que erro de estado não consegue passar.

Substitui a constante da factory (que assume a curva), a sonda (que exige RPC) e a
inferência aproximada (que deu 5 bps numa pool de 1000). Só de log.

### O que o cursor garante por construção

Avança quando o bloco está completo **e** o pai é o bloco anterior. Não existe caminho
em que passe por cima de furo — dá para estar **atrasado**, nunca **furado**. Morre com
isso o backfill, o reparo de lacuna, o repedido de recibo, `reserva_mais_velha` e
`FORA_DA_JANELA`: todos existiam porque o cursor tinha permissão de passar na frente de
dado incompleto.

A **montagem** é separada do avanço de propósito, e a separação já se pagou: a janela de
pedidos em voo entrou depois, e o avanço **não mudou uma linha** — porque avançar sempre
dependeu só de "a próxima altura está montada?", nunca de quem a trouxe. E ela não é
cache: não tem miss. Se falta, o cursor **sabe o que falta e pede**.

## A janela de pedidos em voo

Duas classes, e a divisão entre elas é a frase de cada uma:

- **`Frente`** — serve faixas de blocos usando qualquer par disponível, até todas
  voltarem. Não decide ordem, não aplica log, não escolhe par bom (isso é da `Lotacao`).
  Reatribuir não é segunda responsabilidade: é o que "qualquer par disponível" significa
  quando o par escolhido falha.
- **`Cursor`** — avança a altura processada. Não busca nada.
- **`Orcamentos`** — quantos blocos vale a pena pedir a cada par.
- **`Faixa`** — a unidade de trabalho: `(deAltura, quantos)`. Existe porque "o que faltou"
  precisa de um lugar para ficar, e o jeito de não ter lugar é fingir que veio.

O cursor pede pela `Frente` e recebe por `montar(Bloco)`; a `Frente` recebe o ouvinte em
`abrir`, não no construtor, porque quem monta é o cursor e o cursor precisa da frente
para pedir — é o mesmo corte de ciclo da `PortaUdp`.

### A correlação por request-id, e por que ela veio junto

A primeira medição da janela deu **3,3 blocos/s — 128 blocos entregues, 76 reatribuições
para 8 faixas servidas.** Continuidade e estado passaram; a vazão não. A causa não estava
na janela: o teste não subia a `Lotacao`, então cada trabalhador pagava TCP + ECIES +
`Hello` + `Status` por faixa, com ~80% de recusa por "peers demais".

E não dava para simplesmente ligar o pool, porque `pedir` correlacionava **por bloqueio**:
"a próxima mensagem da classe que eu quero é minha". O leitor da `Lotacao` lê o mesmo
socket — ele consumiria a resposta, ou entregaria a de um pedido a outro. Trocar resposta
é o erro que não dá exceção: os recibos chegam, são do bloco errado, e a continuidade só
reprova depois.

A tensão estava escrita no `pedir` desde que ele nasceu, com a saída já nomeada: *"quando
o cursor existir, a correlação passa a ser por request-id dentro do leitor"*. É o que foi
feito:

- **`vo/Correlacionada`** — interface selada sobre os cinco tipos que carregam
  `requisicaoId`. `Status`, `UpgradeStatus` e `NewBlockHashes` ficam fora: é o tipo dizendo
  quem pode ser correlacionado, em vez de um método devolvendo zero (L4).
- **`sessao/Pedidos`** — o registro de pedidos em voo de UMA conversa. `registrar`,
  `entregar`, `esperar`, `abortar`. O `abortar` no `desativar` é o que impede que a morte
  do par prenda um trabalhador pelo prazo inteiro — o pior par voltando a ditar o ritmo
  por uma porta dos fundos.
- **`Sessao.atender`** — o leitor, único que lê, entrega a resposta a quem espera.
- **`Sessao.pedir`** — registra, envia, espera o **próprio id**. Passou a **exigir um
  leitor vivo**, e por isso a `Frente` só usa sessão já ativa e nunca abre a sua.
- **`ConexaoTcp.enviar` sincronizado** — duas threads escrevem nesta conexão (o leitor,
  que responde o `Ping`, e quem pede), e `Quadros.escrever` avança o MAC de saída: dois
  quadros montados ao mesmo tempo saem com MAC trocado e o par derruba por quebra de
  protocolo.

Efeito colateral que vale dizer: mais de um pedido por sessão passou a ser **possível**.
O arrendamento continua porque espalhar carga entre pares é o objetivo — não porque o
protocolo obrigue.

### Nenhuma faixa é refém de um par

É a premissa P4 escrita em código: quem falha perde a faixa para outro. Isso faz a vazão
degradar **linear** com a qualidade dos pares em vez de desabar. A rodada em série era
refém do pior par — centenas de blocos por segundo caíam para dois ou três, e **vazão
bimodal é sempre assinatura de espera em fila por alguém**.

### O lote é aprendido por par, não fixo em 2 MB

`softResponseLimit = 2 MB` é escolha de implementação **do geth**, não regra do
protocolo, e o `Hello` diz que quem está do outro lado não é necessariamente geth.
`Orcamentos` pergunta pela via que o par entende: pede, observa o corte, e da próxima vez
pede o corte; quando o par entrega tudo, sobe devagar até o teto. A volta de rede é o
recurso escasso — não o byte. Ficar preso no primeiro corte ruim seria o mesmo defeito do
backoff que não recuperava, então a subida é obrigatória.

### Responder vazio não podia continuar barato

A medição que decidiu isto:

```
vazão: 4,1 blocos/s · 128 entregues · 91 reatribuídas
motivos: {respondeu vazio=86, prazo estourado=4, sessão morreu=1}
aplicando: 136 ms de 31.550 ms (0,4%)
```

Aplicar custa 0,4% — **nós não somos lentos, a rede não responde.** E 86 de 91 recusas eram
"respondeu vazio", quase sempre dos MESMOS pares: quem respondia vazio voltava ao pool e o
trabalhador seguinte o escolhia outra vez, sempre o primeiro da lista. Oito trabalhadores
gastavam o tempo insistindo com quem já havia dito que não tinha.

`Desanimo` conserta isso, e a diferença em relação ao projeto anterior é o que importa: lá
havia backoff exponencial de 30 s a 30 min **persistido** na tabela de peers, e com o pool
vazio era o próprio disjuntor que impedia a recuperação. Aqui é **ranking, não punição**, e
isso está no código e não no comentário: quem arrenda **cai no par desanimado quando não há
outro**. Desanimar muda a ORDEM de escolha, nunca subtrai par do pool — se subtraísse, um
instante em que todos estivessem desanimados viraria frente parada, que é o defeito antigo
com outro nome. O prazo é curto, em memória, e a entrada morre na primeira leitura depois de
vencer: não há método de perdoar porque não há nada a perdoar, e não há thread de limpeza
para uma coisa que some sozinha.

```
depois: 8,0 blocos/s · 128 entregues · 4 reatribuídas
motivos: {sessão morreu=3, prazo estourado=1}
```

**Vazão quase dobrada e as recusas de 91 para 4.** As duas categorias novas — `cabeçalho
vazio` (o par não tem a altura, está atrás de nós) e `recibo vazio` (ele tem o bloco e não
serve o recibo) — não apareceram nenhuma vez: as 86 respostas vazias não se dividiram, elas
DESAPARECERAM, porque eram poucos pares sendo reescolhidos em laço. A separação se paga no
dia em que um par voltar a recusar, dizendo qual das duas recusas é.

O teto agora é a rede, e o 8,0 foi medido com lote de 16: o próximo ganho está em lote
maior, não em mais trabalhadores.

### Quem desiste é o cursor, e em voz alta

A `Frente` **nunca** larga uma faixa: enquanto houver par, ela reatribui para sempre. Se
ela também decidisse desistir, existiriam dois donos da mesma decisão e o furo apareceria
no meio. Então o único lugar onde "não dá para seguir" é dito é o cursor, por
`sincronizacao.parada.segundos` sem avanço **nem entrega** — entrega conta como
progresso, porque a faixa que falta pode estar atrás de outras na fila, e matar a corrida
porque a próxima altura demorou seria confundir "a frente parou" com "a frente está
ocupada com outra faixa". E ele para **atrasado**, que é correto, em vez de seguir
furado.

## A arbitragem: o que se calcula, e o que se recusa a calcular

### A forma de Möbius, e por que ela não é elegância

Toda perna que sabemos calcular é `a·x/(b + c·x)`. V2 é ela direta. V3 **dentro do tick**
também é, e não por aproximação — sai da álgebra exata do contrato:

```
0→1:  a = L·√P²·k      b = L·Q96²·E     c = Q96·k·√P
1→0:  a = Q96²·k·L     b = √P²·L·E      c = √P·k·Q96          (k = E − taxa)
```

O que a forma compra é uma propriedade: **ela é fechada na composição.** Duas pernas em
série continuam sendo uma só `a·x/(b+c·x)`, então um ciclo de três pernas é UMA função com
três coeficientes. E aí as duas perguntas que importam deixam de ser busca:

```
rende?          ⟺  A > B                       (nenhuma divisão)
quanto entrar?      x* = (√(A·B) − B)/C        (uma raiz quadrada)
```

Sem isso, "qual o tamanho ótimo" é varredura numérica por ciclo, e com mais de mil ciclos
por bloco a varredura É o atraso.

### O tamanho vem da curva; o lucro vem da pool

A `Curva` diz **onde olhar**. Quem diz **o que se recebe** é a pool, passo a passo, com as
truncagens de cada contrato. Usar a curva para prometer lucro seria prometer wei que o
contrato não paga — e essa é exatamente a classe de erro que produz revert, porque o
arredondamento do V2 é contra quem chama.

Provado no teste, com números do tamanho dos reais: para o V2 a curva bate com o
`aplicar` da pool **wei a wei** nas duas direções, de 10¹⁵ a 10²². Para o V3 ela acompanha
dentro de um milionésimo (o contrato arredonda a taxa para cima; a curva a trata como
linear).

E o ótimo é ótimo: num desequilíbrio construído de 3,3% contra 0,5% de taxa, entrar 6,905
rende 0,0966 — e entrar a 90% ou a 110% do ótimo rende menos, dos dois lados.

### Teto de tick é teto, não recusa

A pool V3 recusa o que cruza o tick. Como o lucro cresce monotonicamente até x*, a melhor
entrada viável é `min(x*, o maior que não cruza)`, e achá-la é bisseção com número fixo de
passos. Recusar o ciclo inteiro por causa do teto seria jogar a oportunidade fora por não
saber medi-la.

### A taxa é provada, nunca estimada

A taxa é o único número da pool que a cadeia não entrega de bandeja, e é o que mais custou
antes: uma pool de 1000 ficou gravada como 5 por média de trades, e **respondeu sozinha por
13 dos 13 reverts por invariante de um dia — com 94.404 amostras.**

Então `Taxas` não faz média. Só entra taxa que **fecha exatamente** a troca observada, e só
se for **redonda** (múltiplo de um ponto-base): as factories cobram múltiplos de bp, então
taxa quebrada não é taxa incomum — é o sinal de que o estado usado estava errado.

- **V2** — inverte-se o `getAmountOut` e confere-se numa janela de três milionésimos. Quem
  chama escolhe `amountOut` e o par só confere K, então em muitas trocas nenhuma taxa fecha:
  essas não dizem nada, e aceitar a mais próxima seria transformar "o chamador pediu menos"
  em "a taxa é outra".
- **V3** — dá-se sem estado anterior, do próprio evento: a saída amarra o preço de antes
  (`√antes = √depois + saída·Q96/L`), os dois preços determinam a entrada líquida, e o que
  sobra contra a entrada bruta é a taxa. Só pode ser um dos quatro degraus que a factory
  cria, e o degrau amarra o `tickSpacing`. **É isto que põe o V3 no grafo** — antes eram
  6.111 pools V3 catalogadas e ZERO arbitragem detectada nelas.
- ~~**Contradição apaga.**~~ **Errado, e corrigido pelo item 1** (`docs/entrada.md` §7). O K só
  limita a taxa por CIMA: troca que fecha em f prova que a pool cobra no máximo f daquele
  chamador. Então duas taxas exatas não se contradizem, se ORDENAM. Medido: nas pools
  "contraditórias", 2500 vinha de 9 a 20 chamadores e 3000 de um só, que fechava 3000 em pools
  diferentes — robô calculando com a fórmula de 0,3%. Vale a **menor taxa provada por pelo menos
  dois chamadores distintos**; nenhuma assim, a pool é ambígua e fica fora. E o banco guarda a
  evidência, não a decisão (`provas_de_taxa`).
- **O pendente vale só DENTRO da transação.** Esta linha é um defeito real, e ele voltou
  pela porta dos fundos: o estado do `Sync` ficava guardado por pool sem limite de
  transação, então um `Sync` SEM `Swap` na mesma transação — mint, burn, skim — deixava o
  estado pendurado, e ele era casado com o `Swap` de uma transação muito depois. Estado
  errado mais inversão exata dá **taxa errada que fecha**, e taxa errada que fecha é
  indistinguível de taxa certa.

  Como isso apareceu: os três maiores achados de uma corrida passavam todos pela pool
  `0x7a3eb6e7ffbf9f8b65afd40c0ab0ac2bab0c5d8e`, a da cicatriz, e a taxa dela saía **500**.
  O defeito do pendente era real e foi corrigido — **mas o 500 não era consequência dele, e não
  estava errado.** O item 1 abriu a `SondaDeCurva` do old: ela media em **base 10.000**, então
  "cobra 1000" é 10%, não 0,1%. Em três janelas, 92 trocas fecharam todas exatamente em 500, de
  dois chamadores só, nenhum roteador. A pool cobra **5 bps desses dois e 10% de quem chama o
  par direto** — que é o que o nosso executor faria. Por log isso não se vê; quem fecha é simular
  a nossa chamada. Até lá ela fica fora do grafo pelo portão de chamadores.

### Não se procura arbitragem — procura-se o fluxo que a cria

Medido em 48 blocos: 513 transações com swap, **400 com um swap só**; das 113 multi-swap,
10 eram arbitragem limpa, e **todas as 10 rendiam menos que o próprio gas**. Quer dizer:
procurar arbitragem na cadeia é achar o que outro já capturou. O que ainda não foi
capturado está nos 400 swaps simples, porque é o swap simples que desloca a pool e abre a
fenda.

Daí o `Vigia`: o bloco diz quais pools se moveram, o `Indice` diz quais ciclos refazer, e
só esses são reavaliados. Reavaliar todos seria gastar mil contas para descobrir que 97%
não mudaram.

E a avaliação é **no fim do bloco**, não a cada log: dentro de uma transação a pool passa
por estados que ninguém pode arbitrar — o `Sync` intermediário de uma troca roteada é um
deles. Avaliar ali é achar oportunidade que não existiu em nenhum instante consultável.

### O portão fica na pool, não no ciclo

Primeira versão ligava tudo no grafo e filtrava o ciclo no fim: **4.566 ciclos enumerados
para 274 servirem.** 94% de enumeração jogada fora — e o custo dela não é CPU ociosa, é o
atraso no único instante que interessa, o instante em que a pool acabou de se mover. O
`Grafo` já dizia na própria ficha que só entra nele pool cuja curva sabemos calcular; agora
o `Cotador.precifica` é consultado **antes de ligar**, e o conjunto enumerado é o conjunto
utilizável.

### O gargalo medido: cobertura de taxa

```
256 blocos:  698 pools V2 + 71 V3, 705 pares
             298 taxas provadas de 769 pools (39%) · 0 contradições
             293 pools precificáveis → 194 ciclos sobre 24 pools
```

Zero contradições em centenas de taxas é o critério se defendendo. Mas a cobertura manda em
tudo, e ela é **composta**: um ciclo precisa de TODAS as pernas provadas, então 39% por pool
vira muito menos por ciclo. Repare no número que importa: 194 ciclos vivem sobre **24
pools**. É esse o núcleo líquido, e é aí que o dinheiro está.

A alavanca é observar mais — pool cuja taxa não se provou é pool que ainda não foi usada com
saída máxima na nossa janela. Não há atalho: o atalho é supor a taxa da factory, e é ele que
gravou 5 numa pool que cobra 1000.

### E o resultado, com a honestidade que ele exige

Duas corridas de 64 blocos de vigia, uma atrás da outra:

```
corrida A:  50 achados · 7 acima do gas · melhor 0,001539 BNB (entrada de 24,7 BNB)
corrida B:  34 achados · 0 acima do gas · melhor 0,000000 BNB
```

**Isso não é "achamos 7 oportunidades".** São duas janelas de 64 blocos com dados
diferentes, e a estimativa da cauda é de uma oportunidade a cada ~12.000 blocos: 64 blocos
não distingue sorte de sinal. Tratar a corrida A como descoberta seria repetir exatamente o
erro do projeto anterior, que enumerou 200.000 rotas e concluiu coisas sobre um livro que
não continha o dinheiro.

Então a janela subiu para 1.024 blocos de vigia, e entrou a única testemunha independente
que existe sem executar: **a arbitragem que TERCEIROS de fato fizeram.** Se dissermos "havia
fenda no bloco N" e alguém arbitrou as mesmas pools em N+1, a nossa conta concorda com o
dinheiro de outra pessoa. A janela de confirmação é de três blocos porque o bloco da BSC dura
750 ms e quem captura captura no seguinte — confirmar num horizonte largo não confirmaria
nada, já que toda pool líquida é arbitrada em algum momento.

A concordância vale nos dois sentidos, e por isso ela entra no relatório e **não numa
asserção**: achado que ninguém arbitrou pode ser fenda que ninguém viu, ou conta nossa que
está errada. Sem executar, os dois casos são indistinguíveis.

```
1.024 blocos de vigia:
   2.218 achados · 389 acima do gas
   lucro:   mediana 0,000000 · maior 0,222484 BNB
   entrada: mediana 0,000176 · maior 103,698774 BNB
   custo de avaliar: 2.323 µs por bloco (o bloco dura 750.000)
   confirmação: 85 alturas com arbitragem de terceiro · 33 dos 389 confirmados (8%)
```

**A latência está resolvida:** 2,3 ms para avaliar um bloco contra 750 ms de orçamento —
0,3%. Detectar dentro da janela do bloco não é o problema.

**E "389 acima do gas" NÃO é 389 oportunidades.** São 1 a cada 2,6 blocos, contra a
estimativa anterior de 1 a cada ~12.000 — quatro mil vezes de diferença, e quando uma
medição discorda de uma estimativa por três ordens de grandeza a suspeita recai sobre a
medição. Aqui, porém, os dois números medem coisas diferentes: o antigo contava arbitragem
**executada e limpa** (o que alguém capturou), este conta **ciclo cuja conta fecha positiva
depois do gas**. A distância entre os dois é competição e capital.

O relatório mostra a distância: a mediana da entrada exigida é 0,000176 BNB — poeira — e o
melhor caso pede **103,7 BNB para tirar 0,22**, que é 0,2% de retorno sobre o capital. Ou
seja, o filtro que falta não é o gas: é retorno sobre capital, e "acima do gas" é critério
fraco demais para chamar de oportunidade.

E daí o terceiro achado do dossiê, que é sobre mim e não sobre a cadeia: **eu estava
comparando unidades diferentes.** O maior achado de uma corrida era um ciclo de **USDT**
rendendo 0,0118 USDT, e o filtro comparava esse número com `GAS = 0,0003` **BNB** — uma
grandeza ~700 vezes menor na mesma escala numérica. Logo "32 acima do gas" era contagem de
duas moedas somadas, e o achado que liderava o relatório na verdade **não pagava o próprio
gas**.

Gas é pago em BNB, então só se compara com gas quem lucra em BNB. Enquanto não houver
conversão de preço, o filtro de gas vale só para ciclo que parte de WBNB; o retorno em
pontos-base, por ser adimensional, vale para qualquer numerário. O dossiê achou isso porque
imprime o ciclo com endereço e estado — um total não teria como denunciar.

Daí o segundo critério, que faltava: **`retornoEmPontosBase`**. Gas é o piso do custo;
capital é o que decide. "Acima do gas" trata 0,0003 BNB de lucro sobre poeira e 0,22 sobre
103 BNB como o mesmo fato, e não são o mesmo fato — o melhor achado de 1.024 blocos rendia
**21 pontos-base** e passava folgado pelo filtro de gas. O corte agora é conjunto: acima do
gas **e** acima de 30 pontos-base.

Por isso, também, o teste imprime um **dossiê**: os maiores achados, perna por perna, com endereço de
pool, par, taxa e reservas na altura. "389 acima do gas" é afirmação minha sobre conta minha;
endereço de pool com reserva e taxa é coisa que se confere no explorador da cadeia. Enquanto
não houver execução, conferível por terceiro é o padrão de prova mais alto disponível — e o
projeto anterior morreu de afirmar números que ninguém tinha como checar.

### Um par por trabalhador, ou a medição mede o pool

`POOL_MINIMO` nos testes de rede é derivado de `sincronizacao.pedidos.em.voo`, não escolhido:
a frente abre uma thread por pedido em voo e cada uma arrenda uma sessão, então com menos par
que trabalhador sobra trabalhador e o pior par volta a ditar o ritmo. Com 4 ativas o
`AnalisadorTest` parava com zero blocos de forma intermitente e reprovava por condição de
rede — reprovar por causa da rede é ruído, não informação. Com 8, passou.

### A latência é o orçamento, e o orçamento é o bloco

Detectar no bloco N não serve se a conta demorar mais que o bloco: fenda vista em N e
avaliada em 900 ms é fenda de outra pessoa. Medido: **2,3 ms para avaliar um bloco**, contra
750 ms de orçamento. Aplicar os recibos já se sabia irrelevante — 1,1 ms por bloco, 0,2% do
tempo do cursor. Nenhum dos dois é o gargalo, e é bom saber disso antes de otimizar o que
não precisa.

### O estado em que isto para: o detector produz falso positivo, e eu sei disso

Última corrida verde, 256 blocos de vigia:

```
349 taxas provadas de 820 pools (43%) · 2 contradições
346 ciclos sobre 43 pools
177 achados · 6 pagam o próprio gas E passam de 30 pontos-base
maior: 3 pernas · entrar 1.492,58 BNB · tirar 56,50 · 378 pontos-base
dos 6, confirmados por terceiro: ZERO
```

**Uma arbitragem de 3,78% valendo 56 BNB não fica parada na BSC.** Se existisse, seria
tomada no bloco seguinte, e nenhum dos seis foi confirmado por ninguém. Então o número é
falso positivo, e registrá-lo como achado seria exatamente o que o projeto anterior fazia.

As três hipóteses, em ordem de suspeita, todas verificáveis pelo dossiê:

1. **`tickSpacing` derivado de taxa errada no V3.** O espaçamento sai do degrau da taxa, e
   o degrau sai da nossa recuperação. Taxa recuperada alta demais dá espaçamento grande
   demais, `maximoNoTick` grande demais, e então `aplicar` NÃO recusa uma entrada que na
   verdade cruza ticks — calculamos saída como se a liquidez atual valesse muito além de
   onde ela vale. É o mecanismo mais direto de lucro fantasma que existe aqui, e o único em
   que a nossa própria recusa falha em nos proteger.
2. **Taxa V2 errada numa perna.** A pool `7a3eb6e7…` continua saindo com **500** depois de
   escopar o pendente por transação, e continua aparecendo nos maiores achados. 5 bps de
   erro não explicam 378 pontos-base sozinhos, mas explicam viés sistemático a nosso favor.
   *Resolvida depois:* a taxa é 500 **para os dois chamadores dela** e 10% para quem chama o par
   direto — a cicatriz estava em base 10.000. Não é erro de recuperação: é taxa por chamador.
3. **Token do meio sem liquidez real.** O numerário é conferido por endereço, mas o token do
   MEIO de um ciclo de três pernas não é — e par falso contra WBNB real produz fenda
   aparente enorme numa pool de pó.

O que está provado é a aritmética: a curva bate wei a wei com o contrato, o ótimo é o
máximo, a taxa só entra exata e redonda. O que não está provado é que a ENTRADA da
aritmética é verdadeira — e as três hipóteses acima são todas sobre entrada, não sobre
conta. É a mesma lição de novo: o projeto não morre de bug de cálculo, morre de calcular
certo sobre dado errado.

### Hipótese 1, confirmada: empate de degrau no V3

`degrauQueExplica` devolvia **o primeiro** dos quatro degraus que cabia na folga de um wei.
Em swap pequeno, a diferença entre 100 e 10.000 milionésimos cabe DENTRO dessa folga, então
vários degraus serviam e a resposta saía por ordem da lista, não por evidência.

E o empate é pior que taxa errada, porque o degrau amarra o `tickSpacing`: espaçamento grande
demais faz `maximoNoTick` grande demais, e então `aplicar` **aceita** entrada que cruza tick.
A única recusa que nos protegia de calcular além da liquidez que vemos deixa de disparar, e o
lucro fantasma cresce com o quanto passamos do tick.

O conserto é o mesmo princípio de sempre: dois degraus que servem não é resposta, é empate, e
empate se descarta. Efeito medido: concordância com terceiros subiu de **7% para 20%** dos
achados, e de 0 para 1 dos que pagam o próprio gas.

### Hipótese 4, que o dossiê entregou de graça: estado impossível

Com os endereços na mão, o número que salta é este:

```
PoolV3[sqrtPrecoX96=2961441359853447859639515620,
       liquidez=1410567031761411235199949998352313826428734045462, tick=-65737]
```

**`liquidez` = 1,41e48.** O `liquidity` do contrato é `uint128`: teto 3,4e38. Está dez ordens
de grandeza fora — e logo abaixo de 2^160, que é a cara de um `sqrtPriceX96` ocupando a
palavra da liquidez. Preço e tick do mesmo evento fecham entre si (tick −65737 ⇒ √P ≈
2,96e27 ✓), então o evento é real: o que não fecha é o nosso mapa de palavras para ELE.

**A leitura da época — "fork na BSC com outro arranjo de palavras" — estava ERRADA.** O item 1
converteu os dois valores para hexadecimal: **160 bits exatos, os dois. São endereços.** O
`Mint` carrega o `sender` NÃO indexado na palavra 0, e o `amount` vai para a palavra 1; o
`Cursor` lia a palavra 0 de `Mint` e `Burn`, então todo Mint somava um endereço à liquidez. O
`Swap` estava sendo lido certo — por isso preço e tick fechavam — e quem estragava a pool era o
Mint seguinte. O old já tinha fechado essa ferida em `EventoDex.liquidezV3`, e a migração a
reabriu (`docs/entrada.md` §1).

Esse estado entrava na curva e saía como oportunidade de **1,6 bilhão de pontos-base**. E o
perigo não é o absurdo do número: é que ele atravessa todos os filtros a jusante, porque gas
e retorno sabem comparar, não duvidar.

A guarda abaixo continua certa — mas, até o decodificador ser corrigido, ela também **apagava do
catálogo toda pool V3 que recebia liquidez**, que são justamente as vivas. Guarda que recusa é
necessária; é CONTAR o descartado que mostra quando ela está recusando demais.

Então quem duvida é o TIPO, no ponto de entrada: `EstadoV3` recusa `liquidez` fora do
uint128, `sqrtPriceX96` fora do uint160 e tick fora da faixa; o catálogo trata a recusa como
"pool que não sabemos ler", descarta e CONTA — roteiro de qual fork falta, no mesmo espírito
dos códigos eth desconhecidos. Descartar custa uma rota; ler errado custa gas.

E a recusa cobrou o próprio preço na hora: `aplicarLiquidezV3` soma o delta do `Mint`/`Burn`
à liquidez, e **liquidez negativa é possível ali sem que o contrato esteja errado** — a nossa
base começa no primeiro `Swap` que vimos, então um `Burn` de posição aberta antes da nossa
janela tira mais do que sabemos que havia. Com a validação nova isso virou exceção num
caminho que ninguém pegava. Zerar seria inventar um número; a pool sai do catálogo, que é
dizer "não sabemos a liquidez desta pool" — e é a verdade.

### O efeito das duas correções, medido

Mesmo caminho, mesmas 256 alturas de vigia, antes e depois de recusar o empate de degrau e o
estado impossível:

```
                        antes                   depois
maior retorno           1.678.955.235 bp        40 bp
melhor achado           0,56 → 43,37            384,49 → 0,306
                        (771.142 bp)            (7 bp)
acima do gas            7                       1
acima de 30 bp          7                       0
V3 descartadas          —                       1
tocam pool arbitrada    20%                     21%
```

**Os 16,8 milhões por cento eram estado impossível.** Uma pool, uma palavra mal lida, e ela
produzia sozinha os três maiores achados de toda corrida. Recusá-la derrubou o máximo para
0,4% — e o que sobrou tem a cara do que um mercado eficiente deve ter: fenda mínima, mediana
de **um** ponto-base, quase nada acima do limiar de capital.

Ou seja: a resposta honesta para "dá para arbitrar?" hoje é **ainda não, e agora sabemos por
quê em vez de achar que sim.** O caminho está inteiro e a aritmética está provada contra os
contratos; o que falta é cobertura de taxa (34-45% das pools) e uma fenda que sobreviva ao
capital. O detector deixou de mentir, que é o pré-requisito de tudo o mais — porque detector
que mente não fica melhor com mais blocos, fica pior.

## Itens 1 e 2: dado confiável e operação na cabeça

Dois agentes em paralelo, cada um lendo o old antes de escrever — e três das feridas do item 1
o old já tinha fechado e a migração reabriu. O detalhe, com os números, está em
`docs/entrada.md` (item 1) e `docs/cabeca.md` (item 2). O que mudou o desenho:

```
item 1 — confiar no dado de entrada
  1,41e48                 não era fork V3: palavra do Mint (endereço somado à liquidez)
  PancakeSwap V3          invisível (outra assinatura de Swap): pools V3 266 → 682
  taxa 7a3eb6e7           500 está certo; a cicatriz estava em base 10.000 (10% direto)
  contradição             o K ordena as provas; banco guarda evidência, não decisão
  imposto de token        35% dos tokens observados cobram; portão exige imposto zero provado
  cobertura de taxa       38% → 48–52%
  portão de chamadores    pool movimentada sem roteador público entre os chamadores: fora

item 2 — operar na cabeça
  cursor                  segue a cabeça e nunca termina sozinho; reorg recua até âncora
  latência da cabeça      leque 4 + prazo por pedido: mediana 363 ms, p90 540 ms
  platô do pool           é de ABERTURA (922 contra 51); Status não muda — medido
  "defeito do Snappy"     era Disconnect em claro: o motivo da queda se perdia
  eth/70                  não vale: sem ganho de I/O no fonte atual, e exigiria declarar faixa
```

**Um erro de coordenação que valeu registrar:** a primeira versão do portão de chamadores
tirava 68 pools, quase todas negociadas só por `0x10ed43c7…` — o **roteador** da PancakeSwap,
que chama o par em nome de todos. Pool só de roteador parecia "um chamador" sendo a mais
aberta da BSC. Roteador conta como todos, nunca como um; a versão final tira 18.

## Conferência na cadeia: o node contra o próprio contrato

Até aqui toda prova era do node sobre si mesmo: aritmética contra a fórmula, dossiê imprimindo o
próprio estado. `ConferenciaNaCadeiaTest` põe uma testemunha independente: congela o catálogo numa
altura H a poucos blocos da cabeça e lê cada pool **do contrato, em H**, por `eth_call`. O RPC existe
só no teste; o runtime continua sem depender dele.

A comparação é exata, e pode ser: tudo que muda reserva de V2 emite `Sync`, e tudo que muda preço ou
liquidez ativa de V3 emite `Swap`, `Mint` ou `Burn`. Se o node aplicou todos os eventos até H, o
estado dele em H é o do contrato em H, wei a wei.

### O instrumento foi conferido antes do node

Três rodadas reprovaram pelo instrumento, e cada uma deixou uma regra:

- **Janela de estado.** Nós públicos guardam estado por **90 a 110 blocos** (medido). 6.676
  `eth_call` em 334 lotes não cabiam nisso — não era limite de taxa (600 de 600 em 30 lotes), era
  tempo. Virou **Multicall3**: poucas chamadas, e todas as leituras de um agregado no MESMO estado.
  `Multicall3Test` prova que o caminho agregado lê exatamente o que a chamada direta lê, inclusive a
  falha.
- **Cabeça.** Com a cabeça do `Status` a foto saiu a 168 blocos da rede: o `Status` é a cabeça do par
  **no aperto de mão**. A cabeça vem do anúncio (`NewBlockHashes`), como no node de produção.
- **Sem resposta não é divergência.** Contado à parte, e separado por leitura.

### O que a conferência achou — e o que isso mudou

A primeira conferência completa (foto a 11 blocos) deu zero divergência em estado, e três no dado
de taxa, **todas conservadoras e todas erradas**:

```
PancakeSwap V1  taxa provada por troca 3000 · o roteador cobra 2000
PancakeSwap V2  taxa provada por troca 3000 · o roteador cobra 2500
V3 de fork      espaçamento derivado 200    · o contrato diz 2000
```

Nenhuma se resolve observando trocas. O K só limita a taxa por cima, e um robô que calcula com a
fórmula de 0,3% "prova" 3000 em qualquer pool mais barata. E o mapa taxa→espaçamento não é universal:
a Uniswap V3 tem `3000→60`, a PancakeSwap V3 tem `2500→50`, e o fork tinha `10000→2000`.

### A identidade da pool pelo endereço

O endereço de uma pool é `CREATE2(deployer, sal, hash do bytecode)`. Recalculá-lo e bater prova de que
contrato a pool é — e o bytecode fixa a fórmula do K. Em V3 a taxa entra no sal: achar a pool com uma
das taxas da tabela da factory prova a taxa **e** o espaçamento. Em V2 de taxa fixa, prova a taxa.

Não é a "taxa suposta pela factory" que P8 proíbe: aquilo era palpite sobre a pool; isto é a pool
dizendo de que contrato é. E só entra factory **provada contra a cadeia**, com a procedência no código:

```
PancakeSwap V2/V1, ApeSwap, SushiSwap, Biswap, MDEX
    hash lido da própria factory · CREATE2 bate com getPair real (3 de 3 cada)
    taxa V2 medida pelo roteador: Pancake V2 2500 · V1 2000 · Ape 2000 · Sushi 3000
    Biswap e MDEX guardam a taxa POR PAR (swapFee 1 e 2; getPairFees 20 e 30): sem taxa fixa
PancakeSwap V3   deployer separado da factory · 4 de 4 pools reais
Uniswap V3       20 de 20 pools reais
BakerySwap       fora: não expõe o hash e não teve par para provar
```

`Tarifario` decide a taxa e o espaçamento que valem: identidade vence; prova por troca só onde a
identidade não fixa a taxa (Biswap, MDEX, V2 desconhecida); e as duas se conferem — prova V2 abaixo da
taxa da factory, ou V3 diferente, é estado errado e a pool fica fora. V3 sem identidade não se
precifica.

### A conferência depois da identidade

Foto a 8 blocos da cabeça, última leitura a 19:

```
v2 reservas                    967 batem · 0 divergem
v2 factory pela identidade     774 · 0
v3 preço / tick / liquidez     514 · 0 cada
v3 factory pela identidade     483 · 0
v3 taxa / espaçamento          483 · 0 cada
v2 cotação (taxa do tarifário) 761 · 0          (antes: 476 batendo, 1 divergindo)

identidade: Pancake V2 733 · Pancake V3 378 · Uniswap V3 105 · Biswap 13 · V1 12 · Ape 9 · MDEX 5 · Sushi 2
fora: 99 V2 e 39 V3 de factory desconhecida · 94 V2 sem par aprendido · 0 em conflito
```

**Zero divergências em todas as categorias.** O dado de estado e o dado de identidade do node batem com
o contrato na mesma altura, e a taxa que ele usa para precificar reproduz a cotação do roteador em 761
pools.

### O que continua sem conferência, e fica dito

- **Imposto de token** e **taxa cobrada de quem chama o par direto** (a `7a3eb6e7`): exigem simular a
  transação, não ler estado.
- **As 99 V2 de factory desconhecida** precificam por prova de troca, que é teto: nunca fabrica lucro,
  mas pode subestimar.
- **É uma altura por corrida.** Estado bateu em duas fotos independentes; identidade, em uma.

## O que ainda não existe

- **Não publicamos registro próprio.** Quem nos pergunta "de que rede você é"
  não recebe resposta. Custo real: clientes que filtram par por rede antes de
  abrir TCP não nos priorizam.
- ~~Não há RLPx.~~ **Feito**, e o `Status` passou a ser o critério autoritativo de
  cadeia: o ENR virou dica (ver *A janela de pedidos em voo*).
- **Anunciamos o genesis como nossa cabeça — e isso fica, por medição.** O item 2 contou as
  quedas: o platô é de abertura, pelo menos 83% das falhas independem do `Status`, e nenhum
  par nos desafiou pedindo o cabeçalho anunciado. Se um dia apontar para ele, o valor honesto
  e a armadilha (`DificuldadeDaRede` do old anunciava por cima) estão em `docs/cabeca.md`.
- **Não negociamos a extensão `bsc`.** Hipótese mais forte para os "peer inútil" antes do
  `Status`; não testada. E o platô aparece nos testes: na suíte completa a `LotacaoTest`
  zerou por 5 s (`2→1→2→1→0→2`) e reprovou; isolada, passou 2 de 2, com mínimo de 2 e de **1**
  conversa. A lotação sustenta 1 a 4 conversas de 25, e o teste passa na margem de uma queda.
  Não é regressão (nada da integração toca `sessao/` ou `rede/`), e afrouxar o teste esconderia
  o sintoma: o conserto é do platô.
- **O arbitrador não está ligado ao `Main`.** O node de produção sincroniza e prova taxa, mas
  o `Vigia` só roda em teste: falta um índice de ciclos que se remonte conforme taxas se provam.
- **`Catalogo.esquecer`.** A reorganização reaplica estado absoluto, mas `Mint`/`Burn` do V3
  são deltas; pool mexida só por liquidez no trecho órfão fica com o delta errado.
- **Token do meio impostor.** Os três maiores achados de uma corrida passavam por
  `0x2ab8a4dd…7777`: passou por não ter imposto VISTO, não por estar limpo.
- **O custo de avaliar não cresce linear.** ~1,4 ms com 374 ciclos, 55,5 ms com 2.794 — ainda
  cabe no bloco, mas cresceu 40× com 7× mais ciclos.
- **Não projetamos a mempool.** É a próxima etapa (premissas §9, P21): decodificar o swap pendente
  — que já recebemos e descartamos (`NewPooledTransactionHashes`, `Transactions`) — projetá-lo sobre as
  reservas com `Pool.aplicar` e avaliar a fenda que ele abre ANTES do bloco, para ser primeiro. Duas
  precondições, e a hora as mediu: o avaliador tem de caber no bloco (grafo podado; caímos a 0,7
  bloco/s quando ele explodiu) e a base tem de ser a cabeça (projeção sobre reserva velha é lixo). O
  projetado é overlay, nunca gravado.
- **Não sabemos executar.** Detectar e dimensionar estão provados; mandar transação
  não existe — exige contrato que faça as pernas atomicamente, assinatura e política
  de gas. O `Achado` carrega a altura justamente porque oportunidade da altura N não
  se manda no bloco N+2, e essa é a primeira regra que a execução vai ter de honrar.
- **O gas é constante no relatório.** 0,0003 BNB foi medido, mas gas é preço de
  mercado no instante do envio; o cabeçalho carrega o `baseFee` e hoje o `Cabecalho`
  só guarda número, hash e pai.
- ~~Não há crivo.~~ **Feito.** A regra escolhida não foi nenhuma das duas que eu
  havia proposto: é determinística. O fork id é um CRC32 encadeado sobre o
  genesis mais cada fork ativado, então a sequência inteira dos 23 cortes válidos
  da BSC se calcula offline. Um par é da nossa rede se declara **qualquer** um
  deles — e tem de ser qualquer um, porque o bootnode publica o do genesis
  (`0x3f2e9ae4`) por não seguir a cadeia, enquanto os pares vivos publicam o de
  hoje (`0xd38cc282`, após Pasteur). Aceitar só o corte atual descartaria os
  bootnodes; aceitar por configuração quebraria no próximo fork.

  Duas medições independentes concordaram: o `old` mediu `d38cc282` no Status
  (2026-09-07) e o node novo mediu o mesmo hash no ENR, por UDP, sem TCP.
  Resultado: **17 pares da BSC fora das sementes em 18 s**, separados dos outros
  36 fork ids que circulam na mesma DHT.
