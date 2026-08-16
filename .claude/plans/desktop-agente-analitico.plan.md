# Plan: Desktop do Agente — espaço de trabalho analítico

**Status: ✅ APROVADO pelo usuário em 2026-08-15 — pode iniciar a implementação (Fase 1) sem
perguntar de novo.** Mockup validado: https://claude.ai/code/artifact/6e414ca0-5f7f-480c-9953-41fa4628ceec

**Origem**: pedido do usuário + 3 screenshots do modelo desejado em `/opt/VoipIA/area/`
(`Area1.png`, `Area2.png`, `area3.png` — interface Genesys Cloud).
**Complexidade**: Média-Grande (backend incremental + reestruturação do frontend)
**Migration nova**: nenhuma nas Fases 1 e 2 (só na Fase 3, opcional)

## Decisões confirmadas (D1-D7 — ver seção completa mais abaixo)

Usuário aprovou o plano e o mockup sem pedir mudanças — todas as recomendações valem como
decisão final:
- **D1** sub-abas (não scroll longo)
- **D2** coluna de operação fixa à esquerda
- **D3** ranking mostra só a própria posição + topo anonimizado por padrão
- **D4** histórico navegado por dia, teto de 90 dias
- **D5** não criar enum de estado novo — reusar `cc_pause_reasons`
- **D6** avaliações de qualidade visíveis ao agente, somente leitura
- **D7** treinamento/plano de desenvolvimento **fora do MVP** (Fase 3 opcional, não iniciar sem pedido explícito)

**Próximo passo ao retomar esta sessão**: iniciar Fase 1 (backend) — ver tarefas 1-6 na seção
"Fase 1" abaixo. Nenhuma pergunta pendente.

---

## Resumo

O `DesktopAgenteTab.tsx` atual (528 linhas, um único componente) já tem softphone, estado do
agente, tabulação, screen pop e copiloto de IA — mas os dados analíticos são apenas 4 números do
dia corrente (`/desktop/me/resumo`) e uma lista de chamadas de hoje. O modelo dos screenshots é um
**espaço de trabalho do agente**: presença sempre visível, KPIs pessoais em destaque, histórico de
interações navegável por data com colunas ricas, aderência à escala, qualidade/avaliações e
ranking. Quase tudo isso **já existe no backend** (Fases 8, 9b, 9c.7, 22, 26, 27) mas hoje só é
consumido pelas telas de supervisor/relatório — o agente não enxerga a própria produtividade.

A tese do plano: **não construir motor novo — abrir para o agente, de forma segura e escopada ao
próprio usuário, o que o sistema já calcula.**

---

## O que os screenshots pedem × o que já existe

| Elemento do modelo | Fonte já existente | Situação |
|---|---|---|
| Seletor de status (Disponível/Intervalo/Refeição/Reunião/Treinamento/Fora do escritório) | `CcPauseReason` + `AgentState` | ✅ existe — motivos de pausa são cadastráveis, cobrem todos os rótulos do modelo |
| Discador numérico + chamada em curso | `useSipPhone` / bridge do shell (Fase 13) | ✅ existe — só muda o layout |
| Tabela de Interações por dia (Remoto, Data, Duração, Direção, Fila, Finalização) | `CcInteraction` + `cc_dispositions` | 🟡 existe parcialmente — `/desktop/me/historico` é só "hoje" e não devolve tabulação |
| "Aderência" (widget) | `CallCenterAgentAdherenceService` + `cc_agent_schedules` (9c.7) | 🟡 existe, mas só por `agentId` (rota de supervisor) |
| "Programação de hoje" | `CcAgentSchedule` | 🟡 existe, mas sem leitura pelo próprio agente |
| "Resumo da avaliação" / "Avaliações para revisão" | `CallEvaluation`/`CallEvaluationItem` (Fase 8) | 🟡 existe, agregado por `AgentReportAggregationService` |
| "Tabelas de classificação" (ranking) | `CallCenterGamificationService` (Fase 27) | 🟡 existe, rota de supervisor |
| Timeline de estados / produtividade | `CallCenterProductivityService` (Fase 27) | 🟡 existe, rota de supervisor (`/{agentId}`) |
| Série histórica (7/30 dias) de volume, TMA, ocupação, NPS | `cc_agg_agent_daily` (Fase 9b) | ✅ dados prontos, sem endpoint pessoal |
| "Agendamentos de treinamento" | — | ❌ não existe (Fase 3, opcional) |
| "Plano de desenvolvimento" | — | ❌ não existe (Fase 3, opcional) |

