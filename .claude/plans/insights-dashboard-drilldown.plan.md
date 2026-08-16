# Plan: Dashboard de Tendências (Insights) — drill-down igual ao Ranking de Atendimentos (URA)

**Origem**: pedido do usuário em 2026-07-18, via `/ecc:plan`
**Complexidade**: Medium (backend pequeno + wiring de frontend, sem migration nova)
**Escopo confirmado com o usuário**: **Completo** — os 4 grupos de indicador do dashboard ficam
clicáveis: Total de Chamadas, Criticidade, Achados por Tipo, Top Categorias.

---

## Requisitos (restatement)

Hoje, no menu **URA → Ranking de Atendimentos**, clicar numa barra/linha de qualquer card
(cliente, tipo de chamada, resolução Jira, assunto, duração média) filtra automaticamente a aba
"Chamadas" pelo valor clicado e já troca para essa aba, sem o usuário precisar reconfigurar nada
manualmente.

O usuário quer o **mesmo comportamento** no menu **Insights → Dashboard de Tendências**: clicar em
qualquer indicador do dashboard (KPI tile ou barra de gráfico) deve levar para a aba "Chamadas" do
Insights já filtrada pelo indicador clicado.

---

## Grounding — padrão a espelhar (URA)

| Categoria | Fonte | Padrão |
|---|---|---|
| Tipo de filtros drill-down | `frontend/src/components/RankingTab.tsx:60-63` | Interface `RankingDrillDownFilters` — campos opcionais, um por dimensão de filtro |
| Clique no gráfico | `RankingTab.tsx:144-152` | `<Bar onClick={entry => onBarClick(label)}>` — extrai `label` do payload da barra clicada |
| Wiring por card | `RankingTab.tsx:296-368` | Cada card mapeia `onBarClick`/`onRowClick` para um campo específico de `RankingDrillDownFilters` |
| Handler central (estado + navegação) | `ModuloURA.tsx:107-118` | `handleDrillDown` — **limpa todos os outros filtros**, aplica só o filtro clicado, abre painel de filtros (`setFiltersOpen(true)`), troca de aba (`setTab('calls')`) |
| Disparo da busca | `ModuloURA.tsx:84-89` (useEffect em `tab`) | Trocar de aba dispara `loadCalls(0)` automaticamente — não é a própria função de drill-down que busca |
| Endpoint de dados do dashboard | `StatsController.java` `GET /stats/calls/ranking` | Aceita `period`/`dateFrom`/`dateTo`/`uraId` |
| Endpoint de destino (lista filtrável) | `GET /api/v1/calls` | Aceita `clientName`, `callType`, `subjectTag`, `jiraResolution`, `dateFrom`, `dateTo`, entre outros |

**Diferença estrutural chave**: no URA, `ModuloURA.tsx` já é dono do estado de filtro da aba
Chamadas (todos os `useState` de filtro vivem no componente pai). No Insights, os filtros
(`text`, `dateFrom`, `dateTo`, `phrase`, `toneCliente`, `toneAtendente`, `categoria`) são **locais**
a `InsightsTab.tsx` — `ModuloInsights.tsx` só guarda o `tab` ativo. Por isso o mecanismo de
passagem de filtro não pode ser idêntico ao de `ModuloURA` linha a linha; a forma mais simples e
com menor diff é: `ModuloInsights` guarda um objeto `pendingDrillDown` (com contador/versão para
disparar mesmo se o mesmo filtro for clicado duas vezes seguidas) e repassa como prop pra
`InsightsTab`, que reage via `useEffect` aplicando os filtros localmente e chamando `loadCalls`.

---

## Achados do banco/backend (Fase 0 já validada — sem necessidade de migration)

- `call_insights.criticidade` (`VARCHAR(10)`, check `baixa|media|alta|urgente`) — já existe.
- `call_insight_findings.tipo` (`VARCHAR(20)`, check `melhoria|falha|treinamento|tendencia`) — já
  existe, tabela 1:N por `audio_file_id` (sem UNIQUE, uma chamada pode ter vários achados de tipos
  diferentes).
