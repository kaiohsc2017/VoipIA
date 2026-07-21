# Plan: Módulo Financeiro (centralização de Custos de IA)

**Origem**: pedido free-form (`/ecc:plan`) — 2026-07-20
**Complexidade**: Média
**Decisões do usuário**: renomear "Análise Individual" → **"Análise Sob Demanda"**; **mover** (centralizar, remover das telas atuais); RBAC = **namespace novo granular `financeiro.*`**.

## Resumo

Criar um novo módulo de topo **Financeiro** no shell Telecom (`frontend/`), com um **submenu expansível** de 3 itens que hoje não existe na Sidebar: **URA**, **Insights** e **Análise Sob Demanda**. Cada submenu abre uma página com as duas telas de custo que já existem (lista "Custos IA" + "Dashboard de Custos"). As telas de custo são **movidas** dos locais atuais (abas do Módulo URA e abas da SPA Insights) para o Financeiro. O acesso é controlado por um namespace RBAC novo e granular (`financeiro.ura` / `financeiro.insights` / `financeiro.envios`), com migration V41 preservando concessões existentes.

Nenhum endpoint de dados novo (de custo) é criado — as 3 fontes (`/api/v1/calls/costs`, `/api/v1/insights/costs`, `/api/v1/insights/uploads/costs`) já existem no mesmo backend Java e mesma origem; só muda a **permissão** que as protege e o **frontend** que as consome.

**Adições confirmadas com o usuário (2026-07-20):**
1. **Alerta de gasto em USD por frente** — cada submenu tem uma opção de configurar um limite de gasto (USD) para aquela frente; ao ser atingido, um alerta é enviado no Telegram (reusa `TelegramBotService.sendMessage`). Um scheduler diário (espelhando `AiModelPricingSyncScheduler`) compara o gasto do mês corrente ao limite e avisa uma única vez por mês por frente.
2. **Gráfico consolidado no Dashboard principal** — evolução mensal dos custos das 3 frentes (URA, Insights, Análise Sob Demanda) no mesmo gráfico (3 séries).
3. **Card de acumulado do mês no Dashboard** — valor do mês corrente somando as 3 frentes.

## Padrões a espelhar

| Categoria | Fonte | Padrão |
|---|---|---|
| Migration RBAC granular | `backend/.../db/migration/V37__insights_rbac_namespace.sql` | INSERT via `unnest(ARRAY[...])` + `ON CONFLICT ... GREATEST` copiando de resource antigo; catálogo fixo em código |
| Catálogo de recursos | `backend/.../domain/accessgroup/ResourceCatalog.java` | Listas `static final List<String>` por sistema; `all()` concatena |
| Matchers de rota | `backend/.../config/SecurityConfig.java:118-136,179-186` | `hasAnyAuthority("ROLE_ADMIN","PERM_READ_<res>")` (GET) / `PERM_WRITE_<res>`; **matcher mais específico ANTES do genérico** |
| Tab-container de módulo | `frontend/src/components/ModuloURA.tsx:166-185,499-508` | estado `tab` + fileira de botões + render condicional |
| Componente de custo (URA) | `frontend/src/components/CostsTab.tsx`, `CostsDashboardTab.tsx` | lista paginada + `BarChart` recharts; drill-down dashboard→lista |
| Componente de custo (basePath) | `insights-platform/frontend/src/components/InsightsCostsTab.tsx:37,56` | mesmo componente parametrizado por `basePath` (resolve sobre `baseURL=/api/v1`) |
| Roteamento por hash | `frontend/src/App.tsx:96-136,200-218` | `Page` union + `PAGE_RESOURCE` gate + `{page === 'x' && <C/>}` lazy |
| Sidebar / gate de nav | `frontend/src/components/Sidebar.tsx:30-55` | `NAV_ITEMS` plano + `canRead(role,perms,resource)` |
| Matriz de permissões (UI) | `frontend/src/components/AccessGroups.tsx:37,40` | array `RESOURCES` hardcoded (4º ponto de sincronia) |
| Release notes | `frontend/src/data/releases.ts` | entrada nova obrigatória (topo/fim conforme consumo) |
| Scheduler diário + Telegram | `backend/.../domain/ai/AiModelPricingSyncScheduler.java:42,49,94` | `@Scheduled(cron="${prop:default}")` → `run()` público (reusável por endpoint manual) → `telegramBotService.sendMessage(...)` por threshold |
| Envio Telegram | `backend/.../telegram/TelegramBotService.java:72` | `sendMessage(text)`; token/chat_id via `ConfigService` (`system_config`) — já configurados |
| Config chave-valor | `backend/.../domain/config/ConfigService.java:46,77,92` | `get/getInt/set` com cache TTL 60s (fallback banco→env→default) |
| Card KPI (Dashboard) | `frontend/src/components/Dashboard.tsx:270-299,579-593` | grid `kpi-card` + componente `KpiCard`; dados via `Promise.all` de GETs |
| Gráfico mês a mês | `frontend/src/components/CostsDashboardTab.tsx:53-63,128-143` | 12 meses fixos + recharts; agregação por `month` "yyyy-MM" |

