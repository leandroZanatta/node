
---
name: refatoracao-java-segura
description: Analisa e refatora incrementalmente projetos Java existentes, preservando comportamento, reduzindo acoplamento, aplicando encapsulamento, orquestração hierárquica e organização consistente dos fontes. Use para reorganizar classes grandes, especialmente Main, sem reescrever o sistema indiscriminadamente.
---

# Refatoração Java Segura

## 1. Objetivo

Tornar o projeto mais compreensível, coeso, desacoplado e sustentável, preservando seu comportamento observável.

A refatoração deve melhorar a arquitetura real do sistema, não apenas reduzir o tamanho dos arquivos ou distribuir métodos entre mais classes.

Prioridades:

1. Preservar o comportamento existente.
2. Recuperar e documentar a arquitetura real.
3. Reduzir acoplamento e dependências desnecessárias.
4. Encapsular detalhes internos.
5. Minimizar a superfície pública das classes.
6. Organizar a orquestração em níveis de responsabilidade.
7. Padronizar a organização dos arquivos-fonte.
8. Validar cada alteração antes de prosseguir.

Esta skill é especialmente importante para aplicações Java com concorrência, rede P2P, processamento de blocos e transações, callbacks, tarefas agendadas, persistência e execução financeira.

## 2. Regras inegociáveis

1. Não reescreva o projeto inteiro de uma vez.
2. Não altere regras de negócio por iniciativa própria.
3. Não invente uma arquitetura sem antes compreender a existente.
4. Não crie abstrações, interfaces, fábricas, serviços ou orquestradores sem responsabilidade concreta.
5. Não aumente a visibilidade de classes ou métodos apenas para resolver problemas de compilação após uma extração.
6. Não transforme classes internas em APIs públicas sem necessidade demonstrada.
7. Não exponha objetos internos quando uma operação de alto nível for suficiente.
8. Não passe a classe Main inteira ou um contêiner global de dependências para novas classes como atalho de injeção.
9. Não mova código sem rastrear seus chamadores, dependências, efeitos colaterais e ciclo de vida.
10. Não misture refatoração estrutural com mudanças funcionais, otimizações ou atualização de dependências.
11. Não remova código aparentemente sem uso sem verificar todos os seus possíveis consumidores.
12. Não esconda falhas com exceções genéricas, valores artificiais ou logs que indiquem sucesso indevidamente.
13. Não afirme que testes passaram se eles não foram executados.
14. Não sobrescreva alterações preexistentes do usuário.
15. Não faça commits, pushes, deploys ou migrações sem autorização explícita.
16. Não adicione comentários aos arquivos-fonte, conforme a seção de padronização.
17. Não considere uma refatoração bem-sucedida apenas porque o projeto compila.

## 3. Fase inicial: proteger e compreender

Antes de editar qualquer arquivo:

1. Inspecione `git status --short`.
2. Identifique alterações preexistentes e preserve-as.
3. Leia as instruções do repositório, como `CLAUDE.md`, `README.md` e documentação arquitetural.
4. Identifique a versão do Java, o sistema de build e os comandos de compilação e teste.
5. Inspecione a estrutura dos pacotes, as classes existentes e os testes relevantes.
6. Identifique configurações, variáveis de ambiente e integrações externas.

Não comece a refatorar antes de concluir o diagnóstico inicial.

## 4. Diagnóstico arquitetural obrigatório

A análise inicial deve ser somente leitura.

Siga o fluxo real de execução, começando pelo ponto de entrada e rastreando as chamadas até as implementações responsáveis.

Mapeie:

- Inicialização e encerramento da aplicação.
- Dependências entre classes e pacotes.
- Responsabilidades de cada componente.
- Fluxo de dados e fluxo de controle.
- Regras de negócio.
- Threads, executores, filas, locks e estado compartilhado.
- Callbacks, listeners e tarefas agendadas.
- Persistência e efeitos externos.
- Tratamento de erros e falhas parciais.
- Criação, propriedade e encerramento de recursos.
- Classes e métodos públicos que não precisam ser públicos.
- Dependências que atravessam limites de responsabilidade.
- Classes que coordenam demasiados subsistemas.
- Componentes que deveriam encapsular seus próprios detalhes.