- **Nenhuma migration Flyway nova é necessária** — falta só expor filtro por `criticidade` e por
  `tipo` de achado no endpoint `GET /api/v1/insights/calls`, seguindo o mesmo padrão que já existe
  pra `categoria` (resolver pra um Set de `audioFileId` em `InsightsQueryService.resolveRestrictedIds`
  e intersectar com os demais critérios via `InsightsSpecifications.withFilters`).
- Repositories já expõem `countByCriticidade`/`countByCategoria`/`countByTipo` (usados hoje só pra
  agregação do dashboard) — precisam de métodos irmãos `findAudioFileIdsByCriticidade` /
  `findAudioFileIdsByTipo` pra uso como filtro (mesmo padrão de `findAudioFileIdsByCategoria`, que
  já existe pra categoria).

---

## Mapeamento indicador → filtro

| Indicador no dashboard | Fonte do label clicado | Campo novo em `InsightsDrillDownFilters` | Filtro passado pra `/insights/calls` |
|---|---|---|---|
| KPI "Total de chamadas analisadas" | clique no tile (sem label, é o agregado geral) | nenhum campo — só limpa filtros e troca de aba | nenhum (lista completa) |
| KPI "Criticidade urgente" | valor fixo `"urgente"` (o próprio tile já sabe sua criticidade) | `criticidade` | `criticidade=urgente` |
| KPI "Criticidade alta" | valor fixo `"alta"` | `criticidade` | `criticidade=alta` |
| Barra do gráfico "Achados por tipo" | `label` da barra clicada (`falha`/`melhoria`/`treinamento`/`tendencia`) | `findingType` | `findingType=<label>` |
| Barra do gráfico "Top categorias/assuntos" | `label` da barra clicada | `categoria` | `categoria=<label>` (já suportado hoje) |

---

## Arquivos a alterar

| Arquivo | Ação | Por quê |
|---|---|---|
| `backend/.../insights/InsightsFilter.java` | UPDATE | Adicionar campos `criticidade` e `findingType` (nomes exatos a confirmar lendo o record) |
| `backend/.../insights/InsightsController.java` | UPDATE | Novos `@RequestParam(required=false) String criticidade` e `String findingType` em `listCalls` |
| `backend/.../insights/InsightsQueryService.java` | UPDATE | `resolveRestrictedIds`: resolver `criticidade` via `insightRepository.findAudioFileIdsByCriticidade(...)` e `findingType` via `findingRepository.findAudioFileIdsByTipo(...)`, intersectando com os demais Sets já resolvidos |
| `backend/.../insights/CallInsightRepository.java` (nome a confirmar) | UPDATE | Novo método `findAudioFileIdsByCriticidade(String criticidade)` |
| `backend/.../insights/CallInsightFindingRepository.java` (nome a confirmar) | UPDATE | Novo método `findAudioFileIdsByTipo(String tipo)` |
| `frontend/src/components/InsightsDashboardTab.tsx` | UPDATE | Adicionar `onDrillDown` como prop; `onClick` nas KPI tiles de criticidade e nas duas `<Bar>`; cursor pointer só onde há `onDrillDown` |
| `frontend/src/components/InsightsTab.tsx` | UPDATE | Novo prop opcional (ex: `pendingDrillDown`); `useEffect` que aplica os filtros recebidos (`categoria`/`criticidade`/`findingType`/limpa os demais), abre o painel de filtros e chama `loadCalls(0)` |
| `frontend/src/components/ModuloInsights.tsx` | UPDATE | Novo tipo `InsightsDrillDownFilters` + estado `pendingDrillDown` (com versão/contador) + handler que limpa o filtro anterior, seta o novo e troca `tab` pra `'calls'` — espelha `ModuloURA.handleDrillDown` |
| `frontend/src/api/types.ts` (se aplicável) | UPDATE | Tipos de request de `listCalls`/`InsightsDrillDownFilters` se centralizados aí |

