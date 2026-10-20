# Diário de tentativas do builder

`target/ensaio.jsonl` é o registro operacional único e append-only das tentativas de envio: uma
linha por tentativa, consolidando proposta, resposta dos builders, simulação na cadeia e desfecho nos
blocos seguintes. `id` é o hash da transação quando ela foi assinada; portões anteriores à assinatura
recebem um id de tentativa. Tentativas recusadas pelo relay também são registradas. O campo `execucao`
identifica a inicialização do processo. A linha é gravada ao concluir autópsia e desfecho; ao encerrar
o processo, o que ainda não terminou fica marcado como incompleto. A escrita e o `flush` rodam em uma
thread dedicada, fora da thread de sincronização do bloco.

O JSON tem quatro partes:

- `envio`: altura, caminho (`cauda`/`n+1`), rota e tokens; lucro e piso previstos; gás, alvo,
  oferta total equivalente por gás, pagamento direto ao builder e limites por saldo, lucro e teto;
  latências e resposta individual de cada builder. Para cada resposta do relay, preserva status HTTP,
  código JSON-RPC, mensagem e `data` originais, corpo de resposta (limitado a 8 KiB), seletor e texto
  de revert decodificados quando disponíveis. Também grava os hashes das transações do bundle em ordem,
  seus papéis (alvo, arbitragem, pagamento), nonces usados e a transação indicada na falha, se o relay
  devolver um hash completo correspondente. A mensagem normalizada continua disponível em `motivo`
  para agregação, sem substituir os campos originais.
- `autopsia`: veredito do Executor e resultado medido por perna no estado do bloco, incluindo
  impostos, desvio do modelo e se a execução alcançava o piso de lucro.
- `desfecho`: inclusão, rival ou pool alterada; posição, folga até o bloco seguinte e alvo de lance
  aprendido para a próxima tentativa da rota, com a regra exata aplicada.
- `diagnostico`: causa provável e próxima ação, derivadas das medições acima.

`folga_ms` compara o horário do bloco seguinte com a confirmação do relay, não com o instante exato
em que o pacote chegou ao builder. Para vários builders, o horário da confirmação usada é o da
primeira aceitação; as latências individuais e o tempo até todas as respostas ficam em `envio`.

`lance_ofertado_wei_por_gas` é a oferta equivalente do nosso bundle: pagamento direto mais 90% do
gás, dividido pelo gás estimado. Já `preco_gas_tx_na_frente_wei_por_gas` é apenas o preço de gás da
transação observada à frente. Não inclui necessariamente o pagamento privado daquele rival, portanto
não permite concluir sozinho que o lance total dele foi maior.

Os diagnósticos não inventam certeza quando falta evidência. Por exemplo, `pool_mexida_n1` indica
atividade na pool, não necessariamente uma arbitragem rival; nesses casos o próximo passo é identificar
a transação que a alterou. Uma perda para `rival_n1` sem o lance total do concorrente pede consultar o
bundle no builder antes de alterar o lance.

Nas recusas do relay, `resposta_bruta`, `mensagem_erro_completa` e `dados_erro` são as evidências
primárias; `motivo` é apenas a forma normalizada para contagem. Se houver um hash completo na mensagem
do relay, `indice_transacao_falha` e `transacoes_bundle` permitem apontar qual item falhou. O relay pode
omitir o hash ou a causa específica; nesse caso os campos ficam nulos, sem inferência.

Quando `regra_aprendizado` diz `...nao_do_bid_total`, o alvo adaptativo foi calculado sobre o preço
de gás da transação observada, não sobre o pagamento completo do bundle rival. Esse campo deixa
visível a limitação da evidência e evita interpretar o lance aprendido como um preço vencedor medido.

O endpoint `/disputa` expõe `vencedor_gas_price_wei` (campo `gasPrice` da transação),
`vencedor_gas_preco_efetivo_wei` (campo `effectiveGasPrice` do recibo, quando fornecido),
`vencedor_gas_preco_usado_wei` e `vencedor_taxa_wei` (preço efetivo × `gasUsed`). Preço zero é
preservado como zero; valor ausente fica nulo. `vencedor_pagou_bnb_wei` mede somente transferências
nativas a endereços conhecidos de builder, do remetente vencedor e na janela de três posições.
`pagamento_direto_builder_status` e `pagamento_direto_builder_escopo` explicitam essa cobertura.
Isso não é o lance completo: `pagamento_total_builder_wei` e `lance_total_rival_wei` permanecem
nulos/desconhecidos sem trace ou evidência do bundle. O resumo separa pagamentos diretos detectados,
não detectados e não medidos; `diferenca_total_pagamento_direto_bnb_wei` soma apenas diferenças
medidas desse componente, não uma diferença de lance total. O campo legado
`faltou_total_bnb_wei` é um alias dessa mesma soma de pagamento direto e não representa o lance total.