## Arquivos a alterar

### Backend (Java) — só RBAC, sem endpoint novo
| Arquivo | Ação | Porquê |
|---|---|---|
| `backend/.../db/migration/V41__financeiro_rbac_namespace.sql` | CREATE | Adiciona `financeiro.ura/insights/envios`; copia concessões de `telecom.modulo1`/`insights.costs`/`insights.uploads` (padrão V37) |
| `backend/.../domain/accessgroup/ResourceCatalog.java` | UPDATE | Nova lista `FINANCEIRO` (3 chaves) + incluir em `all()` |
| `backend/.../config/SecurityConfig.java` | UPDATE | Re-protege os 3 grupos de rota de custo com a permissão `financeiro.*` (GET + escrita); adiciona matcher **específico** `/api/v1/insights/uploads/costs/**` ANTES de `/api/v1/insights/uploads/**`; matcher para `/api/v1/financeiro/cost-alerts/**` (GET=`PERM_READ_financeiro.<scope>`, escrita=`PERM_WRITE_financeiro.<scope>`) |
| `backend/.../db/migration/V42__financeiro_cost_alerts.sql` | CREATE | Tabela `financeiro_cost_alerts` (`scope` PK: 'ura'/'insights'/'envios', `threshold_usd NUMERIC`, `enabled BOOLEAN`, `last_notified_month VARCHAR(7)`, `updated_at`/`updated_by`); seed 3 linhas desabilitadas |
| `backend/.../domain/financeiro/CostAlert*.java` | CREATE | Novo pacote `domain/financeiro`: entidade `CostAlertConfig` + `CostAlertConfigRepository` + `CostAlertService` (lê limite, calcula gasto do mês corrente da frente reusando `CallCostService`/`InsightsCostService`, decide se notifica) + `CostAlertController` (`GET`/`PUT /api/v1/financeiro/cost-alerts/{scope}`) + DTO |
| `backend/.../domain/financeiro/CostAlertScheduler.java` | CREATE | Espelha `AiModelPricingSyncScheduler`: `@Scheduled(cron="${app.financeiro.cost-alert-cron:0 0 8 * * ?}")` → `run()` público; para cada frente habilitada, se gasto do mês > limite e `last_notified_month` ≠ mês atual, `telegramBotService.sendMessage(...)` e carimba o mês |

### Frontend Telecom (`frontend/src/`) — onde o módulo passa a viver
| Arquivo | Ação | Porquê |
|---|---|---|
| `frontend/src/components/Financeiro.tsx` | CREATE | Container do módulo; recebe `scope: 'ura'\|'insights'\|'envios'`; renderiza os 2 tabs (lista + dashboard) da fonte certa, com drill-down igual ao ModuloURA; **+ painel de config de alerta de gasto** (toggle habilitar + input limite USD + salvar), visível/editável só com `canWrite(financeiro.<scope>)` |
| `frontend/src/components/Dashboard.tsx` | UPDATE | **Gráfico consolidado** (`LineChart` recharts, evolução mensal, 3 séries URA/Insights/Envios via `Promise.all` dos 3 `/costs/summary`, agregado por `month`) **+ card `KpiCard`** "Custo IA acumulado (mês)" somando as 3 frentes do mês corrente. Só busca/renderiza se o usuário tiver leitura de ao menos uma frente; trata 403 por frente sem quebrar |
| `frontend/src/components/InsightsCostsTab.tsx` | CREATE (portar) | Cópia do componente da SPA Insights (aceita `basePath`) — usado para Insights (`/insights/costs`) e Envios (`/insights/uploads/costs`) |
| `frontend/src/components/InsightsCostsDashboardTab.tsx` | CREATE (portar) | idem, dashboard |
| `frontend/src/api/types.ts` | UPDATE | Adicionar tipos `InsightCostView`/`InsightMonthlyCostSummary` (já existem na SPA; portar) |
| `frontend/src/components/Sidebar.tsx` | UPDATE | Suporte a item pai com `children[]` (submenu expansível) + item "Financeiro" com 3 filhos; visível se algum filho for legível |
| `frontend/src/App.tsx` | UPDATE | 3 novas `Page` (`finUra`/`finInsights`/`finEnvios`), entradas em `PAGE_RESOURCE`, `valid[]`, render lazy `<Financeiro scope=.../>` |
| `frontend/src/components/AccessGroups.tsx` | UPDATE | 3 linhas novas no array `RESOURCES` (sistema "Financeiro") |
| `frontend/src/components/ModuloURA.tsx` | UPDATE | **Remover** abas `costs`/`costsDashboard` (botões + render + handler drill-down) — movidas p/ Financeiro |
| `frontend/src/App.css` (ou equivalente) | UPDATE | Estilo do submenu expansível (indent/caret), se necessário |
| `frontend/src/data/releases.ts` | UPDATE | Entrada de release nova |

