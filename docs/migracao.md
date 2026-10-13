# Migração do `old` — o que vem, o que morre, e qual premissa decide

42.832 linhas em 178 arquivos. A primeira decisão da migração não é onde pôr
cada classe: é **quais não vêm**. Migrar código que uma premissa mata é o pior
resultado possível — sai um sistema com as classes renomeadas e os mesmos
problemas.

Quase metade morre, e morre por premissa, não por gosto.

## Morre inteiro

| Pacote / classe | linhas | Premissa que mata |
|---|---|---|
| `repository/redis/*` | 679 | Sem cache e sem caminho de volta. Redis era fallback. |
| `service/conferencia/*` | 1.857 | O contrato reverte se não houver lucro. Validar fora da cadeia é duplicar de graça uma garantia atômica. |
| `ValidacaoService`, `EnsaioDeExecucao` | ~900 | idem |
| `RpcCliente`, `TransporteRpc`, `DisjuntorRpc`, `OrcamentoDeRpc`, `RitmoRpc`, `ConfiancaRpcEnum` | ~1.500 | Fonte única: o nosso node. Existiam para cobrir 12 dias de história não processada — 31 registros de dívida real. |
| `SondaDeImposto`, `ProvadorImpostoService` | ~1.100 | Imposto sai de `Transfer` contra o `amountIn` do `Swap`, no mesmo recibo. E imposto não provado é critério de exclusão, não fila para drenar. |
| `CacheAnelar`, `ArmazemCabecalhos` | 478 | Janela, não cache. E os pares não nos derrubavam por não servir — o motivo declarado nunca existiu. |
| `service/session/*` (16 arq) | 3.632 | Um par, uma sessão. Dois tipos de sessão era o protocolo virando identidade. |
| `service/connection/*` | 675 | `PortaUdp` já existe; a TCP nasce como conexão da sessão. |
| `service/peer/*` | 359 | `Sessoes` como registro único. |
| `NodeService` | 1.268 | Uma altura, um dono. Era ele que tinha a terceira. |
| `infra/*` (Diario, Executores, Recursos) | 550 | `Resources` + JDK. |
| `callback/*` | 22 | `Consumer` da JDK. |
| `CorrenteDaCabeca`, `Cobertura`, `Janela`, `TempoPorEstado`, `Veredicto`, `Portao` | ~600 | Cobertura contínua. O portão é `cursor == cabeça`, um booleano. |
| `Funil`, `FunilService`, `MetricasFrio` | ~1.200 | Mediam a coisa errada. A métrica é gas gasto ÷ lucro realizado. |
| `Ritmo` | ~300 | Media a convergência que a folga garante. |

**Subtotal que morre: ~15.100 linhas.** Nenhuma perda de função — cada uma era
gerência de medo contra um defeito que a premissa remove.

## Vem, e é o ativo real

| Pacote / classe | Para onde | Por que vem |
|---|---|---|
| `protocol/eth/ParametrosBsc`, `ForkId`, `AdocaoDeFork` | `cadeia/` | É a identidade da rede. Verificado: `CRC32(genesis) = 0x3f2e9ae4`, igual ao que o bootnode publica no ENR. |
| `protocol/rlpx/*` (Handshake, Quadro, Segredos, Snappy) | `rede/` | É a segunda conexão da sessão. Sem isso não há bloco, recibo nem mempool. |
| `crypto/Ecies` | `util/` | Exigido pelo aperto RLPx. |
| `protocol/MensagemEth`, `BscEthProtocol`, `StatusEth`, `ChainInfo` | `rede/` + `vo/` | O protocolo eth. Sealed: o compilador passa a apontar caso novo. |
| `nucleo/` — Curva, Pool, PoolV3, EstadoV3, LiquidezV3, Livro, Grafo, Ciclo, Perna, Travessia, Taxa, Imposto, Token, Bps, Direcao, Faixa, Fluxo, ReguaDeValor, Execucao, Desfecho | `nucleo/` | **A matemática do domínio. É o que o projeto tem de mais caro e não se refaz.** |
| `frio/Abi`, `EventoDex`, `EventoV3`, `DecodificadorDeSwap`, `EnderecoDePar` | `cadeia/` ou `nucleo/` | Decodificar log é como a reserva chega. Substitui o RPC inteiro. |
| `arbitragem/Precificador`, `MontadorDeRota`, `MedidorDeGas` | `nucleo/` | Cálculo, não orquestração. |
| `repository/` — Pools, Tokens, Rotas, Reservas, Impostos | `repository/` | O catálogo mora no Postgres. Reescritos sem `estimada` misturado com leitura. |

## A confirmar na hora de migrar

`Aptidao`, `Prioridade`, `ParNovo`, `PoolV3Nova`, `MemoriaDeDecisoes`,
`MemoriaDeDesfecho`, `MemoriaDeImpedimento`, `GuardiaoDeReservas`,
`PreenchedorDeReservas`, `ReservasService`, `Amostragem`, `ColetorDeFatias`,
`EnumeradorRotasService`, `ContabilidadeDeNegocio`, `ViaDeAnuncio`,
`GatilhoDeBloco`, `RelogioDeBloco`.

Não li cada uma. A regra ao abrir: se a classe existe para decidir *se dá para
confiar* num dado, ela morre com a premissa. Se ela existe para *calcular*, vem.

`service/painel/*` (1.763) fica fora deste escopo — não é pré-requisito de nada.

## Ordem da migração

O grafo manda, e o critério é "o que destrava mais coisa":

1. **`cadeia/`** — fork id e crivo de rede. *(feito)* Fechou a pergunta "tu é de
   quê?" por UDP, e o crivo virou duas perguntas: `bsc()` para catalogar,
   `corteDeHoje()` para conectar.
2. **`rede/` RLPx + eth** — *(feito)* `Ecies`, `Aperto`, `Quadros`, `Segredos`,
   `Snappy`, `Rlpx`. Handshake do node fecha até o `Status`, e `GetReceipts`
   mediu **275–559 ms** — o número que tirou o recibo do caminho quente.
3. **`sessao/` Sessoes + Lotacao** — *(feito)* Registro único, 25 ativas com
   detecção de morte por qualquer entrada de protocolo, e reposição no mesmo laço.
4. **`nucleo/`** — a matemática, migrada com teste de módulo por curva. **Próximo,
   e é o ativo mais caro do `old`.**
5. **cursor e catálogo** — a continuidade como invariante. Depende de implementar
   os códigos eth que o roteiro de `Desconhecida` apontar.
6. **projeção e arbitrador** — o caminho curto.

## O que a migração não pode deixar acontecer

Cada premissa tem um jeito de renascer com nome novo. Os sinais:

- uma segunda tabela ou campo dizendo "isto é confiável?" → voltou a gerência de medo
- qualquer `if` escolhendo plano quente/frio → P1 caiu
- qualquer consulta que, não achando, busca na rede → P2 caiu
- uma segunda coisa escrevendo "onde estamos" → a terceira altura voltou
- validação off-chain de lucro → o contrato já faz, de graça