Para o modelo publicado do 48club, `/disputa` também expõe `lance_48club_taxa_gas_arb_wei`
(`gasUsed × effectiveGasPrice` da arbitragem), `lance_48club_contribuicao_gas_wei` (90% dessa taxa),
`lance_48club_bnb_direto_wei` (BNB direto ao Builder Control EOA, mais transferências internas
encontradas pelo trace) e `lance_48club_pontuacao_minima_wei` (soma dos componentes observados).
Essa pontuação é um **limite inferior**, não o lance total: pode faltar gás de outras transações
únicas do bundle, pagamentos fora do escopo e a confirmação de que o builder vencedor foi o 48club.
`lance_48club_status` mantém essas limitações explícitas. Pagamento interno só é somado quando o
recibo da arbitragem teve sucesso e o trace/explorer respondeu; ausência de trace nunca vira pagamento
zero. `pagamento_48club_status` distingue medição com trace no escopo observado de limite inferior sem trace.
Nas nossas tentativas, `lance_48club_taxa_gas_ofertada_wei` e
`lance_48club_pontuacao_ofertada_wei` registram a composição calculada para a regra do 48club;
`lance_48club_enviado` indica se o builder estava configurado para receber o bundle.

`RPC_TRACE` aceita uma URL RPC de tracing/archive dedicada, ou uma lista separada por vírgulas. O
provedor precisa suportar `debug_traceTransaction` com `callTracer`; para reprocessar blocos antigos,
também precisa reter traces históricos. Sem essa fonte, o processo continua a tentativa via API do
explorer, mas registra os pagamentos internos como desconhecidos. A prioridade de gás não é elevada
automaticamente ao perder: no modelo 48club, um wei de BNB direto acrescenta 1 wei à pontuação,
enquanto um wei de taxa de gás acrescenta 0,9; o ajuste automático anterior podia aumentar o piso
de gás e reduzir BNB direto sem evidência de ganho líquido.

O status operacional expõe `ensaio.trace_rpc.origem` e os hosts configurados (sem caminho, query ou
credenciais) para confirmar se a instância está usando `RPC_TRACE` ou o RPC de execução padrão. Cada
registro de vencedor inclui `trace_rpc_status` e, em caso de erro, `trace_rpc_erro` com o código e a
mensagem JSON-RPC (por exemplo, `missing trie node` ou método indisponível). Com vários endpoints
configurados, a consulta tenta os seguintes quando um endpoint retorna erro JSON-RPC e para no primeiro
trace válido. A consulta ao explorer
registra `bscscan_status` e `bscscan_erro`; uma chave ausente é diferenciada de erro de API. Resposta
bem-sucedida com lista vazia significa que não foram encontradas transferências internas; falha de
consulta permanece explicitamente não medida.

O `/status` publica `ensaio.economia.piso_margem_centimos`, `gas_tx_pagamento_builder` e
`sobra_liquida_minima_wei` para confirmar a regra ativa no processo. O piso-base padrão cobre gás
calibrado da arbitragem, eventual taxa de empréstimo e 1 wei de sobra prevista. `PISO_MARGEM` pode
adicionar margem, mas não aceita valor abaixo de 1,00×. `lances` mostra fração inicial, máxima e a
faixa atualmente persistida nas rotas, para distinguir um teto configurado de estado antigo em `lances.json`.
Se houver pagamento direto ao builder, o
orçamento soma 21.000 unidades de gás da transação de transferência antes de definir o limite do
pagamento. O lance usa até 100% do lucro disponível por padrão (`lances.fracao_inicial_milha=1000`);
o máximo também é 1000 e o limite deixa pelo menos 1 wei além do piso. Esse 100% é independente dos
90% de gás usados na fórmula publicada de pontuação do 48club.

`lances.json` continua existindo como estado persistente do algoritmo adaptativo; não é um segundo
log. Os registros separados de `autopsias-*.jsonl` e `desfechos-*.jsonl` deixam de ser produzidos
pelo fluxo principal. `mercado-*.jsonl` e `relatorio-execucao-*.jsonl` têm outros consumidores e
não são logs da mesma tentativa; continuam separados para não misturar evidências de mercado e
snapshots operacionais com o resultado por envio.