### Frontend Insights SPA (`insights-platform/frontend/src/`) — remoção (mover)
| Arquivo | Ação | Porquê |
|---|---|---|
| `insights-platform/frontend/src/components/Sidebar.tsx` | UPDATE | Remover itens `costs`, `costsDashboard`, `uploadsCosts`, `uploadsCostsDashboard` |
| `insights-platform/frontend/src/App.tsx` | UPDATE | Remover as 4 tabs de custo do union `Tab` e do render; **manter** a aba `uploads` (portal "Meus Envios") |
| `.../InsightsCostsTab.tsx`, `.../InsightsCostsDashboardTab.tsx` | DELETE (após confirmar sem outros usos) | Deixam de ser usados na SPA; a cópia canônica passa a ser a do Telecom |

### Documentação
| Arquivo | Ação | Porquê |
|---|---|---|
| `frontend/src/components/docs/sections/*` + `docs/toc.ts` + `Documentacao.tsx` | UPDATE | Seção/entrada nova "Financeiro"; ajustar textos de URA/Insights que citam abas de custo movidas |

## Tarefas (por fase)

### Fase 0 — Confirmação de fronteiras (pré-código)
- Confirmar que `/api/v1/calls/costs` só é consumido pelas abas que serão movidas (grep) e que `stats/calls/ranking` continua em `telecom.modulo1`.
- Confirmar que nenhum outro consumidor da SPA usa `InsightsCosts*Tab` além das 4 tabs.
- **Validar**: `grep -rn "costs" frontend/src insights-platform/frontend/src`.

### Fase 1 — Backend RBAC
- `ResourceCatalog.FINANCEIRO` + `all()`.
- `V41` (espelhar V37): seed `financeiro.ura` ← `telecom.modulo1`, `financeiro.insights` ← `insights.costs`, `financeiro.envios` ← `insights.uploads` (GREATEST, sem apagar as origens — elas seguem cobrindo o resto de URA/Insights/Uploads).
- `SecurityConfig`: trocar autoridade dos 3 grupos de custo para `financeiro.*` (mantendo `ROLE_ADMIN`); inserir matcher específico de `/insights/uploads/costs/**` antes do genérico.
- **Validar**: `mvn compile` (via docker maven) + `mvn test` do pacote afetado.

### Fase 1b — Backend alerta de gasto USD
- `V42` (tabela `financeiro_cost_alerts`, seed 3 frentes desabilitadas).
- Pacote `domain/financeiro`: entidade/repo/service/controller (GET/PUT config) + `CostAlertScheduler` (espelha `AiModelPricingSyncScheduler`, cron 08:00, dedup por mês).
- `CostAlertService` reusa `CallCostService`/`InsightsCostService` para o gasto do mês corrente (dateFrom=1º dia do mês, dateTo=hoje, lê `totalCostUsd`).
- **Validar**: `mvn compile` + teste unitário do `CostAlertService` (limite atingido / não atingido / já notificado no mês).

### Fase 2 — Frontend Telecom (novo módulo)
- Portar `InsightsCosts*Tab` + tipos; criar `Financeiro.tsx` (scope-driven) com o painel de config de alerta por frente.
- Sidebar: submenu expansível + item Financeiro; App: rotas/gate/render.
- **Validar**: `npx tsc --noEmit -p frontend/tsconfig.app.json`.

### Fase 2b — Dashboard consolidado
- `Dashboard.tsx`: gráfico de 3 séries (agregação por mês das 3 fontes) + card `KpiCard` de acumulado do mês; gate por leitura de ao menos uma frente + tolerância a 403 por frente.
- **Validar**: `npx tsc --noEmit -p frontend/tsconfig.app.json`.

