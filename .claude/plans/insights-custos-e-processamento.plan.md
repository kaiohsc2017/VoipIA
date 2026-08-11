# Plan: Insights — abas Custos IA / Dashboard de Custos / Processamento + Grupos de Acesso + Documentação

**Origem**: pedido do usuário em 2026-07-17, via `/ecc:plan` (revisado 2x — 2ª iteração adicionou
Grupos de Acesso e Documentação ao escopo)
**Complexidade**: Large (~4-6 dias equivalente — mais mudança de arquitetura do que UI)
**Status geral**: ✅ CONCLUÍDO e deployado em produção (2026-07-18) — ver seção "Fechamento" no final

---

## Achados da revisão (2026-07-17, 2ª iteração)

1. **LACUNA REAL a corrigir**: `telecom.insights` foi adicionado ao `ResourceCatalog.java` (backend)
   e ao `Sidebar.tsx` (nav) na entrega base da tela Insights, mas **NÃO foi adicionado ao array
   `RESOURCES` de `frontend/src/components/AccessGroups.tsx`** — que é hardcoded e precisa ser mantido
   em sincronia manual (o próprio `CLAUDE.md` documenta essa obrigação). Consequência: a tela Insights
   não aparece como linha na matriz de permissões dos Grupos de Acesso, então um admin não consegue
   conceder/negar Insights a um grupo customizado pela UI (só ADMIN, que enxerga tudo por padrão, e os
   grupos seed vê — mas grupo customizado fica sem o toggle). É um bug de visibilidade de permissão
   introduzido na entrega anterior; esta entrega corrige junto.
2. **Granularidade de permissão das novas abas**: pela metodologia já estabelecida, sub-abas
   compartilham o `resource_key` da tela pai — as abas Custos IA/Dashboard de Custos de **URA** reusam
   `telecom.modulo1` (não têm key própria; `SecurityConfig` protege `/api/v1/calls/costs/**` com
   `telecom.modulo1`). **Decisão adotada (consistente com o padrão)**: as 3 abas novas de Insights
   (Custos IA, Dashboard de Custos, Processamento) ficam sob o mesmo `telecom.insights` — sem keys
   novas no catálogo. Ou seja, "incluir novos menus em Grupos de Acesso" se resolve adicionando **1
   linha** (`telecom.insights`, hoje ausente) ao `AccessGroups.tsx`, não uma por aba. ⚠️ Se o usuário
   quiser granularidade fina (ex: esconder só "Custos IA" de um grupo mas mostrar "Chamadas"), isso
   exigiria keys novas (`telecom.insights.custos` etc.) nos 4 pontos de sincronia (ResourceCatalog,
   SecurityConfig, AccessGroups.tsx, e a checagem de aba no componente) — fora do padrão atual,
   confirmar antes.

> Este documento é a fonte de verdade da tarefa. Se a sessão cair ou os créditos acabarem, retome
> por aqui + pela memória `asteriskia_insights_custos_processamento_feature`. Contexto anterior:
> a feature Insights (transcrição/análise de IA das gravações Verint) já está deployada e validada
> em produção — ver `.claude/plans/insights-transcricao-chamadas.plan.md` e memória
> `asteriskia_insights_feature`. Este plano é uma ENTREGA NOVA em cima daquela base.

---

## Requisitos (restatement)

1. Na tela Insights, criar abas "Custos IA" e "Dashboard de Custos" **no mesmo padrão** das abas
   homônimas de URA (`CostsTab.tsx`/`CostsDashboardTab.tsx`) — usar os tokens já capturados
   (`sttTokensIn/Out`, `llmTokensIn/Out` em `call_audio_files`) e a tabela `ai_model_pricing` já
   existente pra estimar custo.
2. Novo submenu "Processamento": tabela com **nome do arquivo, data de início, data fim, posição na
   fila e status** de cada `.wav`/`.xml` descoberto em `/opt/audio` — com botão de filtro
   personalizável (permitir filtrar se um arquivo já foi processado e quando).
