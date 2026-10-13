---
name: mapa-mental
description: Use SEMPRE que for entender, depurar ou mudar o fluxo do node-java (quem alimenta quem, em que thread, por onde passa um dado, que defeitos/decisoes existem num componente) ou ao terminar uma mudanca/diagnostico que altera esse fluxo. Consulta e atualiza o mapa mental no Neo4j (MCP mapa-mental) com vocabulario fechado e consultas compactas. Nao usar para perguntas de nivel de codigo (quem chama o metodo X, assinatura) — isso e grep/LSP.
---

# Mapa mental do node-java (Neo4j)

O grafo guarda o que NAO se ve lendo um arquivo: o fluxo em tempo de execucao entre componentes,
as threads, os externos (peers, RPC, relay, banco), as metricas do relatorio, os defeitos e as
decisoes. Codigo nao mora aqui: cada no aponta para o `arquivo`, e o arquivo e a verdade.

## Antes de consultar

1. **Nao chame `get_neo4j_schema`.** O vocabulario esta abaixo; o esquema custa ~900 tokens (medido
   em 24/09 com 94 nos) e cresce. Chame so se uma consulta falhar por rotulo/propriedade que devia
   existir — e com `sample_size` 50.
2. Prefira UMA consulta que devolva o que a pergunta pede a varias pequenas.

## Vocabulario (fechado — nao invente rotulo, tipo ou propriedade)

Rotulos (chave unica `nome`, com restricao de unicidade):

| Rotulo | nome | outras propriedades |
|---|---|---|
| `Area` | rede, sincronizacao, catalogo, mempool, garimpo, execucao, diagnostico, persistencia | papel |
| `Componente` | nome da classe Java | papel (1 frase), arquivo (caminho relativo), verificado (AAAA-MM-DD) |
| `Thread` | nome da thread (`lotacao-le`, `cursor`, `virtual-mempool`...) | papel, verificado |
| `Externo` | Peers BSC, RPC publico, Relay 48club, Postgres, Executor, quarentena.json | papel |
| `Metrica` | caminho no relatorio (`ensaio.gabarito`, `mempool.conferencia_do_projetor`) | papel |
| `Defeito` | slug-com-hifens | resumo, estado, data, evidencia |
| `Decisao` | slug-com-hifens | porque, data |

`Defeito.estado`: `suspeita` · `aberto` · `corrigido-no-codigo` · `corrigido-e-medido`. Defeito nao se
apaga: muda de estado. Decisao superada ganha `superada` (data) e aponta a nova com `SOBRE`.

Relacoes (direcao fixa):

- `(Area)-[:CONTEM]->(Componente)`
- `(Componente|Externo)-[:FLUI_PARA {dado, modo, thread}]->(Componente|Externo)` — o fluxo
- `(Componente)-[:RODA_EM]->(Thread)`
- `(Componente)-[:EXPOE]->(Metrica)`
- `(Defeito)-[:EM]->(Componente)`
- `(Decisao)-[:SOBRE]->(Componente|Externo)`

`modo`: `sync` · `async` · `fila` · `callback` · `pedir-e-esperar` · `http` · `socket` · `leitura` · `lote` · `arquivo`.

## Regras de consulta (custo)

Medido em 24/09: devolver no inteiro custa ~7x mais que devolver propriedades, e diz menos.

- **Nunca `RETURN n`**, `RETURN *` ou caminho cru. Devolva propriedades nomeadas (`c.nome`, `f.dado`).
- **Agregue** vizinhanca com `collect(a.nome+'>'+f.dado)` numa linha, em vez de uma linha por vizinho.
- **Sempre `LIMIT`** (25 por padrao) quando o resultado puder crescer.
- **Parametros** (`$n`) em vez de valores colados.
- Depois de agregar, `ORDER BY` so via `WITH ... ORDER BY` (erro de sintaxe medido).

## Consultas canonicas (use estas antes de escrever outras)

Vizinhanca de um componente — a pergunta mais comum (~100 tokens):