Classifique os achados em:

- Confirmado: sustentado diretamente pelo código.
- Suspeita: hipótese que precisa de investigação.
- Preferência: melhoria opcional de estilo ou organização.

Use arquivos, classes e métodos reais como evidência. Não deduza responsabilidades apenas pelos nomes.

## 5. Arquitetura-alvo: orquestração hierárquica

O objetivo é organizar a aplicação em níveis de orquestração coerentes.

A regra fundamental é:

**Cada nível conhece apenas a API mínima de seus componentes subordinados. Os detalhes internos permanecem encapsulados.**

A hierarquia deve refletir responsabilidades e ciclos de vida reais, não uma divisão artificial de arquivos.

### 5.1. Main deve ser mínimo

A classe `Main` deve funcionar como ponto de entrada da aplicação.

Idealmente, ela conhece somente o orquestrador geral e invoca sua operação de inicialização, como `start()`, além do encerramento de alto nível quando necessário.

A classe `Main` não deve conhecer detalhes internos de:

- Discovery.
- Sessões.
- Callbacks.
- Gerenciamento de conexões.
- Políticas de reconexão.
- Gerenciamento de peers.
- Regras de negócio de mercado.
- Avaliação e execução de arbitragem.
- Detalhes de implementação dos demais subsistemas.

Essas responsabilidades devem ficar nos componentes ou orquestradores proprietários.

Não mantenha regras de negócio, decisões operacionais detalhadas ou coordenação interna de vários subsistemas dentro de `Main`.

### 5.2. Orquestrador geral

O orquestrador geral coordena os principais subsistemas da aplicação.

Suas responsabilidades podem incluir:

- Inicializar os subsistemas na ordem necessária.
- Coordenar o ciclo de vida da aplicação.
- Encerrar os componentes na ordem correta.
- Tratar falhas durante a inicialização parcial.
- Expor ao `Main` somente a API de ciclo de vida necessária.

Ele não deve absorver as regras de negócio removidas de `Main`.

Não crie um novo objeto central que conheça todos os detalhes internos da aplicação. O orquestrador geral deve coordenar os subsistemas, não implementá-los.

### 5.3. Orquestradores intermediários

Cada subsistema complexo pode ter seu próprio orquestrador.

Exemplo conceitual para o nó P2P:

- `Main` conhece o orquestrador geral.
- O orquestrador geral conhece `NodeOrchestrator`.
- `NodeOrchestrator` coordena `Session`, `Discovery` e o tratamento de callbacks.
- `NodeOrchestrator` gerencia as políticas de conexão, os limites de conexões, a reconexão e o gerenciamento de peers, quando essas responsabilidades fizerem parte do subsistema do nó.
- As classes internas expõem somente as operações necessárias ao orquestrador do nó.

Nesse modelo, `Main` não precisa conhecer `Discovery`, sessões ou callbacks. Ele solicita que a aplicação inicie o nó por meio da API do orquestrador responsável.

A mesma abordagem pode ser considerada para mercado, arbitragem, API HTTP, persistência e outros grupos, desde que o código confirme que são subsistemas coesos.

Este exemplo é uma referência de organização, não uma ordem para criar todas essas classes. Primeiro identifique o que já existe e determine os limites adequados.

### 5.4. Não criar orquestradores artificiais

Um orquestrador precisa possuir uma responsabilidade concreta de coordenação, política ou ciclo de vida.

Evite:

- Classes que apenas repassam chamadas sem agregar responsabilidade.
- Árvores excessivamente profundas.
- Um orquestrador geral que conhece todos os métodos dos filhos.
- Um orquestrador que reproduz toda a lógica da classe original.
- Classes que precisam receber dezenas de dependências para funcionar.
- Dependências circulares entre orquestradores.
- Componentes subordinados que dependem de `Main`.