3. Replicar a mesma metodologia de desenvolvimento usada até agora: grounding no código existente,
   mirror de padrões, validação real em cada fase (compile/tsc/curl), deploy real com dados reais,
   registro em plano + memória a cada fase concluída.

---

## Achado crítico da Fase 0 (bloqueia design da Fase 3, não bloqueia o plano)

**A arquitetura atual não tem estado de processamento persistido antes da conclusão.** Hoje:
- `InsightsIngestionService.ingest()` (chamado por `POST /api/v1/internal/insights`) é o **único**
  ponto que grava uma linha em `call_audio_files` — e ele só é chamado no **final**, com sucesso,
  setando `status='done'` direto.
- Se o processamento falhar em qualquer etapa (parse XML, decode de áudio, chamada Gemini, POST ao
  backend), o Python só loga o erro e segue pro próximo par — **nada é persistido**, a chamada nunca
  aparece no banco. O watcher tenta de novo no próximo ciclo de poll (retry implícito, invisível).
- `ingested_at`/`processed_at`/`status`/`error_msg` já existem na tabela desde a V35 (colunas e
  CHECK constraint já preparados pra `pending`/`processing`/`done`/`error`), mas **nunca são usados
  com esses valores intermediários** — `ingested_at` acaba tendo o mesmo instante de `processed_at`,
  porque a linha só é criada no fim.

**Por isso, pra atender "data de início", "data fim", "status" e "posição na fila" de forma real
(não fictícia), é necessário instrumentar o pipeline pra registrar estado em 3 pontos**, não só no
final — isso é mudança de arquitetura genuína no serviço Python e no backend, não só uma tela nova.

---

## Arquitetura da entrega

### Banco — migration V36
```sql
ALTER TABLE call_audio_files ADD COLUMN started_at TIMESTAMP;
COMMENT ON COLUMN call_audio_files.started_at IS 'Quando o processamento desta chamada começou de fato (picked up da fila) — distinto de ingested_at (quando foi descoberta) e processed_at (quando terminou, com sucesso ou erro)';
```
`status`/`error_msg`/`ingested_at`/`processed_at` já existem — nenhuma outra mudança de schema.
"Posição na fila" **não é coluna** — computada em tempo de consulta (COUNT de linhas
`status='pending'` com `ingested_at` menor que a da linha, ordem FIFO de descoberta).

### Backend Java (`domain/insights/`)

**Novos endpoints internos** (`InsightsInternalController`, protegidos pelo `InternalKeyFilter` já
existente, mesmo padrão de `UraRoutingController`):
- `POST /api/v1/internal/insights/{callRef}/pending` — upsert por `callRef`: cria a linha com
  `status='pending'`, `wavPath`/`xmlPath` (ingestedAt vem do `DEFAULT CURRENT_TIMESTAMP` do banco,
  já que a coluna é `insertable=false` — só é setado na primeira inserção real).
- `POST /api/v1/internal/insights/{callRef}/processing` — `status='processing'`, `startedAt=now()`.
- `POST /api/v1/internal/insights/{callRef}/error` — `status='error'`, `errorMsg`, `processedAt=now()`.
- `GET /api/v1/internal/insights/known-refs` **muda de formato**: em vez de `{"callRefs": [...]}`
  (lista de strings), passa a retornar `{"calls": [{"callRef":..., "status":...}]}` — o Python
  precisa saber o status atual de cada `call_ref` já registrado pra decidir se pula (status=`done`)
  ou reprocessa (`pending`/`processing`/`error` — mesmo comportamento de retry de hoje, agora
  visível). Nenhum outro consumidor usa esse endpoint (é 100% interno), seguro de mudar o contrato.
- `POST /api/v1/internal/insights` (já existe) — sem mudança de contrato, continua marcando `done`.

