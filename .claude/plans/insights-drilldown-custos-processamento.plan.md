# Plan: Drill-down de Custos IA e Processamento para Chamadas

**Status:** aprovado, pronto para implementação — nenhuma fase iniciada ainda.
**Origem:** pedido livre — "na tela de Custos IA e Processamento, sempre que eu clicar em alguma
linha, devo ser direcionado diretamente para o item já filtrado na tela de chamadas."
**Complexidade:** Small/Medium (1 campo novo de filtro no backend + 2 componentes de frontend
ganham `onClick` reaproveitando o mecanismo de drill-down já existente)

## Como o drill-down já funciona hoje (Dashboard → Chamadas)
`insights-platform/frontend/src/App.tsx`:
- Estado `pendingDrillDown: { filters: InsightsDrillDownFilters; nonce: number } | null`
  (`App.tsx:64`) — o `nonce` garante que o `useEffect` de `InsightsTab` dispare mesmo se os
  mesmos filtros forem enviados duas vezes seguidas.
- `handleDrillDown(filters)` (`App.tsx:88-91`) empacota os filtros + `nonce++` e troca a aba
  ativa para `'calls'` — a troca de aba é o que desmonta a aba de origem e monta `InsightsTab`.
- `handleDrillDownConsumed()` (`App.tsx:93`) limpa `pendingDrillDown` depois que `InsightsTab`
  aplica o filtro, evitando reaplicação numa navegação manual futura.
- Hoje só `InsightsDashboardTab` recebe `onDrillDown` e só `InsightsTab` recebe
  `pendingDrillDown`/`onDrillDownConsumed` (`App.tsx:127-128`).
- `InsightsDrillDownFilters` é definido e exportado de dentro de `InsightsDashboardTab.tsx:17-21`
  (`{ categoria?, criticidade?, findingType? }`) e importado de lá em `App.tsx:5` — não vive num
  lugar compartilhado hoje porque só um produtor existia.
- `InsightsTab.tsx:142-154` — o `useEffect` que consome `pendingDrillDown` zera todos os outros
  filtros do formulário, aplica só os campos vindos do drill-down, abre o painel de filtros
  (`setFiltersOpen(true)`) e chama `loadCalls(0, overrides)` passando os valores diretamente (não
  via state, por causa da assincronia do `setState` — evita "correr atrás" do próprio filtro).

## Achado decisivo: `id` é a mesma PK em Chamadas, Custos e Processamento
Confirmado no backend (`InsightCostView.java`, `InsightProcessingItem.java`, `InsightsListItem.java`
— todos chamam `audioFile.getId()`): o `id` de uma linha em Custos IA, Processamento e Chamadas é
literalmente o mesmo `CallAudioFile.id`. Isso permite um drill-down **exato** (uma chamada
específica), não só aproximado por atendente/data — mais preciso do que o drill-down atual do
Dashboard (que só filtra por `categoria`/`criticidade`/`findingType`, campos agregados).

**Decisão:** adicionar um novo campo `id` a `InsightsDrillDownFilters` e ao filtro do backend
(`InsightsFilter`), em vez de tentar aproximar por `agentName`+data — mais simples, sempre exato,
e funciona mesmo se dois atendentes tiverem nomes parecidos ou a mesma data.

**Decisão sobre a aba Processamento (confirmada com o usuário):** só linhas com `status === 'done'`
navegam para Chamadas — `pending`/`processing` ainda não têm `CallInsight` associado (a análise de
IA só roda depois que o processamento termina), então não haveria nada para mostrar. Linhas com
`status === 'error'` continuam com o comportamento atual (expandir a mensagem de erro inline,
`InsightsProcessingTab.tsx:148-167`), sem navegar.

**Custos IA:** como essa aba só lista chamadas já com custo calculado (ou seja, já processadas com
sucesso), todas as linhas têm uma chamada correspondente em `call_insights` — não há caso "vazio"
a tratar aqui, todas as linhas ficam clicáveis.

## Patterns to Mirror
| Categoria | Origem | Padrão |
|---|---|---|
| Produtor de drill-down | `InsightsDashboardTab.tsx` (KPIs/gráficos chamando `onDrillDown({...})`) | Novo `onClick` na `<tr>` chamando `onDrillDown({ id: c.id })` |
| Consumidor de drill-down | `InsightsTab.tsx:142-154` (`useEffect` no `pendingDrillDown.nonce`) | Adicionar `id` ao destructuring e ao `loadCalls(0, overrides)` |
| Filtro dinâmico do backend | `InsightsSpecifications.java:23-50` (predicados condicionais por campo) | Adicionar predicado `cb.equal(root.get("id"), filter.id())` quando `filter.id() != null` |
| Parâmetro de filtro na controller | `InsightsController.java:40-64` (`@RequestParam` por campo, monta `InsightsFilter`) | Adicionar `@RequestParam(required = false) Long id` |
| Record de filtro | `InsightsFilter.java` (campos opcionais, todos ignorados se nulos) | Adicionar `Long id` ao record |

