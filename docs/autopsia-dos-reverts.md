# Autópsia dos reverts — onde erramos e onde acertamos

Levantado em 12/09/2026 sobre `bnb_chain`: 4.836 ensaios e 8.434 pools com
desfecho observado, janela de **3 dias** (06 a 09/09/2026).

O `ensaio` é simulação: cada linha é uma tentativa de executar uma rota por
`eth_call` antes de gastar gas de verdade.

---

## 1. O primeiro achado não é sobre o modelo. É sobre o instrumento.

| resultado | ensaios | % |
|---|---|---|
| **SEM_RESPOSTA** | **3.459** | **71,5** |
| SEM_LUCRO | 836 | 17,3 |
| REVERTEU | 533 | 11,0 |
| TOKEN_NAO_MOVE | 5 | 0,1 |
| **PASSARIA** | **2** | **0,04** |

E o motivo dos 71,5%:

```
http 403                       2.838   (58,7% de TODOS os ensaios)
nenhum endpoint respondeu        530
timeout de 1000ms                 91
```

> **O subsistema que existia para validar passou 71,5% do tempo medindo a própria
> falha de RPC.** Mais da metade de tudo foi `HTTP 403` — o provedor nos recusando.

É o argumento mais forte possível para a premissa de fonte única. Não é que o RPC
fosse lento: é que ele **não respondia**, e sem resposta não há validação, não há
aprendizado e não há como saber se o modelo presta.

---

## 2. Quando mediu, o erro era de ordem de grandeza — não de margem

O `old` acertou em classificar o quanto faltava. A tabela é diagnóstico puro:

| faltou | ensaios | o que significa |
|---|---|---|
| < 10 bps | 2 | ordem do arredondamento |
| 10–25 bps | 3 | ordem do edge |
| 25–100 bps | **128** | **ordem da taxa** — taxa errada |
| 100–500 bps | 196 | grande demais para ser taxa |
| 500–2.000 bps | **191** | **curva errada** |
| > 2.000 bps | 70 | **outra pool** (média: 9.103 bps) |
| `: k` | **234** | invariante do par violada |

**Só 5 de 836 (0,6%) erraram na ordem do arredondamento.** O resto errou por
ordens de grandeza.

Isso não é calibração — é insumo errado. E os 234 `: k` são a assinatura exata do
fee errado: pedir mais saída do que a curva permite faz o par rejeitar por
invariante. Casa com a cicatriz medida: a pool `7a3eb6e7…` estava gravada com
**5 bps** e **94.404 amostras**, e cobra **1000** — ela sozinha respondeu por 13
dos 13 reverts por invariante de um dia.

**94 mil amostras e a inferência errou por um fator de 200.**

---

## 3. Os reverts do nosso próprio contrato

| motivo | vezes | de quem é |
|---|---|---|
| `(revert sem dados)` | 379 | pool alheia |
| `TokenForaDaPool()` | **51** | **nosso** — a rota tinha token que não é da pool |
| `FalhaNaChamada()` | 48 | nosso |
| `Not Allowed` | 20 | token alheio |
| `Panic(0x11) estouro aritmético` | **16** | **nosso** |
| `input 0 or 1 <= 0` | 10 | nosso/rota |
| `caller is not router` | 8 | pool alheia |

`TokenForaDaPool` 51 vezes diz que o grafo apontou para uma pool cujos tokens não
eram os da perna. Erro de **estrutura**, não de preço. E `Panic(0x11)` é estouro
aritmético no nosso código.

---

## 4. Onde acertamos, e vem tudo para o projeto novo

1. **As reservas estavam certas.** 25.411 confirmadas contra 587 erradas —
   **97,7%**. A autópsia 1 já havia provado 320/320 a 1e-9. O preço nunca foi o
   problema.
2. **A bucketização por `faltou_bps` é um instrumento excelente.** É o que permite,
   meses depois, saber *onde* o modelo errava sem reexecutar nada. Vem.
3. **O contrato fez o trabalho dele: 533 reverts = 533 vezes sem perder
   principal**, só gas. As travas próprias (`TokenForaDaPool`, `FalhaNaChamada`,
   reentrância) funcionaram.