> Nomes exatos de arquivo/record/repository do backend (`InsightsFilter`, `CallInsightRepository`,
> `CallInsightFindingRepository`) precisam ser confirmados por leitura direta no início da Fase 1 —
> a pesquisa anterior confirmou os métodos existentes (`findAudioFileIdsByCategoria`, etc.) mas não
> capturou o nome literal do arquivo do repository de findings.

---

## Tasks

### Fase 1 — Backend: filtro por criticidade e por tipo de achado
1. Ler `InsightsFilter.java`, `InsightsQueryService.java`, os dois repositories de insights e
   `InsightsSpecifications.java` na íntegra pra confirmar assinaturas exatas antes de editar.
2. Adicionar campos `criticidade` e `findingType` ao `InsightsFilter` (record).
3. Adicionar `findAudioFileIdsByCriticidade(String)` em `CallInsightRepository` (ou nome real) e
   `findAudioFileIdsByTipo(String)` no repository de findings — mesma assinatura/padrão de
   `findAudioFileIdsByCategoria`.
4. Em `InsightsQueryService.resolveRestrictedIds`, resolver os dois novos campos (quando presentes)
   pra Sets de `audioFileId` e intersectar com os já existentes (mesmo padrão de `categoria`/`text`).
5. Em `InsightsController.listCalls`, adicionar os dois `@RequestParam` e propagar pro `InsightsFilter`.
6. **Validar**: `mvn -q compile` (ou o comando de build já usado no repo) + teste manual via `curl`
   contra `GET /api/v1/insights/calls?criticidade=urgente` e `?findingType=falha` num ambiente com
   dados reais, conferindo que a contagem retornada bate com o KPI/barra correspondente do dashboard.

### Fase 2 — Frontend: tipo de drill-down + estado central em `ModuloInsights`
1. Criar (ou local, ou em `frontend/src/api/types.ts` se for o padrão do projeto) a interface:
   ```ts
   export interface InsightsDrillDownFilters {
     categoria?: string;
     criticidade?: string;
     findingType?: string;
   }
   ```
2. Em `ModuloInsights.tsx`: estado `pendingDrillDown: { filters: InsightsDrillDownFilters; nonce: number } | null`
   (o `nonce` garante reprocessar mesmo se o usuário clicar duas vezes seguidas no mesmo indicador).
3. Handler `handleDrillDown(filters: InsightsDrillDownFilters)`: seta `pendingDrillDown` com filtros
   novos (limpando implicitamente os demais, já que é um objeto novo) e troca `tab` para `'calls'`.
4. Passar `onDrillDown={handleDrillDown}` pra `InsightsDashboardTab` e `pendingDrillDown` pra
   `InsightsTab`.
5. **Validar**: `tsc --noEmit`.

### Fase 3 — Frontend: `InsightsDashboardTab` clicável
1. KPI tile "Total de chamadas analisadas": `onClick={() => onDrillDown({})}` + cursor pointer.
2. KPI tiles "Criticidade urgente"/"Criticidade alta": `onClick={() => onDrillDown({ criticidade: 'urgente' | 'alta' })}`.
3. `<Bar>` do gráfico "Achados por tipo": `onClick` extraindo o `label` do payload (mesmo padrão de
   `RankingTab.tsx:144-152`) → `onDrillDown({ findingType: label })`.
4. `<Bar>` do gráfico "Top categorias/assuntos": mesmo padrão → `onDrillDown({ categoria: label })`.
5. Cursor `pointer` só nos elementos com handler ativo (evitar sugerir clique em algo que não filtra).
6. **Validar**: `tsc --noEmit` + inspeção visual manual (sem Chrome DevTools MCP disponível na VPS,
   conforme já registrado na entrega anterior de Insights — validar ao menos que os tipos batem e
   que o build de frontend sobe sem erro).

