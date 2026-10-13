# Projeto — sincronização e o caminho até o arbitrador

Desenho, ainda não implementado. Escrito antes do código, como manda o laço.

> **SUPERADO EM TRÊS PONTOS, por medição.** O que segue vale, menos:
>
> 1. **A corrente `Orquestrador → Node → serviços` cai.** O `Main` já é a raiz de
>    composição, e eu havia apagado essa mesma corrente dias antes por ser
>    "indireção sem dono". Repropô-la foi erro meu, não do esboço de quem pediu.
> 2. **"Absolutamente tudo para o arbitrador" cai** — ver `premissas.md`, P7. O que
>    a *cabeça* produz vai por evento; o que o *histórico* produz vai para o
>    catálogo, e o arbitrador lê catálogo como snapshot, nunca como fluxo.
> 3. **As 25 conversas não se dimensionam por slot, e sim por banda.** Medido: uma
>    sessão puxa 2,1 MB/s no catch-up, e 21 slots pediriam 350 Mbps. Ver a seção de
>    sincronização em `arquitetura.md`.
>
> O que sobreviveu inteiro: as três costuras e o que cada uma apaga; a fila como
> fronteira de prazo com descarte declarado; `Sessoes` como registro único; e as
> três decisões da `Lotacao` — todas já implementadas e com teste de módulo.

## A imagem

```
Main
 └── Orquestrador                     liga e desliga na ordem certa
      └── Node
           ├── Descoberta             acha nós                        minutos
           ├── Sessoes                guarda a sessão de cada nó
           ├── Lotacao                mantém 25 conexões abertas      segundos
           └── Sincronizacao          pede o bloco que falta          segundos
                     │
                     ▼   Registro            ── costura 2
              Processador                    lê registro a registro
                     │
                     ▼   Evento              ── costura 3
                Arbitrador                   decide                   ≤ 45 ms
```

## As três costuras, e o que cada uma apaga

O desacoplamento não é "usar interface". É **escolher que informação não
atravessa**. Cada costura apaga uma coisa de propósito, e é o *tipo* que
garante — não a boa intenção de quem escreve o próximo método.

### Costura 1 — `Descoberta → Sessoes` · apaga *como o nó foi achado*
Já existe: `Consumer<NoRemoto>`. Quem recebe não sabe se o nó veio de semente,
de vizinho aprendido ou de estranho que nos procurou. Não precisa saber.

### Costura 2 — `Sincronizacao → Processador` · apaga *a origem do registro*
É o ponto central do pedido. O `Processador` não pode saber se aquele registro
chegou porque **pedimos** ou porque o par **anunciou**.

A garantia é de tipo: `Registro` **não tem campo de origem**. Sem campo, não há
`if (origem == ANUNCIO)` possível — nem hoje, nem daqui a seis meses quando
alguém tiver pressa. Se um dia a origem for mesmo necessária, ela entra como um
tipo diferente, não como uma bandeira dentro do mesmo.

Repare o que isso compra de graça: o `Processador` fica exercitável com registro
vindo de arquivo, sem rede nenhuma.

### Costura 3 — `Processador → Arbitrador` · apaga *o trabalho de decodificar*
O arbitrador recebe `Evento` de domínio, nunca bytes. "Absolutamente tudo" vai —
e é justamente por isso que esta costura precisa de teto e de política de
descarte declarada (abaixo).

## Toda costura é também uma fronteira de prazo

O erro-raiz do projeto anterior: sete tarefas com prazos de 45 ms a horas
dividindo o mesmo laço, e o atraso do leitor em **1.844 ms**.

Então costura 2 e costura 3 **não são chamada de método** — são fila com
capacidade fixa e consumidor próprio. Chamada direta faria um `Processador`
lento bloquear a thread que lê a rede, que é literalmente o atraso de 1.844 ms
renascendo.

### `Fila<T>` — a única peça genérica do desenho
| | |
|---|---|
| Frase | Leva itens de um estágio ao seguinte sem deixar o produtor esperar. |
| Não faz | Não transforma, não decide, não sabe o que carrega. |
| Muda quando | Muda a política de contrapressão. |
| Nasce genérica? | Sim — e só porque tem **dois usos reais** no mesmo commit. |

**A decisão que não pode ser acidente:** o que acontece quando enche.
Bloquear o produtor propaga a lentidão para trás até a thread de rede — proibido.
Então: **descarta o mais novo e conta**. O contador é saída de primeira classe;
fila que descarta em silêncio é a mesma mudez que já custou caro aqui.

## As peças

### `Orquestrador`
| | |
|---|---|
| Frase | Liga e desliga as peças do node na ordem certa. |
| Não faz | Não conhece protocolo, não conhece bloco, não decide nada. |
| Muda quando | Muda a composição do node. |

Existe por causa do **desligamento**, não da ligação: parar na ordem errada
perde o que está na fila. Ligar é fácil; parar direito é que precisa de dono.

### `Node`
| | |
|---|---|
| Frase | Reúne o que o node faz na rede. |
| Não faz | Não processa registro, não decide arbitragem. |
| Muda quando | Entra ou sai um serviço de rede. |