```cypher
MATCH (c:Componente {nome:$n})
OPTIONAL MATCH (i)-[g:FLUI_PARA]->(c) WITH c, collect(i.nome+'<'+g.dado) AS entra
OPTIONAL MATCH (c)-[f:FLUI_PARA]->(o) WITH c, entra, collect(o.nome+'>'+f.dado) AS sai
OPTIONAL MATCH (c)-[:RODA_EM]->(t) WITH c, entra, sai, collect(t.nome) AS th
OPTIONAL MATCH (d:Defeito)-[:EM]->(c) WHERE d.estado <> 'corrigido-e-medido'
RETURN c.papel AS papel, c.arquivo AS arq, c.verificado AS verif, th, entra, sai,
       collect(d.nome+':'+d.estado) AS defeitos
```

Caminho de um dado entre dois pontos (o pipeline numa chamada):

```cypher
MATCH p=shortestPath((a {nome:$de})-[:FLUI_PARA*..10]->(b {nome:$ate}))
RETURN [i IN range(0,length(p)-1) | nodes(p)[i].nome+' -['+relationships(p)[i].dado+' @'
        +relationships(p)[i].thread+']-> '] + [last(nodes(p)).nome] AS caminho
```

Quem roda numa thread: `MATCH (c)-[:RODA_EM]->(t:Thread {nome:$t}) RETURN t.papel, collect(c.nome)`

Defeitos vivos:

```cypher
MATCH (d:Defeito) WHERE d.estado <> 'corrigido-e-medido'
OPTIONAL MATCH (d)-[:EM]->(c)
WITH d, collect(c.nome) AS em ORDER BY d.data DESC
RETURN d.nome, d.estado, em LIMIT 25
```

Onde olhar uma metrica: `MATCH (c)-[:EXPOE]->(m:Metrica {nome:$m}) RETURN c.nome, c.arquivo, m.papel`

## O mapa nao e o codigo — conferir antes de agir

Um no diz onde olhar, nao o que esta la. Antes de basear uma mudanca num no:
`stat -c %y <arquivo>` — se o arquivo e mais novo que `verificado`, **releia o arquivo** e, se o fluxo
mudou, atualize o no. Nunca cite fluxo do grafo ao usuario como fato sem ter conferido no codigo nesta
sessao quando a decisao depender dele.

## Regras de escrita

- **Quando escrever:** no mesmo passo em que o fluxo muda (novo callback, nova thread, componente novo,
  dado novo numa aresta), quando um defeito e achado ou muda de estado, e quando uma decisao e tomada.
  Mapa que atrasa vira mapa que mente.
- **Sempre `MERGE` pela chave `nome`**, e `SET` so do que mudou; atualize `verificado` do componente que
  voce conferiu.
- **Lote com `UNWIND $lista`** — uma chamada para N nos, nao N chamadas.
- **Texto SEM ACENTO** nas propriedades. O MCP devolve JSON com `ç` por letra acentuada (6 chars
  cada); medido ~15% do texto de uma resposta em escapes.
- **Curto:** `papel`/`resumo`/`porque` = 1 frase (<= ~120 chars). Precisa de mais? Vai para
  comentario no codigo ou `docs/`, e o no aponta.
- **Nada volatil:** nenhum numero que muda a cada corrida (contagens do relatorio). `evidencia` de
  defeito e o numero que o PROVOU, com a data — e fica.
- **Sem vocabulario novo sem registrar aqui.** Se precisar de rotulo/relacao nova, edite esta skill no
  mesmo passo e crie a restricao de unicidade.

## Limites da ferramenta (medidos)

- `write_neo4j_cypher` recusa DDL (`CREATE CONSTRAINT/INDEX`): use
  `docker exec -i mapa-mental-neo4j cypher-shell -u neo4j -p "$(cat ~/.config/mapa-mental/neo4j.senha)"`.
- Escrita via APOC (`apoc.create.setProperty`) volta `{}` no resumo: confira com uma leitura.
- Edicao Community 5.26: so unicidade; sem node key nem restricao de existencia.
- Tamanho-alvo do grafo: centenas de nos. Se passar de ~500, o nivel de detalhe esta errado
  (virou AST) — resuma para componente.