**Conclusão:** ~85% do pedido é exposição segura de dado já calculado. Nenhuma migration nas
Fases 1-2.

---

## Regra de segurança que governa o plano inteiro

`CallCenterDesktopController` documenta (e o código cumpre): **nenhum endpoint de `/desktop/me/**`
aceita identificador de agente vindo do chamador** — todos resolvem via
`CallCenterAgentStateService.currentAgent()`. Os serviços que serão reusados (`Adherence`,
`Productivity`, `Gamification`) hoje recebem `agentId` por path porque são rotas de supervisor.

Regra desta entrega, sem exceção:
- os métodos de serviço reusados continuam recebendo `agentId` (são de supervisor);
- **quem passa esse `agentId` é sempre o `CallCenterDesktopService`, a partir de `currentAgent()`** —
  nunca o controller, nunca o frontend;
- ranking é a única exceção parcial e vira decisão de produto (ver D3).

Sem isso, o desktop pessoal vira vazamento de produtividade alheia — exatamente o que o javadoc
atual previne.

---

## Decisões a validar (o mockup existe para isto)

| # | Decisão | Recomendação |
|---|---|---|
| **D1** | Layout: aba única com scroll longo × workspace com sub-abas | **Sub-abas** — o agente opera com uma coisa por vez; scroll longo em atendimento é hostil |
| **D2** | Coluna de operação (softphone/atendimento/copiloto) fixa à esquerda × topo | **Fixa à esquerda**, sempre visível — igual ao painel lateral de Chamadas do modelo |
| **D3** | Ranking: o agente vê nome dos colegas × só a própria posição + faixa | **Só a própria posição** + "top 3" anonimizado por padrão, com liga/desliga em `cc_settings` |
| **D4** | Janela do histórico de interações | Navegação por **dia** (igual ao modelo) + atalhos 7d/30d, teto de 90 dias |
| **D5** | Estados extras do modelo (Reunião, Treinamento, Refeição…) | **Não criar enum novo** — são `cc_pause_reasons` cadastrados; só mudar a UI para mostrá-los como opções de primeira classe |
| **D6** | Avaliações de qualidade visíveis ao agente | Sim, **somente leitura**, e só avaliações já liberadas (mesma disciplina D21: nunca dispara reprocessamento) |
| **D7** | Treinamento / plano de desenvolvimento | **Fora do MVP** — exige schema novo; Fase 3 opcional |

---

## Padrões a espelhar

| Categoria | Fonte | Padrão |
|---|---|---|
| Escopo pessoal | `CallCenterDesktopController.java:9-18` | javadoc explícito + `currentAgent()` em todo método |
| Erro 404 (agente sem vínculo) | `CallCenterAgentStateService.currentAgent()` | `ResponseStatusException(404)`, nunca 500 |
| Recorte de período de estado | `CallCenterDesktopService.secondsInEachState():169` | mesma lógica de recorte de `CallCenterAgentAggregationService` (9b) |
| RBAC | matcher genérico `callcenter.desktop` | rotas novas sob `/callcenter/desktop/me/**` herdam — **sem resource novo** |
| Polling / cleanup | `DesktopAgenteTab.tsx:112-147` | dependência primitiva estável, flag `cancelled`, `clearInterval` |
| Somente leitura (D21) | `CallCenterDesktopService` javadoc | histórico nunca enfileira/reprocessa; teste `verify(..., never()).save(any())` |

---

## Fase 1 — Backend: abrir os dados já calculados (sem migration)

Tudo em `backend/.../domain/callcenter/desktop/`.