**Novos endpoints públicos** (`InsightsController`, RBAC já coberto por `telecom.insights` — os
matchers `GET /api/v1/insights/**` já existem no `SecurityConfig`, cobrem os paths novos
automaticamente por serem sub-rotas):
- `GET /api/v1/insights/costs` — paginado, filtros dateFrom/dateTo/agentName — mirror de
  `CallCostController`/`CallRecordController.listCosts`.
- `GET /api/v1/insights/costs/summary` — agregado mensal — mirror de `costsSummary`.
- `GET /api/v1/insights/processing` — paginado, filtros status/dateFrom/dateTo/fileName — nova
  tabela de status de processamento com posição na fila calculada.

**Novos arquivos** (mirror exato dos existentes em `domain/call/`):
| Novo (`domain/insights/`) | Mirror de (`domain/call/`) |
|---|---|
| `InsightCostView.java` (record) | `CallCostView.java` |
| `InsightMonthlyCostSummary.java` (record, sem campo TTS) | `MonthlyCostSummary.java` |
| `InsightsCostService.java` (reusa `AiModelPricingRepository` direto, sem duplicar) | `CallCostService.java` |
| `InsightProcessingItem.java` (record: callRef, fileName, status, ingestedAt, startedAt, processedAt, errorMsg, queuePosition) | — (não existe equivalente em call/, é novo) |
| `InsightsProcessingFilter.java` (record: status, dateFrom, dateTo, fileName) | `CallRecordFilter.java` |

**`InsightsIngestionService`**: adicionar `registerPending(callRef, wavPath, xmlPath)`,
`markProcessing(callRef)`, `markError(callRef, errorMsg)` — todos upsert por `callRef` (mesmo padrão
do `ingest()` existente, `findByCallRef().orElseGet(builder)`).

**`InsightsQueryService`**: adicionar `findProcessing(filter, pageable)` — query nativa ou
Specification pra computar `queuePosition` (subquery `COUNT` sobre a própria tabela, só relevante
pra linhas `status='pending'`; `null` pras demais).

### Serviço Python (`insights/`)

**`backend_client.py`**: `register_pending()`, `mark_processing()`, `mark_error()`; `get_known_call_refs()`
passa a retornar `dict[str, str]` (call_ref → status) em vez de `set[str]`.

**`main.py`** (`_poll_once`/`process_pair`):
- Após `discover_pairs()`, cruza com o dict de status: pula só os `status='done'`; todo o resto
  (novo, `pending`, `processing`, `error`) entra na lista de processamento — **para um par
  nunca visto antes**, chama `register_pending()` ANTES de entrar na fila de concorrência (pra
  aparecer na tela de Processamento mesmo antes de começar a rodar).
- No início de `process_pair()`: chama `mark_processing()`.
- Em cada bloco `except` que hoje só loga e retorna (parse XML, decode áudio, falha de IA, falha de
  POST): chama `mark_error(call_ref, mensagem)` em vez de só logar — assim o erro fica visível na
  tela de Processamento com a mensagem real, em vez de só no log do container.

### Frontend

**`api/types.ts`**: `InsightCostView`, `InsightMonthlyCostSummary`, `InsightProcessingItem`.

**`InsightsCostsTab.tsx`** (mirror exato de `CostsTab.tsx`, sem filtro de URA — Insights não tem
URA — trocar por filtro de atendente/agentName).

**`InsightsCostsDashboardTab.tsx`** (mirror exato de `CostsDashboardTab.tsx`, gráfico com só 2 séries
empilhadas — STT e LLM — sem TTS, já que Insights não faz síntese de voz).

**`InsightsProcessingTab.tsx`** (nova, sem mirror direto — mais próxima de `InsightsTab.tsx` em
estrutura de busca/tabela): colunas Nome do arquivo, Data início (`ingestedAt`), Data fim
(`processedAt`), Posição na fila (só preenchida pra `pending`), Status (badge colorido:
pendente/processando/concluído/erro). Filtro colapsável: status (select), data de/até, nome do
arquivo (texto). Ao clicar numa linha com `status='error'`, mostrar a mensagem de erro completa
(tooltip ou expandir).