Se a extração apenas deslocar o acoplamento, reavalie o limite arquitetural.

## 6. Encapsulamento e exposição mínima

A visibilidade deve ser a menor possível, compatível com o funcionamento e os contratos legítimos do sistema.

Use como padrão:

- `private` para implementação interna.
- Visibilidade de pacote quando os consumidores legítimos pertencem ao mesmo pacote.
- `public` somente quando houver consumidores externos reais ou uma API deliberadamente exposta.

Analise individualmente classes, métodos, construtores, campos e tipos internos.

### 6.1. Não exponha detalhes desnecessários

Evite expor:

- Executores e threads internos.
- Sockets e conexões de rede.
- Sessões e peers internos.
- Coleções mutáveis.
- Estado interno de componentes.
- Repositórios que só um subsistema precisa acessar.
- Objetos que dão acesso indireto a outros subsistemas.
- Métodos auxiliares que deveriam ser privados.
- Classes concretas que só precisam ser conhecidas pelo próprio orquestrador.

Não crie getters e setters indiscriminadamente. Prefira operações de domínio com finalidade clara.

Se um consumidor precisa apenas iniciar uma operação, exponha essa operação em vez de entregar o objeto interno completo.

### 6.2. Visibilidade não é um mecanismo de conveniência

Não torne uma classe pública apenas porque outra classe precisa instanciá-la.

Antes de ampliar a visibilidade, investigue se:

- A criação deveria pertencer ao orquestrador proprietário.
- A classe está no pacote correto.
- O limite entre responsabilidades está mal definido.
- Uma dependência está atravessando indevidamente a arquitetura.
- Uma operação de alto nível pode substituir o acesso direto.

Não transforme tudo em `public` para fazer uma refatoração compilar.

### 6.3. APIs mínimas entre pai e filho

Para cada relação entre componentes, determine:

1. Quais operações o pai realmente precisa executar.
2. Quais consultas são indispensáveis.
3. Quais eventos ou callbacks precisam atravessar a fronteira.
4. Quem controla o estado.
5. Quem cria e encerra os recursos.
6. Como as falhas são propagadas.

O pai não deve conhecer todos os métodos do filho. O filho não deve conhecer o pai inteiro.

Evite callbacks que capturem `Main` ou um orquestrador superior quando isso puder ser substituído por um contrato mais restrito.

Não introduza um barramento de eventos genérico apenas para eliminar referências diretas.

## 7. Responsabilidade e propriedade de recursos

Todo recurso deve ter um proprietário claramente definido.

Isso inclui:

- Executores.
- Threads.
- Sockets.
- Sessões.
- Clientes RPC.
- Timers.
- Filas.
- Conexões de banco.
- Listeners e callbacks.
- Estados compartilhados.

O componente proprietário deve coordenar criação, uso, encerramento e falhas parciais.

Não transfira a propriedade de um recurso sem rastrear todos os seus consumidores e o ciclo de vida atual.

Não permita que dois componentes assumam responsabilidade independente pelo encerramento do mesmo recurso.

## 8. Organização obrigatória dos arquivos Java

Todo arquivo-fonte alterado deve seguir uma organização consistente.

Antes de definir ou alterar o padrão, inspecione o estilo predominante do projeto. Adote uma convenção única, compatível com o código existente, e aplique-a aos arquivos do escopo aprovado.

A ordem padrão é:

1. Declaração `package`.
2. Imports.
3. Declaração da classe, interface, enum ou record.
4. Constantes e campos estáticos.
5. Campos de instância.
6. Construtores.
7. Métodos públicos.
8. Métodos protegidos.
9. Métodos com visibilidade de pacote.
10. Métodos privados.
11. Tipos internos, se realmente necessários.

### 8.1. Imports