## Arquivos a criar/alterar
| Arquivo | Ação | Motivo |
|---|---|---|
| `backend/.../insights/InsightsFilter.java` | UPDATE | Adiciona campo `Long id` |
| `backend/.../insights/InsightsSpecifications.java` | UPDATE | Predicado de igualdade por `id` quando presente |
| `backend/.../insights/InsightsController.java` | UPDATE | `@RequestParam(required = false) Long id` em `GET /insights/calls`, repassado ao `InsightsFilter` |
| `insights-platform/frontend/src/api/types.ts` | UPDATE | Move `InsightsDrillDownFilters` para cá (tipo compartilhado por 3 produtores agora) com o novo campo `id?: number` |
| `insights-platform/frontend/src/components/InsightsDashboardTab.tsx` | UPDATE | Importa `InsightsDrillDownFilters` de `api/types.ts` em vez de declarar localmente; re-exporta ou App.tsx importa do novo local |
| `insights-platform/frontend/src/App.tsx` | UPDATE | Importa `InsightsDrillDownFilters` de `api/types.ts`; passa `onDrillDown={handleDrillDown}` para `InsightsCostsTab` e `InsightsProcessingTab` |
| `insights-platform/frontend/src/components/InsightsCostsTab.tsx` | UPDATE | Recebe prop `onDrillDown`; `<tr onClick={() => onDrillDown({ id: c.id })} style={{cursor:'pointer'}}>` |
| `insights-platform/frontend/src/components/InsightsProcessingTab.tsx` | UPDATE | Recebe prop `onDrillDown`; `onClick` da `<tr>` passa a: se `status==='done'`, chama `onDrillDown({ id: item.id })`; se `status==='error'`, mantém o toggle atual; `pending`/`processing` sem `onClick` |
| `insights-platform/frontend/src/components/InsightsTab.tsx` | UPDATE | `useEffect` de drill-down (linha ~142) passa a aplicar/repassar também `id` para `loadCalls` |
| `frontend/src/data/releases.ts` | UPDATE | Nova versão (obrigatório) |

## Tarefas (fases)

### Fase 1 — Backend: filtro exato por `id`
- `InsightsFilter.java`: adicionar `Long id` ao record (posição livre, ex: primeiro campo).
- `InsightsSpecifications.java`: adicionar, junto aos demais `if`, o predicado
  `if (filter.id() != null) { predicates = cb.and(predicates, cb.equal(root.get("id"), filter.id())); }`.
- `InsightsController.java`: adicionar `@RequestParam(required = false) Long id` em `listCalls` e
  repassar como primeiro argumento posicional do novo `InsightsFilter(...)`.
- **Validar:** `docker run --rm -v "$(pwd)/backend":/build -w /build maven:3.9-eclipse-temurin-21
  mvn -q compile` (mesmo mecanismo já usado nesta sessão, já que `mvn` não está instalado
  localmente); depois de deployado, `curl` em `/api/v1/insights/calls?id=<algum id real>` retorna
  só aquela chamada.

### Fase 2 — Frontend: tipo compartilhado `InsightsDrillDownFilters`
- Mover a interface de `InsightsDashboardTab.tsx:17-21` para `api/types.ts`, adicionando `id?:
  number`:
  ```ts
  export interface InsightsDrillDownFilters {
    id?: number;
    categoria?: string;
    criticidade?: string;
    findingType?: string;
  }
  ```
- `InsightsDashboardTab.tsx`: trocar a declaração local por `import type { InsightsDrillDownFilters
  } from '../api/types';` (mantém o uso de `onDrillDown` como está — nenhum KPI passa `id` hoje).
- `App.tsx`: trocar `import { InsightsDashboardTab, type InsightsDrillDownFilters } from
  './components/InsightsDashboardTab';` por importar o tipo de `api/types` e o componente de
  `InsightsDashboardTab` separadamente.
- **Validar:** `npx tsc --noEmit` (deve continuar limpo — é só realocação de tipo).

### Fase 3 — `InsightsTab.tsx`: aplicar `id` recebido do drill-down
- No `useEffect` (`InsightsTab.tsx:142-154`), incluir `id` no destructuring de
  `pendingDrillDown.filters` e repassar para `loadCalls(0, { ...overrides, id: newId })`.
