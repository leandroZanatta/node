# Refatoração Java Segura

Skill para Claude Code e Claude.ai focada em refatoração incremental, preservação de comportamento e revisão de diffs.

## Claude Code — instalação no projeto

Na raiz do repositório Java:

```bash
mkdir -p .claude/skills
unzip refatoracao-java-segura.zip -d .claude/skills/
```

Isso deve resultar em:

```text
.claude/skills/refatoracao-java-segura/SKILL.md
```

Depois, invoque `/refatoracao-java-segura` ou peça ao Claude Code para usar a skill na análise/refatoração.

## Claude Code — instalação pessoal

Extraia a pasta em:

```text
~/.claude/skills/refatoracao-java-segura/SKILL.md
```

## Claude.ai

Carregue o arquivo ZIP em Settings/Configurações → Features/Funcionalidades → Skills, se esse recurso estiver disponível na sua conta.

## Primeiro comando recomendado

```text
Use a skill refatoracao-java-segura no modo somente leitura.
Analise o Main e rastreie o fluxo real de inicialização e de uma arbitragem.
Não altere nenhum arquivo. Mostre evidências com arquivo/método,
riscos confirmados versus suspeitas e proponha etapas pequenas de refatoração.
```