- Remova imports não utilizados nos arquivos alterados.
- Agrupe e ordene imports de maneira consistente.
- Evite imports duplicados.
- Preserve as convenções de organização adotadas pelo projeto.
- Não faça reformatação indiscriminada de arquivos fora do escopo aprovado.

### 8.2. Campos e métodos

- Não misture campos, construtores e métodos em ordem aleatória.
- Agrupe campos relacionados por responsabilidade.
- Mantenha constantes separadas dos campos de instância.
- Organize os métodos públicos para deixar clara a API da classe.
- Agrupe métodos auxiliares privados depois dos métodos que os utilizam, quando isso melhorar a leitura.
- Organize os métodos por responsabilidade e fluxo de leitura, não apenas por ordem alfabética.
- Não use a organização visual para disfarçar uma classe com responsabilidades demais.

### 8.3. Consistência

Cada classe deve apresentar uma estrutura previsível.

Ao abrir qualquer arquivo, deve ser fácil localizar:

- O que a classe expõe.
- Qual estado ela mantém.
- Como é construída.
- Quais operações oferece.
- Quais detalhes são internos.

Não misture estilos diferentes de organização dentro do mesmo conjunto de classes.

## 9. Proibição de comentários nos fontes

**Não adicione comentários aos arquivos-fonte.**

Isso inclui comentários de linha, blocos de comentário, Javadocs e comentários de TODO.

Não use comentários para:

- Narrar o que o código está fazendo.
- Explicar métodos mal nomeados.
- Justificar acoplamento desnecessário.
- Delimitar artificialmente seções de uma classe.
- Documentar etapas de refatoração.
- Substituir nomes claros e responsabilidades bem definidas.

Quando o código for difícil de entender, melhore a estrutura, os nomes e os limites das responsabilidades.

A documentação arquitetural deve ficar em arquivos próprios, como `ARCHITECTURE.md`, e não em comentários dentro dos fontes.

Não remova em massa comentários existentes como efeito colateral de uma refatoração. Se a remoção de comentários existentes fizer parte de uma padronização, proponha uma etapa explícita, verifique se há informações operacionais relevantes e obtenha aprovação antes de executá-la.

Preserve comentários exigidos por licenças, ferramentas, geração de código ou contratos externos até que a necessidade de removê-los seja analisada e aprovada.

## 10. Refatoração incremental

Após o diagnóstico, apresente um plano de etapas pequenas.

Cada etapa deve especificar:

- Objetivo.
- Responsabilidade a isolar.
- Arquivos e métodos envolvidos.
- Dependências e consumidores.
- API mínima esperada.
- Alterações de visibilidade previstas.
- O que não será alterado.
- Riscos.
- Testes e verificações.
- Critério de conclusão.
- Forma de reversão.

Comece pela etapa de menor risco que produza um ganho arquitetural real.

Não faça movimentação massiva de métodos. Prefira uma responsabilidade coesa por etapa.

**Aguarde aprovação antes de editar**, a menos que o usuário já tenha autorizado explicitamente a execução do plano completo em etapas.

A aprovação de uma etapa não autoriza ampliar o escopo.

## 11. Implementação de cada etapa

Para cada etapa aprovada:

1. Releia os trechos envolvidos e seus chamadores.
2. Faça o menor diff coerente possível.
3. Preserve inicialmente o corpo e a ordem das operações ao mover código.
4. Não combine movimentação de código com otimizações ou mudanças funcionais.
5. Ajuste a visibilidade para o mínimo necessário.
6. Evite dependências da classe `Main` em componentes extraídos.
7. Não exponha classes internas apenas para facilitar a extração.
8. Não crie interfaces sem necessidade concreta.
9. Preserve o tratamento de exceções e os efeitos colaterais.
10. Preserve a ordem de inicialização e encerramento.
11. Preserve callbacks, contratos e semântica de concorrência.
12. Inspecione o diff completo antes de concluir.

Se a refatoração exigir uma mudança de comportamento, interrompa a etapa e explique a necessidade separadamente. Não esconda mudanças funcionais dentro de uma refatoração estrutural.

