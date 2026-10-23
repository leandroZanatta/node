# Documento de Estrutura - Refatoração Node Java

Gerado em: 2026-10-22T19:34:25.189205
Total de arquivos .java: 197
Branch: feature-pelado
Commit: 9e5a587

## Índice por pacote

### br.com.node.CriarKeystore (1 arquivos, 53 linhas)
- [   53 linhas] src/main/java/br/com/node/CriarKeystore.java

### br.com.node.Main (1 arquivos, 659 linhas)
- [  659 linhas] src/main/java/br/com/node/Main.java

### br.com.node.RelatorioExecucao (1 arquivos, 166 linhas)
- [  166 linhas] src/main/java/br/com/node/RelatorioExecucao.java

### br.com.node.api (1 arquivos, 254 linhas)
- [  254 linhas] src/main/java/br/com/node/api/HttpApi.java

### br.com.node.cadeia (3 arquivos, 184 linhas)
- [   51 linhas] src/main/java/br/com/node/cadeia/Crivo.java
- [   84 linhas] src/main/java/br/com/node/cadeia/Forks.java
- [   49 linhas] src/main/java/br/com/node/cadeia/ParametrosBsc.java

### br.com.node.catalogo (19 arquivos, 4404 linhas)
- [   14 linhas] src/main/java/br/com/node/catalogo/Achado.java
- [  545 linhas] src/main/java/br/com/node/catalogo/Catalogo.java
- [  936 linhas] src/main/java/br/com/node/catalogo/Cauda.java
- [  156 linhas] src/main/java/br/com/node/catalogo/ChaveDaPool.java
- [  358 linhas] src/main/java/br/com/node/catalogo/Cotador.java
- [  322 linhas] src/main/java/br/com/node/catalogo/Detector.java
- [   60 linhas] src/main/java/br/com/node/catalogo/EspacamentoPorChamada.java
- [   21 linhas] src/main/java/br/com/node/catalogo/EstadoNoGerente.java
- [  167 linhas] src/main/java/br/com/node/catalogo/Eventos.java
- [   26 linhas] src/main/java/br/com/node/catalogo/Fabrica.java
- [  229 linhas] src/main/java/br/com/node/catalogo/Fabricas.java
- [  297 linhas] src/main/java/br/com/node/catalogo/Impostos.java
- [  203 linhas] src/main/java/br/com/node/catalogo/ObservatorioArbitragem.java
- [   11 linhas] src/main/java/br/com/node/catalogo/Origem.java
- [  177 linhas] src/main/java/br/com/node/catalogo/Quentes.java
- [  167 linhas] src/main/java/br/com/node/catalogo/Tarifario.java
- [  312 linhas] src/main/java/br/com/node/catalogo/Taxas.java
- [  386 linhas] src/main/java/br/com/node/catalogo/Ticks.java
- [   17 linhas] src/main/java/br/com/node/catalogo/Transicao.java

### br.com.node.descoberta (2 arquivos, 261 linhas)
- [  192 linhas] src/main/java/br/com/node/descoberta/Descoberta.java
- [   69 linhas] src/main/java/br/com/node/descoberta/Sementes.java

### br.com.node.ensaio (26 arquivos, 8228 linhas)
- [  169 linhas] src/main/java/br/com/node/ensaio/AbiDoExecutor.java
- [   58 linhas] src/main/java/br/com/node/ensaio/AssinadorDeTransacao.java
- [ 1039 linhas] src/main/java/br/com/node/ensaio/Autopsia.java
- [  713 linhas] src/main/java/br/com/node/ensaio/AutopsiaDoMercado.java
- [  347 linhas] src/main/java/br/com/node/ensaio/Backrun.java
- [   39 linhas] src/main/java/br/com/node/ensaio/CalculadorDeLance.java
- [  656 linhas] src/main/java/br/com/node/ensaio/CustoDeGas.java
- [  343 linhas] src/main/java/br/com/node/ensaio/Desfechos.java
- [  375 linhas] src/main/java/br/com/node/ensaio/DiarioDeEnsaio.java
- [  739 linhas] src/main/java/br/com/node/ensaio/Disparo.java
- [  372 linhas] src/main/java/br/com/node/ensaio/Disputa.java
- [  200 linhas] src/main/java/br/com/node/ensaio/Gabarito.java
- [  396 linhas] src/main/java/br/com/node/ensaio/Gatilhos.java
- [   33 linhas] src/main/java/br/com/node/ensaio/Lance48Club.java
- [  204 linhas] src/main/java/br/com/node/ensaio/Lances.java
- [  179 linhas] src/main/java/br/com/node/ensaio/MedidorDeGas.java
- [  100 linhas] src/main/java/br/com/node/ensaio/MetricasDoDisparo.java
- [  165 linhas] src/main/java/br/com/node/ensaio/MontadorDeBundle.java
- [  188 linhas] src/main/java/br/com/node/ensaio/PagamentosDeBloco.java
- [  448 linhas] src/main/java/br/com/node/ensaio/RelayPrivado.java
- [  153 linhas] src/main/java/br/com/node/ensaio/RotaDoModelo.java
- [  556 linhas] src/main/java/br/com/node/ensaio/RpcDoEnsaio.java
- [   66 linhas] src/main/java/br/com/node/ensaio/Salto.java
- [  322 linhas] src/main/java/br/com/node/ensaio/TransferenciasInternas.java
- [  204 linhas] src/main/java/br/com/node/ensaio/VencedorDoLeilao.java
- [  164 linhas] src/main/java/br/com/node/ensaio/VereditorDoContrato.java