| Arquivo | Ação | Por quê |
|---|---|---|
| `CallCenterDesktopService.java` | UPDATE | novos métodos delegando a serviços existentes, sempre com `currentAgent().getId()` |
| `CallCenterDesktopController.java` | UPDATE | 5 endpoints GET novos sob `/me` |
| `DesktopCallHistoryItem.java` | UPDATE | + `dispositionLabel`, `waitSeconds`, `holdSeconds`, `contactName` |
| `DesktopSummaryView.java` | UPDATE | + ocupação, NPS médio, aderência do dia, comparação com média 7d |
| `DesktopTrendPoint.java` | CREATE | ponto diário de série histórica (`cc_agg_agent_daily`) |
| `DesktopScheduleView.java` | CREATE | turno de hoje + aderência do dia |
| `DesktopQualityView.java` | CREATE | resumo de avaliações + pontos fortes/melhoria |
| `DesktopRankingView.java` | CREATE | posição própria + faixa (D3) |
| `CallCenterDesktopServiceTest.java` | UPDATE | cobrir escopo pessoal, janela, D21 |

Endpoints novos (todos GET, todos sem parâmetro de agente):

```
GET /api/v1/callcenter/desktop/me/tendencia?dias=7|30      → List<DesktopTrendPoint>
GET /api/v1/callcenter/desktop/me/escala?data=YYYY-MM-DD    → DesktopScheduleView
GET /api/v1/callcenter/desktop/me/produtividade?de=&ate=    → AgentProductivityReport (reuso Fase 27)
GET /api/v1/callcenter/desktop/me/qualidade?de=&ate=        → DesktopQualityView
GET /api/v1/callcenter/desktop/me/ranking?de=&ate=          → DesktopRankingView
```

E `GET /me/historico` ganha `?de=&ate=` (default = hoje, teto 90 dias, 400 claro se exceder).

**Tarefas**

1. **Histórico por período + tabulação.** Estender `historico()` com janela validada; incluir
   `dispositionLabel` (join já disponível em `CcInteraction`) e tempo de fila.
   *Validar*: `mvn test -Dtest=CallCenterDesktopServiceTest`.
2. **Resumo enriquecido.** Somar ocupação (`secondsInEachState`), NPS médio do dia e delta vs.
   média dos 7 dias anteriores lida de `cc_agg_agent_daily` — nunca recalculando o agregado.
3. **Série histórica.** Ler `CcAggAgentDailyRepository` por `agentId` + intervalo. Dia sem
   registro = ponto ausente, **não zero** (mesma disciplina de `adherencePct` nulo em
   `AgentAdherenceRow`).
4. **Escala e aderência.** Delegar a `CallCenterAgentAdherenceService` passando o id resolvido
   internamente. Sem turno cadastrado → `adherencePct = null` + rótulo "sem escala cadastrada".
5. **Produtividade e qualidade.** Delegar a `CallCenterProductivityService`; a narrativa de
   IA já vem pronta do `AgentReportAggregationService` (Fase 8) — **nenhuma chamada de IA nova,
   nenhuma frente nova no Financeiro**.
6. **Ranking (D3).** `CallCenterGamificationService` já calcula tudo; o service do desktop
   filtra para a própria linha + agregados anônimos antes de devolver.

**Risco de segurança a revisar (`ecc:security-reviewer` obrigatório):** os 5 endpoints novos
delegam a serviços de supervisor. Qualquer um que aceite `agentId` por query/path é achado
CRITICAL — mesma classe de IDOR já corrigida nas Fases 14 e 16.

---

## Fase 2 — Frontend: workspace do agente

`callcenter-platform/frontend/src/components/`. O componente atual (528 linhas) já passa do que
o próprio projeto define como saudável; a reestruturação é oportuna, não gratuita.