- `loadCalls` (`InsightsTab.tsx:93-124`): adicionar `id` aos `overrides` e ao `URLSearchParams`
  quando presente (mesmo padrão dos demais campos: `overrides.categoria ?? categoria`, etc.) —
  como `id` não tem um `useState` correspondente no formulário (não é um filtro digitável pelo
  usuário), ele só existe como override pontual desta chamada, não precisa de state próprio nem de
  campo visível no formulário de filtros.
- **Validar:** `npx tsc --noEmit`.

### Fase 4 — `InsightsCostsTab.tsx`: linha clicável
- Adicionar prop `onDrillDown: (filters: InsightsDrillDownFilters) => void`.
- Na `<tr>` (`InsightsCostsTab.tsx:125-135`): `style={{ cursor: 'pointer' }}` e
  `onClick={() => onDrillDown({ id: c.id })}`.
- **Validar:** `npx tsc --noEmit`.

### Fase 5 — `InsightsProcessingTab.tsx`: linha clicável condicional
- Adicionar prop `onDrillDown: (filters: InsightsDrillDownFilters) => void`.
- Ajustar o `onClick` existente (`InsightsProcessingTab.tsx:148-167`):
  ```tsx
  <tr
    key={item.id}
    style={item.status === 'done' || item.status === 'error' ? { cursor: 'pointer' } : undefined}
    onClick={() => {
      if (item.status === 'done') onDrillDown({ id: item.id });
      else if (item.status === 'error') setExpandedError(expandedError === item.id ? null : item.id);
    }}
  >
  ```
- **Validar:** `npx tsc --noEmit`.

### Fase 6 — `App.tsx`: conectar os dois novos produtores
- Passar `onDrillDown={handleDrillDown}` para `<InsightsCostsTab>` e `<InsightsProcessingTab>`
  (`App.tsx:127-131`, ao lado do `InsightsDashboardTab` que já recebe a mesma prop).
- **Validar:** `npx tsc --noEmit && npm run build`.

### Fase 7 — Release notes + validação final
- Nova entrada em `frontend/src/data/releases.ts` (próxima versão).
- **Validar (local, sem deploy):**
  ```bash
  cd insights-platform/frontend && npx tsc --noEmit && npm run build
  cd /opt/VoipIA/frontend && npx tsc --noEmit
  docker run --rm -v "$(pwd)/../backend":/build -w /build maven:3.9-eclipse-temurin-21 mvn -q compile
  ```
- Deploy (só com confirmação separada antes de mexer em produção):
  ```bash
  docker compose build backend frontend
  docker compose up -d backend frontend
  docker compose ps
  curl -s "https://app.voiphash.com.br/api/v1/insights/calls?id=<id_valido>" # confere filtro exato
  ```

## Riscos
| Risco | Prob. | Mitigação |
|---|---|---|
| Clicar numa linha de Custos/Processamento enquanto o usuário estava com outros filtros abertos na aba Chamadas — o `useEffect` de drill-down já zera todos os outros filtros (`InsightsTab.tsx:142-154`), então não há mistura de filtro antigo + `id` novo | Baixa | Comportamento herdado do mecanismo existente, sem mudança de risco |
| `id` sozinho pode colidir com os demais parâmetros de texto/frase (`text`/`phrase`) se algum dia forem combinados sem querer | Baixa | Drill-down por `id` sempre zera os demais filtros antes de aplicar (mesmo padrão do drill-down por categoria/criticidade já existente) |
| Usuário clica muito rápido em duas linhas diferentes de Custos/Processamento seguidas — precisa o `nonce` disparar de novo mesmo com filtros "parecidos" (ex: dois `id`s diferentes já disparam sozinhos, já que o objeto muda; risco só existiria se fosse o mesmo `id` duas vezes) | Baixa | Mecanismo de `nonce` já cobre esse caso (incrementa sempre) |
| Migration de banco | — | Nenhuma — é só um novo parâmetro de filtro em memória (Specification), sem mudança de schema |

## Aceite
- [ ] Clicar numa linha de Custos IA navega para Chamadas já filtrada por aquela chamada específica
- [ ] Clicar numa linha `done` de Processamento navega para Chamadas já filtrada por aquela chamada
- [ ] Clicar numa linha `error` de Processamento continua expandindo a mensagem de erro, sem navegar
- [ ] Linhas `pending`/`processing` de Processamento continuam sem `onClick`
- [ ] Drill-down do Dashboard de Tendências continua funcionando sem regressão
- [ ] `tsc --noEmit`/`npm run build` (frontend e SPA de Insights) e `mvn compile` (backend) limpos
- [ ] Release notes atualizado

## Retomada em outra sessão
Para continuar este trabalho a partir de qualquer sessão nova, peça para ler este arquivo
(`.claude/plans/insights-drilldown-custos-processamento.plan.md`) e seguir a partir da última fase
marcada como concluída em "Tarefas (fases)" — nenhuma fase foi iniciada ainda.