## 12. Cuidados especiais com concorrência e blockchain

Trate os seguintes aspectos como invariantes até que uma mudança seja explicitamente aprovada:

- Ordem dos eventos e callbacks.
- Idempotência e deduplicação.
- Nonces e estados de transações.
- Confirmação de blocos e transações.
- Timeouts, retries e circuit breakers.
- Precisão e unidades de `BigInteger` e `BigDecimal`.
- Conversões entre wei, gwei e BNB.
- Condições de corrida e visibilidade de estado.
- Locks e sincronização.
- Execução e encerramento de executores agendados.
- Persistência antes ou depois de efeitos externos.
- Comportamento quando RPC, banco ou peers falham.
- Estratégia de arbitragem e critérios de seleção.
- Fórmulas de lucro e parâmetros econômicos.
- Limites de gas e valores de lance.
- Configurações e variáveis de ambiente.

Não altere esses comportamentos durante uma refatoração estrutural.

Não exponha segredos encontrados nos arquivos. Informe apenas a existência e a localização aproximada de um possível segredo.

## 13. Verificação obrigatória

Depois de cada etapa:

1. Execute a compilação pertinente.
2. Execute os testes relevantes disponíveis.
3. Verifique imports, referências e contratos.
4. Inspecione o diff completo.
5. Confirme que nenhuma alteração não relacionada foi introduzida.
6. Verifique se a nova API expõe somente o necessário.
7. Verifique se os componentes deixaram de depender de internals indevidos.
8. Revise inicialização, callbacks, concorrência e shutdown.
9. Informe os testes não executados e suas limitações.

Se a verificação falhar, investigue e corrija somente os problemas introduzidos pela etapa atual. Não avance enquanto a etapa não estiver consistente.

Não afirme equivalência comportamental absoluta sem evidências suficientes.

## 14. Critérios de qualidade

Uma refatoração é positiva quando:

- As responsabilidades são claras.
- As dependências são menores ou mais explícitas.
- Cada componente conhece somente o necessário.
- A visibilidade é mínima e deliberada.
- Os orquestradores possuem responsabilidades reais.
- A classe `Main` fica restrita à entrada e à orquestração de alto nível.
- O estado e o ciclo de vida possuem responsáveis definidos.
- Os arquivos seguem uma organização consistente.
- Não foram adicionados comentários aos fontes.
- Não foram criadas abstrações artificiais.
- O diff pode ser revisado por uma pessoa.
- A compilação e os testes relevantes foram executados ou suas limitações foram registradas.
- O comportamento observável foi preservado tanto quanto as evidências permitem concluir.

Menos linhas, mais arquivos ou mais interfaces não são, isoladamente, evidência de qualidade.

## 15. Relatório obrigatório por etapa

Ao concluir uma etapa, informe:

1. Resultado arquitetural.
2. Arquivos alterados e motivo.
3. Responsabilidades movidas ou isoladas.
4. Dependências removidas ou reduzidas.
5. Alterações de visibilidade.
6. Comportamentos e contratos preservados.
7. Comandos executados e resultados reais.
8. Riscos restantes e pontos ainda não verificados.
9. Próxima etapa sugerida.

Não comece a próxima etapa automaticamente, salvo autorização explícita para executar uma sequência de etapas.

## 16. Instrução inicial

Quando solicitado a refatorar um projeto existente:

1. Proteja o estado atual do repositório.
2. Faça o diagnóstico somente leitura.
3. Mapeie a arquitetura real e os principais fluxos.
4. Identifique problemas de coesão, acoplamento, visibilidade e orquestração.
5. Proponha uma hierarquia de responsabilidades baseada em evidências.
6. Defina APIs mínimas entre os níveis.
7. Apresente um plano incremental.
8. Aguarde aprovação antes de alterar arquivos.

Não comece pela criação de classes. Comece pela compreensão do sistema.