| Arquivo | Ação |
|---|---|
| `DesktopAgenteTab.tsx` | UPDATE — vira o shell: presença + coluna de operação + sub-abas |
| `desktop/PresenceBar.tsx` | CREATE — avatar, nome, ramal, status, timer no estado |
| `desktop/OperationColumn.tsx` | CREATE — softphone, atendimento em curso, identidade, copiloto (movidos como estão) |
| `desktop/KpiStrip.tsx` | CREATE — faixa de KPIs com delta vs. média 7d |
| `desktop/InteractionsTable.tsx` | CREATE — tabela do modelo (area3) com navegação por dia |
| `desktop/ProductivityPanel.tsx` | CREATE — timeline de estados + pausas + série histórica |
| `desktop/SchedulePanel.tsx` | CREATE — programação de hoje + aderência |
| `desktop/QualityPanel.tsx` | CREATE — resumo de avaliações, pontos fortes/melhoria |
| `desktop/RankingPanel.tsx` | CREATE — posição própria |
| `api/types.ts` | UPDATE — tipos novos |

**Regras**
- Nenhuma mudança na lógica de softphone/bridge (`isEmbedded`, `sendCallAction`) — só recorte
  visual. Continua valendo D10-A: **um único UA SIP por sessão de navegador**.
- Polling: só a coluna de operação segue em 5s. Painéis analíticos carregam sob demanda ao abrir
  a sub-aba, com cleanup (padrão já usado em `metricsTab`).
- Gráficos: `recharts` **não** é dependência da SPA do Call Center (decisão registrada em
  `InsightsDashboardTab.tsx`/`ReportsQueueTab.tsx`). Séries usam sparkline em SVG inline — sem
  dependência nova.

---

## Fase 3 — Opcional, fora do MVP

Só se o usuário quiser paridade total com o modelo Genesys:
- `cc_agent_trainings` (agendamentos de treinamento) — migration **V89**
- `cc_agent_development_plans` (plano de desenvolvimento, metas por item de ficha)

Nenhum dos dois tem fonte de dado hoje; ambos são cadastro novo + tela de supervisor + leitura no
desktop. Recomendo decidir depois de validar as Fases 1-2 em uso real.

---

## Validação

```bash
# Backend
docker run --rm -v /opt/VoipIA/backend:/app -v ~/.m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -o test

# Frontend
cd /opt/VoipIA/callcenter-platform/frontend && npx tsc --noEmit && npm run build

# Produção (após deploy)
docker compose up -d --build backend frontend
# RBAC: 403 sem token nos 5 endpoints novos; 404 (não 500) para usuário sem vínculo de agente
# IDOR: confirmar que nenhum endpoint aceita agentId (curl com ?agentId=  deve ser ignorado)
```

---

## Riscos

| Risco | Probab. | Mitigação |
|---|---|---|
| IDOR ao reusar serviços de supervisor | **Alta** | `agentId` só de `currentAgent()`; `ecc:security-reviewer` antes do commit; teste que prova o 403/404 |
| Custo de query no histórico de 90 dias sem paginação em banco | Média | teto de janela + paginação; mesmo gap já aceito em outras telas, aqui evitado desde o início |
| Agente sem vínculo (`cc_agents` vazio nesta VPS) quebra a tela | **Alta** | já resolvido por convenção (404 + estado "Offline"); manter em cada painel novo |
| Sem escala/avaliação/agregado cadastrados, painéis ficam vazios | Alta | estado vazio explicativo, nunca "0" (que mente) |
| Regressão no softphone durante o recorte visual | Média | não tocar em `useSipPhone`/bridge; validar com `enabled=!isEmbedded` preservado |
| Ranking expor produtividade de colegas | Média | D3 — decisão de produto antes de codar |

---

## Aceitação

- [ ] Nenhum endpoint de `/desktop/me/**` aceita identificador de agente do chamador
- [ ] Nenhuma migration nas Fases 1-2; nenhuma chamada de IA nova
- [ ] Suíte do backend verde, sem regressão (baseline atual: 965/966, flake conhecido de `ffmpeg`)
- [ ] `tsc --noEmit` e `npm run build` limpos na SPA do Call Center
- [ ] Estados vazios explicativos em todos os painéis novos
- [ ] Release notes registrada em `frontend/src/data/releases.ts`
