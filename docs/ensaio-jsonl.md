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

`lances.json` continua existindo como estado persistente do algoritmo adaptativo; não é um segundo
log. Os registros separados de `autopsias-*.jsonl` e `desfechos-*.jsonl` deixam de ser produzidos
pelo fluxo principal. `mercado-*.jsonl` e `relatorio-execucao-*.jsonl` têm outros consumidores e
não são logs da mesma tentativa; continuam separados para não misturar evidências de mercado e
snapshots operacionais com o resultado por envio.