**`ModuloInsights.tsx`**: de 2 abas (Chamadas, Dashboard de Tendências) pra **5 abas**: Chamadas,
Dashboard de Tendências, Custos IA, Dashboard de Custos, Processamento — mesmo padrão de botões de
`ModuloURA.tsx` (6 abas lá).

### Grupos de Acesso (`AccessGroups.tsx`)
Adicionar a linha `telecom.insights` ao array `RESOURCES` (hoje ausente — ver Achados da revisão).
Sub-abas não ganham key própria (decisão de granularidade acima).

### Documentação (`docs/`)
Nova seção `TelecomInsights.tsx` + item no `toc.ts` + render em `Documentacao.tsx` — documenta o
módulo e as 5 abas. Revisar `TelecomRBAC.tsx`/`TelecomModulos.tsx` pra não deixarem listas de menus
desatualizadas sem Insights.

---

## Fases de implementação

### Fase 0 — Discovery — Status: ✅ CONCLUÍDA (2026-07-17)
Achados acima (arquitetura de status não instrumentada) já mapeados; padrões de Custos IA
(`CallCostService`/`CallCostView`/`MonthlyCostSummary`/`CostsTab.tsx`) já lidos e confirmados
reaproveitáveis quase 1:1.

### Fase 1 — Banco (migration V36) — Status: PENDENTE
- `backend/src/main/resources/db/migration/V36__call_audio_files_started_at.sql` — `ALTER TABLE`
  simples, um `COMMENT ON COLUMN`.
- **Validar**: mesma técnica das fases anteriores — aplicar num `--single-transaction` no Postgres
  real com `ROLLBACK` explícito, conferir que nada persiste, e só então deixar o Flyway aplicar de
  verdade no próximo build do backend.

### Fase 2 — Backend Java: custos de IA (Custos/Dashboard) — Status: PENDENTE (depende de F1... na
verdade não depende, usa só colunas já existentes — pode rodar em paralelo à Fase 3)
- `InsightCostView`, `InsightMonthlyCostSummary`, `InsightsCostService` (reusa
  `AiModelPricingRepository`), 2 endpoints novos em `InsightsController`.
- **Validar**: `mvn compile` real (via `docker run maven:3.9-eclipse-temurin-21`, volume
  `maven-repo-asteriskia` já cacheado).

### Fase 3 — Backend Java: status de processamento — Status: PENDENTE (depende de F1 — usa `started_at`)
- 3 endpoints internos novos (`pending`/`processing`/`error`), mudança de contrato de `known-refs`,
  `InsightProcessingItem`/`InsightsProcessingFilter`, método `findProcessing` com cálculo de posição
  na fila, `registerPending`/`markProcessing`/`markError` no `InsightsIngestionService`.
- **Validar**: `mvn compile` real.

### Fase 4 — Serviço Python: instrumentação do pipeline — Status: PENDENTE (depende de F3)
- `backend_client.py` (3 funções novas + mudança de `get_known_call_refs`), `main.py` (registrar
  pending antes de processar, processing no início, error em cada except).
- **Validar**: `python -m py_compile` + revisão manual de todas as referências cruzadas (mesma
  técnica da Fase 2 original) — e depois validação real no deploy (Fase 6), já que esta mudança só
  se prova de verdade rodando contra arquivos reais.

### Fase 5 — Frontend: abas novas — Status: PENDENTE (depende de F2+F3)
- `api/types.ts`, `InsightsCostsTab.tsx`, `InsightsCostsDashboardTab.tsx`,
  `InsightsProcessingTab.tsx`, `ModuloInsights.tsx` (5 abas).
- **Validar**: `npx tsc --noEmit` (exit 0, mesma barra das fases anteriores).

### Fase 6 — Grupos de Acesso (correção da lacuna) — Status: PENDENTE (independente, pode rodar cedo)
- `frontend/src/components/AccessGroups.tsx`: adicionar a linha
  `{ key: 'telecom.insights', label: 'Insights', system: 'Telecom' }` no array `RESOURCES`, na
  ordem correta (entre `telecom.modulo1` e `telecom.modulo2`, espelhando a posição no Sidebar).