### br.com.node.exception (3 arquivos, 36 linhas)
- [   10 linhas] src/main/java/br/com/node/exception/BancoException.java
- [   11 linhas] src/main/java/br/com/node/exception/PeerException.java
- [   15 linhas] src/main/java/br/com/node/exception/RlpException.java

### br.com.node.kademlia (2 arquivos, 124 linhas)
- [   47 linhas] src/main/java/br/com/node/kademlia/Distancia.java
- [   77 linhas] src/main/java/br/com/node/kademlia/TabelaDeRotas.java

### br.com.node.mempool (21 arquivos, 3992 linhas)
- [  324 linhas] src/main/java/br/com/node/mempool/AprendizDeLayout.java
- [  191 linhas] src/main/java/br/com/node/mempool/CadernoDeLayouts.java
- [   97 linhas] src/main/java/br/com/node/mempool/DossieWbnb.java
- [   39 linhas] src/main/java/br/com/node/mempool/ExemploDeMovimento.java
- [  176 linhas] src/main/java/br/com/node/mempool/LayoutAprendido.java
- [   90 linhas] src/main/java/br/com/node/mempool/LoteEip7702.java
- [   32 linhas] src/main/java/br/com/node/mempool/MargemDeChegada.java
- [ 1023 linhas] src/main/java/br/com/node/mempool/ObservatorioMempool.java
- [  160 linhas] src/main/java/br/com/node/mempool/Pareador.java
- [   11 linhas] src/main/java/br/com/node/mempool/Projecao.java
- [  118 linhas] src/main/java/br/com/node/mempool/RoteadorAgregador.java
- [   89 linhas] src/main/java/br/com/node/mempool/RoteadorBStocks.java
- [  117 linhas] src/main/java/br/com/node/mempool/RoteadorDag.java
- [  141 linhas] src/main/java/br/com/node/mempool/RoteadorDeSaltos.java
- [  116 linhas] src/main/java/br/com/node/mempool/RoteadorSolidly.java
- [  337 linhas] src/main/java/br/com/node/mempool/Roteamento.java
- [  299 linhas] src/main/java/br/com/node/mempool/RoteamentoUniversal.java
- [   74 linhas] src/main/java/br/com/node/mempool/SeletoresVistos.java
- [  301 linhas] src/main/java/br/com/node/mempool/Simulador.java
- [   27 linhas] src/main/java/br/com/node/mempool/TransacaoAnunciada.java
- [  230 linhas] src/main/java/br/com/node/mempool/TransacaoPendente.java

### br.com.node.nucleo (20 arquivos, 2223 linhas)
- [   26 linhas] src/main/java/br/com/node/nucleo/Arbitragem.java
- [  231 linhas] src/main/java/br/com/node/nucleo/Avaliador.java
- [   15 linhas] src/main/java/br/com/node/nucleo/Ciclo.java
- [   70 linhas] src/main/java/br/com/node/nucleo/Curva.java
- [    8 linhas] src/main/java/br/com/node/nucleo/Direcao.java
- [  177 linhas] src/main/java/br/com/node/nucleo/EstadoV3.java
- [   96 linhas] src/main/java/br/com/node/nucleo/Grafo.java
- [  679 linhas] src/main/java/br/com/node/nucleo/GrafoDePrecos.java
- [   71 linhas] src/main/java/br/com/node/nucleo/MapaDeTicks.java
- [   15 linhas] src/main/java/br/com/node/nucleo/Oportunidade.java
- [   35 linhas] src/main/java/br/com/node/nucleo/Par.java
- [   10 linhas] src/main/java/br/com/node/nucleo/Passo.java
- [    5 linhas] src/main/java/br/com/node/nucleo/Perna.java
- [   41 linhas] src/main/java/br/com/node/nucleo/Pool.java
- [  137 linhas] src/main/java/br/com/node/nucleo/PoolSolidlyStable.java
- [   58 linhas] src/main/java/br/com/node/nucleo/PoolV2.java
- [  295 linhas] src/main/java/br/com/node/nucleo/PoolV3.java
- [   89 linhas] src/main/java/br/com/node/nucleo/ReservasV2.java
- [   94 linhas] src/main/java/br/com/node/nucleo/TickMath.java
- [   71 linhas] src/main/java/br/com/node/nucleo/Travessia.java