### `Sessoes` — o registro único
| | |
|---|---|
| Frase | Guarda a sessão de cada nó conhecido. |
| Não faz | Não abre conexão, não escolhe quem conectar, não fala com a rede. |
| Muda quando | Muda a identidade de uma sessão. |

**Por que desce para baixo de tudo:** hoje a `Descoberta` guarda o próprio mapa.
Se a sincronização também gerenciar sessões, passam a existir dois donos da mesma
coisa — e dois donos do mesmo estado foi como o `old` chegou a um
`SessionsService` de 683 linhas. O registro é um só; `Descoberta`, `Lotacao` e
`Sincronizacao` pedem a ele.

Isto **não** é fronteira de prazo: é consulta de mapa, chamada direta.

### `Lotacao` — as 25 ativas
| | |
|---|---|
| Frase | Mantém aberto o número de conexões que o node precisa. |
| Não faz | Não pede bloco, não escolhe o que pedir. |
| Muda quando | Muda a política de ocupação. |

Aqui é onde o projeto anterior morreu, e a cadeia está documentada em
`spec-2-peers.md`:

> somos leech → o par nos evicta → o socket morre em silêncio → sem `setSoTimeout`,
> sem PING de saída e com o watchdog desligado, o zumbi fica `CONNECTED` e ocupa
> um dos 25 slots **para sempre** → `completar()` não repõe → as rodadas pedem a
> quem não existe → a thread de sync morre com `lidos==0` → o backoff exponencial
> de 30 min mantém os peers bons barrados.

Então três decisões entram no desenho, não como conserto depois:

1. **"Viva" se define por entrada de protocolo qualquer**, não por bloco. Medir
   mudez só por bloco derruba sessão saudável durante catch-up pesado.
2. **A reposição é do mesmo laço que detecta a morte.** Um slot vago é um slot
   que se preenche na mesma rodada; não existe caminho em que a lotação caia e
   ninguém tenha a tarefa de recompô-la.
3. **Queda pós-handshake não persiste castigo.** Backoff exponencial gravado no
   banco só vale para veredicto de handshake (outra rede, cheio, mudo,
   inalcançável). Uma rodada ruim não pode barrar um peer bom por 30 minutos.

Aumentar o teto de 25 **não** é solução: zumbi ocupa qualquer teto. O conserto é
detecção.

### `Sincronizacao`
| | |
|---|---|
| Frase | Pede à rede o bloco que ainda falta. |
| Não faz | Não decodifica, não guarda, não decide a quem conectar. |
| Muda quando | Muda a estratégia de busca por altura. |

Recebe o nó novo pela costura 1 e o entrega a `Sessoes`. Pede por número. Tudo
que volta — pedido ou anúncio — vai para a costura 2 pelo mesmo caminho.

**Não morre quando não lê nada.** No `old`, `if (lidos == 0) return;` congelava a
sync até um anúncio novo aparecer. Aqui, zero lido é motivo para dormir e tentar
de novo, e só se encerra com parada explícita ou sem sessão viva.

### `Processador`
| | |
|---|---|
| Frase | Transforma um registro cru em eventos de domínio. |
| Não faz | Não fala com a rede, não sabe de onde veio o registro, não arbitra. |
| Muda quando | Muda o que se extrai de um bloco. |

### `Arbitrador`
| | |
|---|---|
| Frase | Decide o que fazer com um evento. |
| Muda quando | Muda a regra de decisão. |

Único com prazo de 45 ms. É por isso que ele fica atrás de fila: nada a montante
pode empurrar latência para dentro dele.

## Os tipos que atravessam

```
Registro   selado — o que a rede entregou, sem origem
Evento     selado — o que o processador entendeu
```

Selados pelo mesmo motivo que `MensagemDiscovery`: quando entrar um caso novo, o
compilador aponta cada lugar que precisa decidir. No `old`, pedido não tratado
caía em `responderVazio` e ninguém ficava sabendo.

## Direção das dependências

```
sincronizacao: vo, util, sessao, rede, fila
processamento: vo, util, fila
arbitragem:    vo, util
```

`processamento` **não** pode importar `sessao` nem `rede` — é a costura 2 virada
regra de build. `arbitragem` não importa ninguém de rede. `ArquiteturaTest`
reprova se alguém tentar.

Nenhum serviço importa outro serviço: eles se falam por `Fila`, e quem os liga é
o `Orquestrador`.

## O que precisa ser decidido antes de escrever

1. **Capacidade de cada fila e o que fazer no estouro.** Proposta: descartar o
   mais novo e contar. Alternativa: descartar o mais velho (bom para preço,
   ruim para sequência de blocos).
2. **O que é `Registro`.** Cabeçalho, corpo e recibo são três coisas com tamanhos
   muito diferentes — um recibo de bloco cheio não é barato de manter na fila.
3. **"Absolutamente tudo" para o arbitrador.** A BSC fecha bloco a cada 0,45 s.
   Mandar tudo é uma decisão de vazão, não só de arquitetura: precisa de um
   número medido antes de virar padrão.
4. **`Sessoes` como registro único** implica mexer na `Descoberta` que já
   funciona. É a hora certa — o teste de módulo existe e protege a mudança.