### Fase 3 — Remoção nas origens (mover)
- Tirar as abas de custo do `ModuloURA.tsx` e da SPA Insights; deletar componentes órfãos.
- **Validar**: `tsc --noEmit` nas duas SPAs; `npm run build` da SPA Insights.

### Fase 4 — AccessGroups + Documentação + Release notes
- 3 linhas em `AccessGroups.tsx`; seção de docs; entrada em `releases.ts`.

### Fase 5 — Revisão + Deploy + validação
- `ecc:java-reviewer` + `ecc:react-reviewer`/`typescript-reviewer` em paralelo; corrigir CRITICAL/HIGH.
- Deploy: `docker compose up -d --build backend frontend` (migration V41 aplica no boot do backend).
- Validar com JWT forjado inline: GETs de custo com perm `financeiro.*` retornam 200; sem a perm, 403; abas antigas sumiram.

## Validação (comandos)
```bash
ls backend/src/main/resources/db/migration | sort -V | tail -2   # V41 (RBAC) + V42 (cost_alerts)
docker run --rm -v "$(pwd)/backend":/build -v maven-repo-cache:/root/.m2 -w /build \
  maven:3.9-eclipse-temurin-21 mvn -q compile
cd frontend && npx tsc --noEmit -p tsconfig.app.json
cd insights-platform/frontend && npx tsc --noEmit && npm run build
# pós-deploy (JWT inline, não persistido — ver memória asteriskia_no_persist_forged_tokens)
curl -s -H "Authorization: Bearer $JWT_FIN" https://app.voiphash.com.br/api/v1/calls/costs | head
```

## Riscos
| Risco | Prob. | Mitigação |
|---|---|---|
| Ordem de matchers no SecurityConfig (genérico `/uploads/**` engolir `/uploads/costs/**`) | Média | Matcher específico declarado ANTES; validar com curl pós-deploy nos dois caminhos |
| Migration V41 irreversível em prod | Baixa | Só INSERT/ON CONFLICT aditivo, sem DELETE das origens; revisar SQL antes (regra inegociável #6) |
| Usuário que só tinha `telecom.modulo1` perder acesso ao custo de URA | Média | V41 copia a concessão para `financeiro.ura`; validar que grupos existentes seguem enxergando |
| Submenu expansível é padrão novo na Sidebar (nenhum precedente) | Média | Manter simples (accordion controlado por estado local), sem lib nova; espelhar visual das seções atuais |
| `basePath` divergente ao portar p/ Telecom | Baixa | Ambos clients usam `baseURL=/api/v1`; `basePath='/insights/costs'` e `'/insights/uploads/costs'` resolvem igual |
| Alerta Telegram disparar em loop (todo run do scheduler) | Média | `last_notified_month` na tabela: só notifica se ≠ mês corrente; reseta naturalmente ao virar o mês |
| Telegram não configurado (`TELEGRAM_*` vazio) | Baixa | `TelegramBotService` já loga warn e não envia; scheduler não quebra |
| Dashboard chamar `/costs/summary` para quem não tem perm de custo → 403 | Média | Só dispara os fetches das frentes legíveis (`canRead(financeiro.<scope>)`); `catch` por frente; card/gráfico some se nenhuma frente legível |
| Gasto do mês recalculado a cada run (custo de query) | Baixa | 1×/dia, agregação já existente em memória nos services; sem tabela nova de histórico |

## Aceite
- [ ] Módulo "Financeiro" com submenu URA / Insights / Análise Sob Demanda funcional
- [ ] As 2 telas de custo por submenu abrindo dados corretos das 3 fontes
- [ ] Abas de custo removidas do Módulo URA e da SPA Insights (portal "Meus Envios" preservado)
- [ ] `financeiro.*` no catálogo, SecurityConfig, Sidebar, AccessGroups (4 pontos de sincronia) + V41
- [ ] Grupos existentes preservam acesso (V41 copiou concessões)
- [ ] Config de alerta de gasto USD em cada submenu (habilitar + limite), persistida
- [ ] Scheduler dispara Telegram ao ultrapassar o limite do mês, uma única vez por frente/mês
- [ ] Dashboard com gráfico de 3 séries (URA/Insights/Envios) + card de acumulado do mês somado
- [ ] Revisões sem CRITICAL/HIGH; deploy validado por curl; release notes registrada