### Fase 4 — Frontend: `InsightsTab` aplica o filtro recebido
1. Novo `useEffect` reagindo a mudanças em `pendingDrillDown` (chave: `pendingDrillDown?.nonce`):
   - Limpar os filtros locais que não vieram no drill-down (`text`, `phrase`, `toneCliente`,
     `toneAtendente`, `dateFrom`, `dateTo` — mesma filosofia do URA: "não combinar com recorte
     anterior sem o usuário perceber").
   - Aplicar `categoria`/`criticidade`/`findingType` recebidos.
   - `setFiltersOpen(true)`.
   - Chamar `loadCalls(0)` com os novos filtros (não esperar o próximo render/debounce, se houver).
2. **Validar**: fluxo end-to-end manual — abrir Insights, clicar num indicador do Dashboard de
   Tendências, confirmar que a aba Chamadas abre já filtrada e com o painel de filtros visível,
   comparando a contagem com o valor do indicador clicado.

### Fase 5 — Revisão e fechamento
1. `code-reviewer` no diff completo (backend + frontend).
2. `security-reviewer` rápido nos dois `@RequestParam` novos (nenhuma injeção esperada — são
   `String` comparados contra `CHECK` constraint, mas confirmar que a query usa parâmetro
   bind, não concatenação).
3. Registrar entrada em `frontend/src/data/releases.ts` (obrigatório por convenção do projeto).
4. Deploy: `docker compose up -d --build backend frontend` + smoke test via `curl`/UI.
5. Atualizar memória (`asteriskia_insights_custos_processamento_feature` ou nova memória dedicada)
   e este plano com o resultado.

---

## Validação (comandos)

```bash
# Backend
cd /opt/VoipIA/backend && mvn -q -DskipTests compile

# Frontend
cd /opt/VoipIA/frontend && npx tsc --noEmit

# Smoke test dos novos filtros (após deploy)
curl -s "https://app.voiphash.com.br/api/v1/insights/calls?criticidade=urgente&size=5" -H "Authorization: Bearer <token>"
curl -s "https://app.voiphash.com.br/api/v1/insights/calls?findingType=falha&size=5" -H "Authorization: Bearer <token>"
```

---

## Riscos

| Risco | Probabilidade | Mitigação |
|---|---|---|
| Nome real dos arquivos de repository/filter divergir do assumido | Baixa | Ler os arquivos por completo no início da Fase 1 antes de editar (já planejado como passo 1) |
| `findingType` gerar contagem diferente da KPI do dashboard (achado 1:N pode inflar/duplicar chamadas na busca por tipo) | Média | Usar `DISTINCT audio_file_id` na query de resolução (mesma lógica que provavelmente já existe pra `categoria`, que também é resolvida via Set) |
| Trocar de aba não disparar `loadCalls` automaticamente (Insights pode não ter o mesmo `useEffect` de `tab` que o URA tem) | Média | Chamar `loadCalls(0)` explicitamente dentro do `useEffect` de `pendingDrillDown` em vez de depender de side-effect da troca de aba |
| Clique duplo no mesmo indicador não reabrir/refiltrar (obj igual não dispara useEffect) | Baixa | Usar `nonce`/contador no estado de drill-down, não só o objeto de filtros |

---

## Acceptance

- [ ] Clicar em qualquer um dos 4 grupos de indicador do Dashboard de Tendências troca para a aba
      Chamadas do Insights já filtrada corretamente e com o painel de filtros aberto.
- [ ] A contagem de resultados na aba Chamadas bate com o valor do indicador clicado (validação
      manual, não só visual).
- [ ] `tsc --noEmit` e `mvn compile` passam.
- [ ] `code-reviewer` e `security-reviewer` sem CRITICAL/HIGH pendente.
- [ ] Entrada em `releases.ts`.
- [ ] Deployado e testado em produção (VPS é o próprio ambiente de desenvolvimento desta sessão).