### br.com.node.rede (12 arquivos, 1625 linhas)
- [  146 linhas] src/main/java/br/com/node/rede/Aperto.java
- [  201 linhas] src/main/java/br/com/node/rede/Discv4.java
- [   69 linhas] src/main/java/br/com/node/rede/Envelope.java
- [  306 linhas] src/main/java/br/com/node/rede/Eth.java
- [   72 linhas] src/main/java/br/com/node/rede/LeituraSnappy.java
- [   95 linhas] src/main/java/br/com/node/rede/P2p.java
- [   99 linhas] src/main/java/br/com/node/rede/PortaUdp.java
- [  216 linhas] src/main/java/br/com/node/rede/Quadros.java
- [   81 linhas] src/main/java/br/com/node/rede/Recibos.java
- [  115 linhas] src/main/java/br/com/node/rede/Rlpx.java
- [   72 linhas] src/main/java/br/com/node/rede/Segredos.java
- [  153 linhas] src/main/java/br/com/node/rede/Snappy.java

### br.com.node.repository (6 arquivos, 706 linhas)
- [  146 linhas] src/main/java/br/com/node/repository/ImpostosRepository.java
- [   71 linhas] src/main/java/br/com/node/repository/PeersRepository.java
- [  138 linhas] src/main/java/br/com/node/repository/PoolsNoGerenteRepository.java
- [  152 linhas] src/main/java/br/com/node/repository/ProvasDeTaxaRepository.java
- [  189 linhas] src/main/java/br/com/node/repository/postgres/Postgres.java
- [   10 linhas] src/main/java/br/com/node/repository/postgres/Trabalho.java

### br.com.node.rlp (5 arquivos, 375 linhas)
- [  225 linhas] src/main/java/br/com/node/rlp/Rlp.java
- [   22 linhas] src/main/java/br/com/node/rlp/RlpBytes.java
- [    4 linhas] src/main/java/br/com/node/rlp/RlpItem.java
- [  118 linhas] src/main/java/br/com/node/rlp/RlpLeitor.java
- [    6 linhas] src/main/java/br/com/node/rlp/RlpLista.java

### br.com.node.sessao (8 arquivos, 1364 linhas)
- [  236 linhas] src/main/java/br/com/node/sessao/ConexaoTcp.java
- [   49 linhas] src/main/java/br/com/node/sessao/ConexaoUdp.java
- [  444 linhas] src/main/java/br/com/node/sessao/Lotacao.java
- [   98 linhas] src/main/java/br/com/node/sessao/Pedidos.java
- [   30 linhas] src/main/java/br/com/node/sessao/PlacarDePrimeiros.java
- [  400 linhas] src/main/java/br/com/node/sessao/Sessao.java
- [   54 linhas] src/main/java/br/com/node/sessao/Sessoes.java
- [   53 linhas] src/main/java/br/com/node/sessao/Vinculo.java

### br.com.node.sincronizacao (8 arquivos, 1786 linhas)
- [  222 linhas] src/main/java/br/com/node/sincronizacao/Cabeca.java
- [  433 linhas] src/main/java/br/com/node/sincronizacao/Cursor.java
- [   54 linhas] src/main/java/br/com/node/sincronizacao/Desanimo.java
- [    9 linhas] src/main/java/br/com/node/sincronizacao/Faixa.java
- [  700 linhas] src/main/java/br/com/node/sincronizacao/Frente.java
- [   49 linhas] src/main/java/br/com/node/sincronizacao/Orcamentos.java
- [   93 linhas] src/main/java/br/com/node/sincronizacao/Sincronizacao.java
- [  226 linhas] src/main/java/br/com/node/sincronizacao/VarredorDeGerentes.java

