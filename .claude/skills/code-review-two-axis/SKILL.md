---
name: code-review-two-axis
description: Revisão das mudanças desde um ponto fixo (commit, branch, tag) em dois eixos independentes — Standards (segue os padrões documentados do VoipIA?) e Spec (implementa fielmente o que foi pedido?). Roda os dois em subagentes paralelos e reporta lado a lado. Use quando o usuário pedir para revisar uma branch, um PR, mudanças em andamento, ou "revisa desde X".
---

# Code Review em Dois Eixos

Revisão do diff entre `HEAD` e um ponto fixo informado pelo usuário, em dois eixos que **não se misturam**:

- **Standards** — o código segue os padrões documentados deste repositório (CLAUDE.md, `.claude/rules/common/*.md`) e evita os code smells clássicos?
- **Spec** — o código implementa fielmente o que foi pedido (issue/ticket/conversa/PRD que originou a mudança)?

Os dois eixos rodam como **subagentes paralelos** (uma única mensagem, duas chamadas `Agent`) para que um não contamine o contexto do outro. Depois, agregue os achados sem misturá-los.

## Processo

### 1. Fixar o ponto de comparação

O usuário diz o ponto fixo — um SHA, branch, tag, `main`, `HEAD~5`. Se não disser, pergunte.

Capture o comando do diff: `git diff <ponto-fixo>...HEAD` (three-dot, contra o merge-base). Anote também `git log <ponto-fixo>..HEAD --oneline`.

Confirme antes de seguir que o ref resolve (`git rev-parse <ponto-fixo>`) e que o diff não está vazio. Uma ref inválida ou diff vazio deve falhar aqui — não dentro dos dois subagentes em paralelo.

### 2. Identificar a fonte do spec

O VoipIA não usa issue tracker externo — o spec normalmente está na própria conversa. Procure, nesta ordem:

1. Um caminho ou trecho que o usuário passou como argumento (ex.: "revisa contra o que combinamos sobre RBAC").
2. O contexto da conversa atual — o que foi pedido/discutido antes desta mudança.
3. Uma seção relevante do `CLAUDE.md` (ex.: "Pendências conhecidas") que descreve o comportamento esperado.
4. Se nada for encontrado, pergunte ao usuário. Se ele disser que não há spec formal, o subagente de **Spec** deve relatar "sem spec disponível" em vez de inventar critério.

### 3. Identificar as fontes de Standards

- `CLAUDE.md` (seção "Princípios de trabalho" e as convenções de arquitetura/stack descritas).
- `.claude/rules/common/coding-style.md`, `security.md`, `code-review.md`, `testing.md`.
- `CONTEXT.md` (linguagem de domínio — nomes de variáveis/funções/tipos devem usar os termos corretos, não os sinônimos em `_Avoid_`).

Além do que o repositório documenta, o eixo Standards sempre carrega a **baseline de smells** abaixo — smells clássicos de Fowler (_Refactoring_, cap. 3), válidos mesmo quando o repositório não documenta nada sobre eles. Duas regras:

- **O repositório tem prioridade.** Um padrão documentado sempre vence; onde ele endossa algo que a baseline marcaria, suprima o smell.
- **É sempre um julgamento, não uma violação dura.** Cada smell é um sinal rotulado ("possível Feature Envy"), e pule qualquer coisa que ferramenta automatizada já cubra (Spotless, ESLint/tsc, ruff/black/mypy, bandit).

Smells (o que é → como corrigir):

- **Nome Misterioso** — função/variável/tipo cujo nome não revela o que faz ou guarda. → renomear; se nenhum nome honesto aparecer, o design é confuso.
- **Código Duplicado** — a mesma lógica aparece em mais de um hunk/arquivo do diff. → extrair o trecho comum, chamar dos dois lugares.
- **Feature Envy** — um método mexe mais nos dados de outro objeto do que nos próprios. → mover o método para perto do dado que ele usa.
- **Data Clumps** — os mesmos campos/parâmetros sempre viajam juntos (um tipo querendo nascer). → agrupar num tipo só.
- **Obsessão por Primitivo** — um primitivo/string representando um conceito de domínio que merece tipo próprio (ex.: um `resource_key` como string solta em vez de um tipo).
- **Switches Repetidos** — o mesmo `switch`/cascata de `if` sobre o mesmo tipo se repete no diff. → polimorfismo, ou um mapa único compartilhado pelos dois pontos.
- **Shotgun Surgery** — uma mudança lógica força edições espalhadas por muitos arquivos do diff. → reunir o que muda junto num módulo.
- **Divergent Change** — um arquivo/módulo é editado por vários motivos não relacionados. → separar para que cada módulo mude por um motivo só.
- **Generalidade Especulativa** — abstração, parâmetro ou hook adicionado para uma necessidade que o spec não pede. → remover; reinserir só quando a necessidade for real (YAGNI).
- **Message Chains** — navegação longa tipo `a.b().c().d()` que o chamador não deveria depender. → esconder o caminho atrás de um método no primeiro objeto.
- **Middle Man** — classe/função que só repassa a chamada adiante. → cortar, chamar o alvo real direto.
- **Refused Bequest** — subclasse/implementação que ignora ou sobrescreve a maior parte do que herda. → trocar herança por composição.

### 4. Disparar os dois subagentes em paralelo

Envie uma única mensagem com duas chamadas `Agent` (subagent_type `general-purpose`).

**Prompt do subagente Standards** — inclua:

- O comando do diff e a lista de commits.
- As fontes de standards do passo 3, **mais a baseline de smells colada por inteiro** — o subagente não tem outro acesso a ela.
- A instrução: "Relate — por arquivo/hunk quando fizer sentido — (a) todo lugar onde o diff viola um padrão documentado: cite o padrão (arquivo + regra); e (b) qualquer smell da baseline que você notar: nomeie e cite o trecho. Distinga violações duras (padrão documentado) de julgamentos (smells da baseline) — um padrão documentado do repositório sempre tem prioridade sobre a baseline. Ignore o que já é coberto por ferramenta automatizada. Menos de 400 palavras."

**Prompt do subagente Spec** — inclua:

- O comando do diff e a lista de commits.
- O spec (trecho da conversa, seção do CLAUDE.md, ou o que o usuário passou).
- A instrução: "Relate: (a) requisitos pedidos que estão faltando ou parciais; (b) comportamento no diff que não foi pedido (scope creep); (c) requisitos que parecem implementados mas cuja implementação parece errada. Cite a linha do spec para cada achado. Menos de 400 palavras."

Se não houver spec, pule o subagente de Spec e anote isso no relatório final.

### 5. Agregar

Apresente os dois relatórios sob os títulos `## Standards` e `## Spec`, literalmente ou levemente limpos. **Não** misture nem reordene achados entre os eixos — a separação é proposital.

Feche com um resumo de uma linha: total de achados por eixo, e o pior achado **dentro de cada eixo** (se houver). Não escolha um "vencedor" único entre os dois eixos — isso é exatamente o reranking que a separação existe para evitar.

## Por que dois eixos

Uma mudança pode passar em um eixo e falhar no outro:

- Código que segue todo padrão mas implementa a coisa errada → **Standards passa, Spec falha.**
- Código que faz exatamente o que foi pedido mas quebra as convenções do projeto → **Spec passa, Standards falha.**

Relatar separado evita que um eixo esconda o outro — ex.: um PR de RBAC que segue perfeitamente `coding-style.md` mas esqueceu de aplicar `require_permission` num endpoint (falha de Spec, não de Standards).