- **Revisar os 4 pontos de sincronia** e confirmar que ficam coerentes: `ResourceCatalog.java` (já
  tem — feito na entrega base), `SecurityConfig.java` (já tem — matchers `/api/v1/insights/**`),
  `Sidebar.tsx` (já tem), `AccessGroups.tsx` (esta correção). Nenhuma migration nova: grupos seed e
  a resolução de `perm` no login já leem do `ResourceCatalog`, que já inclui a key.
- **Validar**: `npx tsc --noEmit`; conferir na UI (Fase 8) que a linha "Insights" aparece com os
  toggles de leitura/escrita num grupo customizado.

### Fase 7 — Documentação — Status: PENDENTE (depende de F5, pra documentar as abas como entregues)
- Nova seção `frontend/src/components/docs/sections/TelecomInsights.tsx` (mirror de
  `TelecomModulos.tsx` — mesma estrutura `<Section id=... title=...>`), documentando: o que é o
  módulo Insights (gravações Verint, apartado do Asterisk), as 5 abas (Chamadas, Dashboard de
  Tendências, Custos IA, Dashboard de Custos, Processamento), o significado dos status de
  processamento e da posição na fila, e como usar os filtros.
- `frontend/src/components/docs/toc.ts`: novo item `{ id: 'telecom-insights', label: 'Insights' }`
  no grupo "Telecom" (após `telecom-alertas`, antes de `telecom-rbac`).
- `frontend/src/components/Documentacao.tsx`: import + render de `<TelecomInsights />` na ordem.
- **Revisar** a seção RBAC existente (`TelecomRBAC.tsx`) e o texto de módulos: se mencionarem a lista
  de menus/recursos, incluir Insights pra não ficar desatualizada.
- **Validar**: `npx tsc --noEmit`; conferir na UI (Fase 8) que a seção aparece no TOC lateral e rola.

### Fase 8 — Deploy real + validação com dados reais — Status: PENDENTE (depende de F1-F7)
- Mesma metodologia das fases 5-6 da entrega anterior: `docker compose build insights backend
  frontend` real (este ambiente é a própria VPS — ver memória `asteriskia_dev_env_is_prod_vps`),
  `docker compose up -d`, acompanhar logs, testar endpoints novos via `curl` com JWT gerado inline
  (nunca persistido em arquivo), e **corrigir na hora** qualquer bug real que aparecer contra dados
  de produção — como aconteceu 2x na entrega anterior (overflow numérico, validação de segmentos
  vazios). Não presumir que vai dar certo de primeira.
- Release notes (`v1.28` ou próxima livre) só depois de validado rodando de verdade.

---

## Riscos

| Risco | Prob. | Mitigação |
|---|---|---|
| Mudar o contrato de `known-refs` quebra algo que já está em produção | Baixa | É endpoint 100% interno, único consumidor é o próprio `insights/src/backend_client.py` — atualizar os dois lados juntos no mesmo deploy (Fase 6), nunca fazer deploy parcial (Java novo + Python antigo, ou vice-versa) |
| Retry de erro perpétuo pra falha realmente permanente (não coberta pelos 2 fixes já aplicados) | Média | Já existia antes desta entrega (comportamento não muda, só fica visível); considerar backoff/máx-tentativas como entrega futura, fora de escopo aqui |
| "Posição na fila" ficar incoerente se a ordem real de processamento do Python não seguir estritamente `ingested_at` (hoje a ordem de discovery não é garantida) | Média | Aceitável como aproximação nesta entrega — documentar que é "posição estimada por ordem de descoberta", não uma garantia de ordem de execução real (concorrência=2 pode processar fora de ordem estrita) |
| `mark_error` disparado num ponto do pipeline que roda MUITAS vezes rápido (ex: erro de rede transitório) pode gerar muitas escritas no backend | Baixa | Volume esperado é baixo (poucas dezenas de chamadas por ciclo), não é hot-path de alta frequência |