### br.com.node.sondagem (6 arquivos, 823 linhas)
- [   26 linhas] src/main/java/br/com/node/sondagem/Cadeia.java
- [   50 linhas] src/main/java/br/com/node/sondagem/Medida.java
- [   64 linhas] src/main/java/br/com/node/sondagem/Ritmo.java
- [  135 linhas] src/main/java/br/com/node/sondagem/Rpc.java
- [  294 linhas] src/main/java/br/com/node/sondagem/Sonda.java
- [  254 linhas] src/main/java/br/com/node/sondagem/Sondagem.java

### br.com.node.util (10 arquivos, 1023 linhas)
- [   50 linhas] src/main/java/br/com/node/util/Amostras.java
- [  178 linhas] src/main/java/br/com/node/util/Diario.java
- [  177 linhas] src/main/java/br/com/node/util/Ecies.java
- [   47 linhas] src/main/java/br/com/node/util/Falhas.java
- [   71 linhas] src/main/java/br/com/node/util/Identidade.java
- [   39 linhas] src/main/java/br/com/node/util/Keccak.java
- [  102 linhas] src/main/java/br/com/node/util/Keystore.java
- [   71 linhas] src/main/java/br/com/node/util/NodeKey.java
- [   97 linhas] src/main/java/br/com/node/util/Resources.java
- [  191 linhas] src/main/java/br/com/node/util/Secp256k1.java

### br.com.node.vo (42 arquivos, 684 linhas)
- [   16 linhas] src/main/java/br/com/node/vo/AnuncioBloco.java
- [   15 linhas] src/main/java/br/com/node/vo/BlockHeaders.java
- [    8 linhas] src/main/java/br/com/node/vo/Bloco.java
- [   37 linhas] src/main/java/br/com/node/vo/Cabecalho.java
- [   11 linhas] src/main/java/br/com/node/vo/Capacidade.java
- [   14 linhas] src/main/java/br/com/node/vo/Correlacionada.java
- [   18 linhas] src/main/java/br/com/node/vo/Desconhecida.java
- [   33 linhas] src/main/java/br/com/node/vo/Disconnect.java
- [    6 linhas] src/main/java/br/com/node/vo/Endpoint.java
- [    9 linhas] src/main/java/br/com/node/vo/EnrRequest.java
- [   28 linhas] src/main/java/br/com/node/vo/EnrResponse.java
- [   21 linhas] src/main/java/br/com/node/vo/FindNode.java
- [   22 linhas] src/main/java/br/com/node/vo/ForkId.java
- [   10 linhas] src/main/java/br/com/node/vo/GetBlockHeaders.java
- [   16 linhas] src/main/java/br/com/node/vo/GetBlockHeadersDeHash.java
- [   15 linhas] src/main/java/br/com/node/vo/GetPooledTransactions.java
- [   15 linhas] src/main/java/br/com/node/vo/GetReceipts.java
- [   18 linhas] src/main/java/br/com/node/vo/Hello.java
- [   19 linhas] src/main/java/br/com/node/vo/Log.java
- [   23 linhas] src/main/java/br/com/node/vo/MensagemDiscovery.java
- [   14 linhas] src/main/java/br/com/node/vo/MensagemEth.java
- [   11 linhas] src/main/java/br/com/node/vo/MensagemP2p.java
- [    6 linhas] src/main/java/br/com/node/vo/MensagemRlpx.java
- [   11 linhas] src/main/java/br/com/node/vo/Neighbors.java
- [   23 linhas] src/main/java/br/com/node/vo/NewBlock.java
- [   18 linhas] src/main/java/br/com/node/vo/NewBlockHashes.java
- [   28 linhas] src/main/java/br/com/node/vo/NewPooledTransactionHashes.java
- [   28 linhas] src/main/java/br/com/node/vo/NoRemoto.java
- [    4 linhas] src/main/java/br/com/node/vo/PacoteAberto.java
- [    4 linhas] src/main/java/br/com/node/vo/PacoteDiscovery.java
- [    9 linhas] src/main/java/br/com/node/vo/Ping.java
- [   11 linhas] src/main/java/br/com/node/vo/PingP2p.java
- [   22 linhas] src/main/java/br/com/node/vo/Pong.java
- [    9 linhas] src/main/java/br/com/node/vo/PongP2p.java
- [   16 linhas] src/main/java/br/com/node/vo/PooledTransactions.java
- [   15 linhas] src/main/java/br/com/node/vo/ProvaDeImposto.java
- [   14 linhas] src/main/java/br/com/node/vo/ProvaDeTaxa.java
- [   17 linhas] src/main/java/br/com/node/vo/Receipts.java
- [   16 linhas] src/main/java/br/com/node/vo/Status.java
- [   26 linhas] src/main/java/br/com/node/vo/SwapPendente.java
- [   16 linhas] src/main/java/br/com/node/vo/Transactions.java
- [   12 linhas] src/main/java/br/com/node/vo/UpgradeStatus.java