4. **Instrumentar o ensaio foi o acerto que paga esta análise.** Sem os 4.836
   registros com motivo, nada disto seria conhecível.
5. **A detecção funcionava.** 63.433 arbitragens alheias observadas. A visão
   estava lá; a captura não.

---

## 5. O tamanho real do mercado — com a contagem dupla corrigida

`pool_desfecho.valor_alheio_bnb` conta o **mesmo dinheiro uma vez por pool**. A
prova está nas três primeiras linhas do ranking: `USDT/AFF`, `AiFi/AFF` e
`USDT/AiFi` com **valor idêntico e os mesmos 32 eventos** — é um triângulo só,
contado três vezes.

O ciclo médio tem **2,93 pernas**. Então:

```
soma por pool          4,478 BNB
÷ 2,93 pernas       ≈  1,53 BNB de dinheiro REAL
em 3 dias           ≈  0,51 BNB/dia   ≈ US$ 306/dia
```

Esse é **todo** o mercado de arbitragem que a nossa visão da rede alcançou.

### E ele é uma power law brutal

Valor por evento, contra o gas de execução (~0,0003 BNB):

| faixa | pools | eventos | BNB | % do dinheiro |
|---|---|---|---|---|
| ≥ 0,01 BNB (~30x o gas) | **5** | 100 | 2,3522 | **52,5** |
| ≥ 0,003 (~10x) | 4 | 5 | 0,0195 | 0,4 |
| ≥ 0,0003 (paga o gas) | 59 | 921 | 0,4872 | 10,9 |
| **abaixo do gas** | **8.057** | **62.407** | 1,6189 | 36,2 |

**98,4% dos eventos perseguíveis valiam menos que o gas para persegui-los.** E
somam 36% do "dinheiro" — que portanto não é dinheiro: é valor que desaparece no
ato de tentar capturá-lo.

Concentração: top 5 pools = 53,6%; top 10 = 66,3%; top 100 = 83,4%.

O subconjunto viável: **68 pools**, 2,859 BNB por pool ≈ 0,98 BNB real em 3 dias
≈ **US$ 195/dia**, em ~117 arbitragens/dia de ~US$ 1,67 cada.

---

## 6. O achado mais duro

Olha a coluna `tinha_rota` das três pools que carregam 46% do dinheiro real:

```
bnb      eventos  tinha_rota  par
0.6926      32        0       USDT/AFF
0.6926      32        0       AiFi/AFF
0.6926      32        0       USDT/AiFi
```

**Zero.** O triângulo `USDT → AFF → AiFi → USDT` rendeu 32 arbitragens em 3 dias,
a ~US$ 13 cada, e **a rota nunca estava no nosso livro.**

Enquanto isso, `LUNA/WBNB` teve 1.906 eventos, 93 com rota nossa e 128 reservas
confirmadas — muito trabalho, quase nenhum dinheiro.

> Enumeramos 344.823 pools e 200.000 rotas, e a rota que tinha metade do dinheiro
> não estava entre elas.

---

## 7. A conclusão que reescreve a prioridade

> **O trabalho não é achar mais oportunidades. É achar as cinco certas.**

O gargalo nunca foi preço (97,7% de reserva certa), nem volume de enumeração
(344 mil pools), nem velocidade de conta. Foi:

1. **o instrumento de validação não funcionava** (71,5% sem resposta) →
   resolvido pela fonte única
2. **o insumo taxa estava errado por ordens de grandeza** → resolvido pela
   constante da factory + endereço determinístico
3. **a enumeração não alcançava onde o dinheiro estava** → é aqui que o trabalho
   novo tem de ir
4. **não havia piso em valor absoluto** → 98,4% do esforço em eventos abaixo do gas

---

## 8. O que isto decide sobre o núcleo

**Todas as 68 pools economicamente viáveis são V2** (`e_v3 = false`), com taxa de
25 ou 30 bps — Pancake e Biswap padrão. Zero V3 no conjunto que paga o gas.

Então o problema do mapa de ticks do V3, a poda que o impede e o debate sobre
limitar o trade ao tick corrente: **nada disso está no caminho do dinheiro.** O
V3 entra depois, se entrar.

O núcleo começa em V2 com taxa da factory. É menos matemática e é onde está o
dinheiro.