## Acceptance
- [x] Fase 1: V36 criada e validada — aplicada em produção (`flyway_schema_history`, rank 36, success)
- [x] Fase 2: `mvn compile` limpo, endpoints de custo funcionando (`GET /api/v1/insights/costs`, `/costs/summary` validados via curl com dados reais)
- [x] Fase 3: `mvn compile` limpo, endpoints de status/processing funcionando, known-refs com novo contrato (`{"calls":[{"callRef","status"}]}`)
- [x] Fase 4: sintaxe Python validada (`py_compile`), pipeline instrumentado — 3 ciclos de poll em produção sem erro consumindo o novo contrato
- [x] Fase 5: `tsc --noEmit` exit 0, 5 abas na tela Insights (Chamadas/Dashboard/Custos IA/Dashboard de Custos/Processamento)
- [x] Fase 6: `telecom.insights` aparece na matriz de Grupos de Acesso (lacuna corrigida em `AccessGroups.tsx`)
- [x] Fase 7: seção Insights na Documentação (TOC + `TelecomInsights.tsx` + render em `Documentacao.tsx`)
- [x] Fase 8: deploy real (`docker compose build/up insights backend frontend`) — backend/frontend já estavam deployados desde a sessão anterior (imagem de 2026-07-18 03:42), `insights` recriado nesta sessão; validado com dados reais das 42 chamadas já processadas
- [x] Padrões espelhados (CallCostService/CallCostView/MonthlyCostSummary/CostsTab/CostsDashboardTab/TelecomModulos), não reinventados
- [x] 4 pontos de sincronia do RBAC coerentes (ResourceCatalog/SecurityConfig/Sidebar/AccessGroups)

---

## Fechamento (2026-07-18)

Todo o código das 7 fases já estava implementado ao retomar a sessão (compactada/reiniciada) — a
sessão anterior tinha codado tudo mas não tinha atualizado este documento nem feito o deploy final.
Nesta retomada: `mvn compile` (offline, cache já aquecido) e `npx tsc --noEmit` rodaram limpos sem
nenhum ajuste necessário; `python -m py_compile` limpo. Deploy: `docker compose build insights
backend frontend` — backend e frontend deram cache hit total (já haviam sido buildados/deployados
há 8h, na sessão anterior); só `insights` foi rebuildado e recriado agora.

**Validação real**: os 3 endpoints novos (`/api/v1/insights/costs`, `/costs/summary`,
`/processing`) responderam com os dados reais das 42 chamadas já processadas — `queuePosition`
null pra status `done` (esperado, só calculado pra `pending`). Acompanhados 3 ciclos de poll do
`insights` em produção consumindo o novo contrato de `known-refs` sem erro (nenhum arquivo novo
apareceu em `/opt/audio` durante a validação, então os caminhos `pending`→`processing`→`error` não
foram exercitados ao vivo — mas a leitura do novo formato foi confirmada e o código foi revisado
manualmente linha a linha contra o plano).

**Achado não-bug**: `estimatedCostUsd` retorna `0.000000` em todas as linhas — confirmado que é
porque `ai_model_pricing` tem preço `$0.00` cadastrado pros dois modelos Gemini desde a entrega
anterior (Custos IA de URA já tem o mesmo comportamento, `/api/v1/calls/costs` retorna igual) —
não é regressão desta entrega, é configuração de preço pendente, fora de escopo.

**Não verificado nesta sessão**: renderização visual da UI (5 abas, filtros da aba Processamento,
matriz de Grupos de Acesso, seção nova de Documentação) — Chrome DevTools MCP não conseguiu
conectar neste ambiente (VPS de produção sem navegador com extensão Claude ativa). Recomendo
validação visual manual pelo usuário na próxima vez que acessar a tela Insights.

Release notes: ainda **não registrada** em `frontend/src/data/releases.ts` — pendência, ver memória
`asteriskia_release_notes_mandatory`.