## Lista completa de arquivos (para controle de leitura/edição)

| Arquivo | Pacote | Linhas | Bytes | Lido | Comentários removidos | Reformato | Observações |
|---------|--------|--------|-------|------|----------------------|-----------|-------------|
| src/main/java/br/com/node/CriarKeystore.java | br.com.node.CriarKeystore | 53 | 2040 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/Main.java | br.com.node.Main | 659 | 29807 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/RelatorioExecucao.java | br.com.node.RelatorioExecucao | 166 | 6555 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/api/HttpApi.java | br.com.node.api.HttpApi | 254 | 9358 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/cadeia/Crivo.java | br.com.node.cadeia.Crivo | 51 | 1414 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/cadeia/Forks.java | br.com.node.cadeia.Forks | 84 | 1967 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/cadeia/ParametrosBsc.java | br.com.node.cadeia.ParametrosBsc | 49 | 1593 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Achado.java | br.com.node.catalogo.Achado | 14 | 333 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Catalogo.java | br.com.node.catalogo.Catalogo | 545 | 22544 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Cauda.java | br.com.node.catalogo.Cauda | 936 | 36410 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/ChaveDaPool.java | br.com.node.catalogo.ChaveDaPool | 156 | 6845 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Cotador.java | br.com.node.catalogo.Cotador | 358 | 13424 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Detector.java | br.com.node.catalogo.Detector | 322 | 13988 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/EspacamentoPorChamada.java | br.com.node.catalogo.EspacamentoPorChamada | 60 | 2120 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/EstadoNoGerente.java | br.com.node.catalogo.EstadoNoGerente | 21 | 1001 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Eventos.java | br.com.node.catalogo.Eventos | 167 | 8028 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Fabrica.java | br.com.node.catalogo.Fabrica | 26 | 1330 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Fabricas.java | br.com.node.catalogo.Fabricas | 229 | 10309 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Impostos.java | br.com.node.catalogo.Impostos | 297 | 13731 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/ObservatorioArbitragem.java | br.com.node.catalogo.ObservatorioArbitragem | 203 | 8415 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Origem.java | br.com.node.catalogo.Origem | 11 | 507 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Quentes.java | br.com.node.catalogo.Quentes | 177 | 6535 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Tarifario.java | br.com.node.catalogo.Tarifario | 167 | 6300 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Taxas.java | br.com.node.catalogo.Taxas | 312 | 13010 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Ticks.java | br.com.node.catalogo.Ticks | 386 | 14584 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/catalogo/Transicao.java | br.com.node.catalogo.Transicao | 17 | 715 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/descoberta/Descoberta.java | br.com.node.descoberta.Descoberta | 192 | 6347 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/descoberta/Sementes.java | br.com.node.descoberta.Sementes | 69 | 1726 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/AbiDoExecutor.java | br.com.node.ensaio.AbiDoExecutor | 169 | 5863 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/AssinadorDeTransacao.java | br.com.node.ensaio.AssinadorDeTransacao | 58 | 2214 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Autopsia.java | br.com.node.ensaio.Autopsia | 1039 | 40053 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/AutopsiaDoMercado.java | br.com.node.ensaio.AutopsiaDoMercado | 713 | 26923 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Backrun.java | br.com.node.ensaio.Backrun | 347 | 14703 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/CalculadorDeLance.java | br.com.node.ensaio.CalculadorDeLance | 39 | 1771 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/CustoDeGas.java | br.com.node.ensaio.CustoDeGas | 656 | 30110 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Desfechos.java | br.com.node.ensaio.Desfechos | 343 | 13651 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/DiarioDeEnsaio.java | br.com.node.ensaio.DiarioDeEnsaio | 375 | 15039 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Disparo.java | br.com.node.ensaio.Disparo | 739 | 31554 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Disputa.java | br.com.node.ensaio.Disputa | 372 | 18091 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Gabarito.java | br.com.node.ensaio.Gabarito | 200 | 7745 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Gatilhos.java | br.com.node.ensaio.Gatilhos | 396 | 17224 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Lance48Club.java | br.com.node.ensaio.Lance48Club | 33 | 953 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Lances.java | br.com.node.ensaio.Lances | 204 | 8808 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/MedidorDeGas.java | br.com.node.ensaio.MedidorDeGas | 179 | 6240 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/MetricasDoDisparo.java | br.com.node.ensaio.MetricasDoDisparo | 100 | 3514 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/MontadorDeBundle.java | br.com.node.ensaio.MontadorDeBundle | 165 | 5509 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/PagamentosDeBloco.java | br.com.node.ensaio.PagamentosDeBloco | 188 | 6438 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/RelayPrivado.java | br.com.node.ensaio.RelayPrivado | 448 | 21960 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/RotaDoModelo.java | br.com.node.ensaio.RotaDoModelo | 153 | 6315 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/RpcDoEnsaio.java | br.com.node.ensaio.RpcDoEnsaio | 556 | 22026 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/Salto.java | br.com.node.ensaio.Salto | 66 | 2504 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/TransferenciasInternas.java | br.com.node.ensaio.TransferenciasInternas | 322 | 14707 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/VencedorDoLeilao.java | br.com.node.ensaio.VencedorDoLeilao | 204 | 9398 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/ensaio/VereditorDoContrato.java | br.com.node.ensaio.VereditorDoContrato | 164 | 6085 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/exception/BancoException.java | br.com.node.exception.BancoException | 10 | 205 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/exception/PeerException.java | br.com.node.exception.PeerException | 11 | 202 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/exception/RlpException.java | br.com.node.exception.RlpException | 15 | 285 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/kademlia/Distancia.java | br.com.node.kademlia.Distancia | 47 | 908 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/kademlia/TabelaDeRotas.java | br.com.node.kademlia.TabelaDeRotas | 77 | 1573 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/AprendizDeLayout.java | br.com.node.mempool.AprendizDeLayout | 324 | 11499 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/CadernoDeLayouts.java | br.com.node.mempool.CadernoDeLayouts | 191 | 6752 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/DossieWbnb.java | br.com.node.mempool.DossieWbnb | 97 | 3589 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/ExemploDeMovimento.java | br.com.node.mempool.ExemploDeMovimento | 39 | 1711 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/LayoutAprendido.java | br.com.node.mempool.LayoutAprendido | 176 | 7051 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/LoteEip7702.java | br.com.node.mempool.LoteEip7702 | 90 | 3666 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/MargemDeChegada.java | br.com.node.mempool.MargemDeChegada | 32 | 1304 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/ObservatorioMempool.java | br.com.node.mempool.ObservatorioMempool | 1023 | 44229 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/Pareador.java | br.com.node.mempool.Pareador | 160 | 4915 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/Projecao.java | br.com.node.mempool.Projecao | 11 | 491 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/RoteadorAgregador.java | br.com.node.mempool.RoteadorAgregador | 118 | 4154 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/RoteadorBStocks.java | br.com.node.mempool.RoteadorBStocks | 89 | 3310 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/RoteadorDag.java | br.com.node.mempool.RoteadorDag | 117 | 4129 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/RoteadorDeSaltos.java | br.com.node.mempool.RoteadorDeSaltos | 141 | 5079 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/RoteadorSolidly.java | br.com.node.mempool.RoteadorSolidly | 116 | 4235 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/Roteamento.java | br.com.node.mempool.Roteamento | 337 | 14004 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/RoteamentoUniversal.java | br.com.node.mempool.RoteamentoUniversal | 299 | 9974 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/SeletoresVistos.java | br.com.node.mempool.SeletoresVistos | 74 | 3088 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/Simulador.java | br.com.node.mempool.Simulador | 301 | 9889 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/TransacaoAnunciada.java | br.com.node.mempool.TransacaoAnunciada | 27 | 1018 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/mempool/TransacaoPendente.java | br.com.node.mempool.TransacaoPendente | 230 | 7530 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Arbitragem.java | br.com.node.nucleo.Arbitragem | 26 | 925 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Avaliador.java | br.com.node.nucleo.Avaliador | 231 | 8600 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Ciclo.java | br.com.node.nucleo.Ciclo | 15 | 419 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Curva.java | br.com.node.nucleo.Curva | 70 | 3191 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Direcao.java | br.com.node.nucleo.Direcao | 8 | 234 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/EstadoV3.java | br.com.node.nucleo.EstadoV3 | 177 | 8063 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Grafo.java | br.com.node.nucleo.Grafo | 96 | 2872 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/GrafoDePrecos.java | br.com.node.nucleo.GrafoDePrecos | 679 | 25061 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/MapaDeTicks.java | br.com.node.nucleo.MapaDeTicks | 71 | 2928 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Oportunidade.java | br.com.node.nucleo.Oportunidade | 15 | 483 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Par.java | br.com.node.nucleo.Par | 35 | 1027 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Passo.java | br.com.node.nucleo.Passo | 10 | 340 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Perna.java | br.com.node.nucleo.Perna | 5 | 168 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Pool.java | br.com.node.nucleo.Pool | 41 | 1983 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/PoolSolidlyStable.java | br.com.node.nucleo.PoolSolidlyStable | 137 | 5692 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/PoolV2.java | br.com.node.nucleo.PoolV2 | 58 | 2405 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/PoolV3.java | br.com.node.nucleo.PoolV3 | 295 | 12108 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/ReservasV2.java | br.com.node.nucleo.ReservasV2 | 89 | 3515 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/TickMath.java | br.com.node.nucleo.TickMath | 94 | 3736 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/nucleo/Travessia.java | br.com.node.nucleo.Travessia | 71 | 3080 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Aperto.java | br.com.node.rede.Aperto | 146 | 3863 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Discv4.java | br.com.node.rede.Discv4 | 201 | 6187 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Envelope.java | br.com.node.rede.Envelope | 69 | 2176 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Eth.java | br.com.node.rede.Eth | 306 | 11290 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/LeituraSnappy.java | br.com.node.rede.LeituraSnappy | 72 | 1484 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/P2p.java | br.com.node.rede.P2p | 95 | 2730 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/PortaUdp.java | br.com.node.rede.PortaUdp | 99 | 2182 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Quadros.java | br.com.node.rede.Quadros | 216 | 5736 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Recibos.java | br.com.node.rede.Recibos | 81 | 2364 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Rlpx.java | br.com.node.rede.Rlpx | 115 | 3883 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Segredos.java | br.com.node.rede.Segredos | 72 | 1624 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rede/Snappy.java | br.com.node.rede.Snappy | 153 | 3417 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/repository/ImpostosRepository.java | br.com.node.repository.ImpostosRepository | 146 | 4290 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/repository/PeersRepository.java | br.com.node.repository.PeersRepository | 71 | 1764 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/repository/PoolsNoGerenteRepository.java | br.com.node.repository.PoolsNoGerenteRepository | 138 | 3988 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/repository/ProvasDeTaxaRepository.java | br.com.node.repository.ProvasDeTaxaRepository | 152 | 5358 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/repository/postgres/Postgres.java | br.com.node.repository.postgres.Postgres | 189 | 3792 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/repository/postgres/Trabalho.java | br.com.node.repository.postgres.Trabalho | 10 | 206 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rlp/Rlp.java | br.com.node.rlp.Rlp | 225 | 4839 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rlp/RlpBytes.java | br.com.node.rlp.RlpBytes | 22 | 455 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rlp/RlpItem.java | br.com.node.rlp.RlpItem | 4 | 89 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rlp/RlpLeitor.java | br.com.node.rlp.RlpLeitor | 118 | 2193 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/rlp/RlpLista.java | br.com.node.rlp.RlpLista | 6 | 117 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/ConexaoTcp.java | br.com.node.sessao.ConexaoTcp | 236 | 6368 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/ConexaoUdp.java | br.com.node.sessao.ConexaoUdp | 49 | 1452 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/Lotacao.java | br.com.node.sessao.Lotacao | 444 | 17242 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/Pedidos.java | br.com.node.sessao.Pedidos | 98 | 3941 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/PlacarDePrimeiros.java | br.com.node.sessao.PlacarDePrimeiros | 30 | 911 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/Sessao.java | br.com.node.sessao.Sessao | 400 | 12620 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/Sessoes.java | br.com.node.sessao.Sessoes | 54 | 1311 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sessao/Vinculo.java | br.com.node.sessao.Vinculo | 53 | 1196 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Cabeca.java | br.com.node.sincronizacao.Cabeca | 222 | 8565 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Cursor.java | br.com.node.sincronizacao.Cursor | 433 | 15548 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Desanimo.java | br.com.node.sincronizacao.Desanimo | 54 | 1922 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Faixa.java | br.com.node.sincronizacao.Faixa | 9 | 425 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Frente.java | br.com.node.sincronizacao.Frente | 700 | 26863 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Orcamentos.java | br.com.node.sincronizacao.Orcamentos | 49 | 1681 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/Sincronizacao.java | br.com.node.sincronizacao.Sincronizacao | 93 | 3286 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sincronizacao/VarredorDeGerentes.java | br.com.node.sincronizacao.VarredorDeGerentes | 226 | 7868 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sondagem/Cadeia.java | br.com.node.sondagem.Cadeia | 26 | 1408 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sondagem/Medida.java | br.com.node.sondagem.Medida | 50 | 2108 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sondagem/Ritmo.java | br.com.node.sondagem.Ritmo | 64 | 2257 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sondagem/Rpc.java | br.com.node.sondagem.Rpc | 135 | 4345 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sondagem/Sonda.java | br.com.node.sondagem.Sonda | 294 | 10698 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/sondagem/Sondagem.java | br.com.node.sondagem.Sondagem | 254 | 10367 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Amostras.java | br.com.node.util.Amostras | 50 | 1251 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Diario.java | br.com.node.util.Diario | 178 | 6802 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Ecies.java | br.com.node.util.Ecies | 177 | 4652 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Falhas.java | br.com.node.util.Falhas | 47 | 1903 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Identidade.java | br.com.node.util.Identidade | 71 | 1847 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Keccak.java | br.com.node.util.Keccak | 39 | 681 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Keystore.java | br.com.node.util.Keystore | 102 | 3333 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/NodeKey.java | br.com.node.util.NodeKey | 71 | 1696 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Resources.java | br.com.node.util.Resources | 97 | 2393 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/util/Secp256k1.java | br.com.node.util.Secp256k1 | 191 | 5581 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/AnuncioBloco.java | br.com.node.vo.AnuncioBloco | 16 | 343 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/BlockHeaders.java | br.com.node.vo.BlockHeaders | 15 | 280 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Bloco.java | br.com.node.vo.Bloco | 8 | 354 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Cabecalho.java | br.com.node.vo.Cabecalho | 37 | 1394 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Capacidade.java | br.com.node.vo.Capacidade | 11 | 194 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Correlacionada.java | br.com.node.vo.Correlacionada | 14 | 642 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Desconhecida.java | br.com.node.vo.Desconhecida | 18 | 639 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Disconnect.java | br.com.node.vo.Disconnect | 33 | 1027 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Endpoint.java | br.com.node.vo.Endpoint | 6 | 126 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/EnrRequest.java | br.com.node.vo.EnrRequest | 9 | 157 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/EnrResponse.java | br.com.node.vo.EnrResponse | 28 | 570 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/FindNode.java | br.com.node.vo.FindNode | 21 | 429 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/ForkId.java | br.com.node.vo.ForkId | 22 | 484 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/GetBlockHeaders.java | br.com.node.vo.GetBlockHeaders | 10 | 225 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/GetBlockHeadersDeHash.java | br.com.node.vo.GetBlockHeadersDeHash | 16 | 567 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/GetPooledTransactions.java | br.com.node.vo.GetPooledTransactions | 15 | 274 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/GetReceipts.java | br.com.node.vo.GetReceipts | 15 | 262 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Hello.java | br.com.node.vo.Hello | 18 | 407 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Log.java | br.com.node.vo.Log | 19 | 594 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/MensagemDiscovery.java | br.com.node.vo.MensagemDiscovery | 23 | 443 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/MensagemEth.java | br.com.node.vo.MensagemEth | 14 | 410 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/MensagemP2p.java | br.com.node.vo.MensagemP2p | 11 | 373 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/MensagemRlpx.java | br.com.node.vo.MensagemRlpx | 6 | 127 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Neighbors.java | br.com.node.vo.Neighbors | 11 | 198 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/NewBlock.java | br.com.node.vo.NewBlock | 23 | 856 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/NewBlockHashes.java | br.com.node.vo.NewBlockHashes | 18 | 498 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/NewPooledTransactionHashes.java | br.com.node.vo.NewPooledTransactionHashes | 28 | 880 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/NoRemoto.java | br.com.node.vo.NoRemoto | 28 | 706 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/PacoteAberto.java | br.com.node.vo.PacoteAberto | 4 | 113 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/PacoteDiscovery.java | br.com.node.vo.PacoteDiscovery | 4 | 120 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Ping.java | br.com.node.vo.Ping | 9 | 184 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/PingP2p.java | br.com.node.vo.PingP2p | 11 | 266 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Pong.java | br.com.node.vo.Pong | 22 | 478 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/PongP2p.java | br.com.node.vo.PongP2p | 9 | 127 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/PooledTransactions.java | br.com.node.vo.PooledTransactions | 16 | 350 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/ProvaDeImposto.java | br.com.node.vo.ProvaDeImposto | 15 | 881 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/ProvaDeTaxa.java | br.com.node.vo.ProvaDeTaxa | 14 | 783 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Receipts.java | br.com.node.vo.Receipts | 17 | 438 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Status.java | br.com.node.vo.Status | 16 | 487 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/SwapPendente.java | br.com.node.vo.SwapPendente | 26 | 1163 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/Transactions.java | br.com.node.vo.Transactions | 16 | 316 | ⬜ | ⬜ | ⬜ | |
| src/main/java/br/com/node/vo/UpgradeStatus.java | br.com.node.vo.UpgradeStatus | 12 | 420 | ⬜ | ⬜ | ⬜ | |
