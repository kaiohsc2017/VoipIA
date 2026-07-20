# Plan: Insights → Plataforma de Monitoria de Qualidade (Quality Management)

**Origem**: conversa livre (não há PRD), pedido direto do usuário em 2026-07-19
**Complexidade**: Large (ALTA) — 3 fases independentes em deploy, com dependência de dados entre Fase 1→2
**Status**: APROVADO PARA CONSULTA — aguardando confirmação final ("sim") antes de iniciar o código
**Última migration existente na branch main**: V37__insights_rbac_namespace.sql (este plano usa V38, V39, V40)

---

## 1. Objetivo e requisitos originais do usuário

Evoluir o módulo Insights (hoje: transcrição + análise livre de gravações Verint via IA) para um
sistema de **Quality Management (QM)** de call center, comparável a NICE CXone, Verint AQM e
Genesys Cloud QM. Três pedidos originais do usuário, todos com RBAC de posse (supervisor vê só o
seu; ADMIN vê tudo):

1. **Fichas de avaliação (scorecards)** configuráveis com perguntas, pesos e notas. A IA analisa a
   chamada e atribui nota por item com base nas perguntas/pesos cadastrados. Se o agente zerar um
   indicador crítico ou ficar abaixo da nota média, o sistema sinaliza para estudo/ação.
2. **Relatórios de performance por atendente** — supervisor busca o atendente, define período,
   pede geração; relatório traz indicações de onde o agente deve melhorar. ADMIN vê todos os
   relatórios (data/hora, supervisor solicitante, conteúdo); supervisor só vê os que ele mesmo
   pediu.
3. **Portal do supervisor** — upload de até 100 arquivos de áudio por vez para transcrição/análise
   ad-hoc de casos específicos, com visualização do processamento e dos dados da ligação numa
   tela única (unifica o que hoje são as abas separadas Chamadas + Processamento). Mesma regra de
   posse.

### Adições feitas durante o planejamento (todas confirmadas com o usuário)

- Áudio de upload salvo em pasta própria `/opt/audio_upload` (não subpasta de `/opt/audio`) —
  elimina risco de o watcher do Verint reprocessar/cruzar os dois fluxos.
- Portal do supervisor tem suas próprias abas **Custo IA** e **Dashboard de Custos**, filtradas por
  `source='upload'` — visibilidade de gasto separada por origem.
- Relatório de performance: visualização web **+ exportação em PDF**.
- Relatórios sucessivos do mesmo atendente comparam com o relatório anterior e indicam evolução
  item a item; esse histórico de evolução fica navegável pelo supervisor (não só dentro de um
  relatório específico).
- Cooldown: um supervisor só pode gerar 1 relatório por atendente a cada **5 dias úteis**
  (sábado/domingo não contam; sem calendário de feriados no MVP). O limite é por par
  (supervisor, atendente) — ADMIN não tem esse limite.

---

## 2. Benchmark: mercado (NICE / Verint / Genesys) vs. o que este plano adota

| Prática do mercado | NICE CXone | Verint AQM | Genesys Cloud QM | Neste plano |
|---|---|---|---|---|
| Formulários de avaliação com perguntas ponderadas | ✅ Enlighten AutoQM | ✅ Form Designer | ✅ Evaluation Forms | ✅ Fase 1 |
| Avaliação automática por IA de 100% das chamadas | ✅ | ✅ | Parcial (STA) | ✅ Fase 1 — toda chamada processada com ficha ativa é avaliada |
| Pergunta fatal / auto-fail | ✅ | ✅ "compliance triggers" | ✅ "fatal questions" | ✅ Fase 1 — `is_critical`, cálculo determinístico no Java |
| Evidência ancorada na transcrição | ✅ | ✅ | ✅ | ✅ Fase 1 — nota + justificativa + `trecho_referencia` |
| Dashboards de performance por agente com tendência | ✅ | ✅ | ✅ Agent Development | ✅ Fases 1+2 |
| Relatório de coaching com recomendações | ✅ | ✅ | ✅ | ✅ Fase 2 |
| Histórico de evolução do agente entre avaliações | ✅ | ✅ | ✅ | ✅ Fase 2 (adição do usuário) |
| Upload/ingestão de gravações externas | ✅ | ✅ | ✅ | ✅ Fase 3 |
| Calibração entre avaliadores, contestação pelo agente, planos de coaching formais | ✅ | ✅ | ✅ | ❌ Fora de escopo — exige o agente como usuário do sistema, que hoje não existe (YAGNI) |

---

## 3. Achados do codebase que moldam o desenho (pattern grounding)

| Categoria | Fonte | Padrão a espelhar |
|---|---|---|
| Ingestão idempotente por chave natural | `InsightsIngestionService.java:34` | upsert por `callRef`, estados `pending/processing/done/error` |
| Polling assíncrono limitado | `insights/src/main.py:190-197` | `asyncio.Semaphore` + `asyncio.gather`, retry automático via reentrada no polling |
| Paginação | `InsightsQueryService.java:36-48` | `Specification` dinâmica + `Page<T>`, 2ª query em lote para evitar N+1 |
| Custo de IA por tokens | `InsightsCostService.java` | reusa `ai_model_pricing`, nunca duplica tabela de preço |
| RBAC granular por aba | `SecurityConfig.java:129-166`, `ResourceCatalog.java:51-56` | `ROLE_ADMIN` OU `PERM_READ/WRITE_<resource>`; sincronização manual documentada entre `ResourceCatalog.java` / `App.tsx` / `Sidebar.tsx` |
| Export tabular | `ReportCsvBuilder` (`domain/report/`) | CSV com timestamp no nome; não há lib de PDF no projeto (`pom.xml` sem iText/openhtmltopdf) |
| Identidade do usuário nos controllers | `JwtAuthFilter.java:82-85` | principal de segurança é o **username** (não há user-id nem claim de id no JWT); posse é sempre por username |
| Clamp de valor vindo do LLM | `insights_llm.py:168` (bug real de produção, overflow em `NUMERIC(4,3)`) | nunca persistir número cru do LLM sem clamp determinístico no lado que grava |
| Mounts de áudio existentes | `docker-compose.yml:217,336` | `/opt/audio:ro` tanto no `backend` quanto no `insights` — não há mount RW hoje em nenhum serviço para áudio |
| Cálculo de dias úteis | busca no repo (`grep -rniE "business.?day\|dia.?util\|isWeekend"`) | **não existe** utilitário hoje — `PeriodRangeResolver.java:22` só trata semana corrida; será código novo |

### Decisões de arquitetura derivadas dos achados

- **Quem fala com o Gemini é sempre o Python** (`insights/`) — nunca o Java. Fichas (Fase 1) e
  relatórios (Fase 2) seguem esse padrão: Java persiste e orquestra, Python chama o modelo.
- **Números nunca vêm do LLM sem validação determinística no Java**: nota de item, nota total,
  auto-fail, delta de evolução entre relatórios — tudo calculado em SQL/Java; o LLM só produz
  texto (justificativa, narrativa do relatório, comparação em prosa).
- **Posse de registro = coluna `username`**, filtrada no service (não só na listagem, para não
  vazar por acesso direto ao id) — não há alternativa melhor sem adicionar user-id ao JWT, o que
  está fora de escopo deste plano.

---

## 4. Fase 1 — Fichas de avaliação com nota por IA

### Migration `V38__quality_scorecards.sql`

- `quality_scorecards`: `id, name, description, is_active BOOL, version INT, created_at, updated_at`.
  Índice parcial único garantindo **uma única ficha ativa por vez**. Editar uma ficha em uso cria
  **nova versão** (linha nova, versão incrementada) — avaliações antigas continuam referenciando a
  versão com que foram avaliadas (imutabilidade do histórico).
- `scorecard_items`: `id, scorecard_id FK, ordem INT, pergunta TEXT, peso NUMERIC, nota_maxima INT,
  is_critical BOOL DEFAULT false`.
- `call_evaluations`: `id, audio_file_id FK UNIQUE → call_audio_files, scorecard_id FK,
  nota_total NUMERIC(5,2)` (normalizada 0–100), `is_failed BOOL, fail_reason TEXT,
  llm_tokens_in/out INT, llm_model VARCHAR, created_at`.
- `call_evaluation_items`: `id, evaluation_id FK CASCADE, item_id FK, nota NUMERIC, justificativa
  TEXT, trecho_referencia TEXT`.
- Índices: `call_evaluations(nota_total)`, `call_evaluations(is_failed)`,
  `call_evaluations(audio_file_id)`.

### Backend Java (`domain/insights/`)

- `ScorecardController` — CRUD em `/api/v1/insights/scorecards`; leitura `PERM_READ_insights.scorecards`,
  escrita `PERM_WRITE_insights.scorecards` (novo resource key em `ResourceCatalog.java`).
- `InsightsInternalController` ganha `GET /internal/insights/active-scorecard` (Python consulta a
  ficha ativa) e `IngestInsightsRequest` ganha bloco opcional `evaluation` (notas por item vindas
  do LLM).
- `EvaluationService` (novo) — cálculo determinístico: nota total ponderada pelos pesos, regra de
  auto-fail (`is_critical` com nota 0 → `is_failed=true`), clamp de cada nota recebida a
  `[0, nota_maxima]` antes de persistir (mesma lição do bug de `aderencia_script`).
- Busca de chamadas ganha filtros `notaMin/notaMax`, `isFailed`; dashboard ganha "média por agente"
  e "agentes abaixo da média / com auto-fail no período".

### Python (`insights/src/`)

- Novo `evaluation_llm.py` — chamada Gemini separada da análise geral: recebe transcrição + itens
  da ficha ativa, devolve JSON schema `{items:[{item_id, nota, justificativa, trecho_referencia}]}`.
  Separação deliberada da análise existente (`insights_llm.py`) para permitir, no futuro, reavaliar
  só a ficha sem refazer STT.
- `main.py`: busca a ficha ativa 1×/ciclo com cache TTL (mesmo padrão de `config.py`). Se não
  houver ficha ativa, pipeline segue exatamente como hoje — avaliação é opcional/retrocompatível.

### Frontend (SPA `insights-platform/frontend/`)

- Nova aba **"Fichas"** (`ScorecardsTab.tsx`) — CRUD de perguntas/pesos/nota máxima/flag crítico.
  Resource novo `insights.scorecards` adicionado em `ResourceCatalog.java` + `App.tsx` (mapa
  `TAB_RESOURCE`) + `Sidebar.tsx` (`NAV_ITEMS`) — sincronização manual tripla, já documentada como
  padrão intencional no projeto.
- Aba Chamadas: coluna de nota + badge "Reprovada"; modal de detalhe ganha seção "Avaliação" com
  nota por item, justificativa e trecho de referência.
- Dashboard: cards "Agentes abaixo da média" e "Auto-fails no período".

---

## 5. Fase 2 — Relatórios de performance por atendente (com evolução e cooldown)

### Migration `V39__agent_performance_reports.sql`

- `agent_performance_reports`: `id, agent_name VARCHAR, date_from DATE, date_to DATE,
  requested_by VARCHAR (username), requested_at TIMESTAMPTZ, status
  (pending/processing/done/error), error_msg TEXT, content_json JSONB, llm_tokens_in/out INT,
  llm_model VARCHAR, completed_at TIMESTAMPTZ, previous_report_id FK nullable (self-reference),
  evolution_json JSONB nullable`.
- Índices: `(status)`, `(requested_at)`, e **`(requested_by, agent_name, requested_at DESC)`** —
  este último dedicado à checagem de cooldown (item 6), consulta O(log n) mesmo com histórico
  grande.
- Índice único parcial em `(requested_by, agent_name)` filtrado por
  `status IN ('pending','processing')` — cinturão de segurança contra race condition de dois
  pedidos simultâneos do mesmo par supervisor/atendente.
- Nova tabela `agent_evolution_snapshots`: `id, agent_name VARCHAR, report_id FK, item_id
  (referência a scorecard_items, nullable — cobre também métricas sem ficha, ex. sentimento),
  metric_key VARCHAR, valor NUMERIC, created_at`. Motivo de existir separada do
  `evolution_json` do relatório: permite ao supervisor navegar o histórico do agente
  independentemente de abrir um relatório específico (gráfico de tendência ao longo do tempo).

### Backend Java

- `AgentReportController` (`/api/v1/insights/reports`):
  - `POST` — cria pedido. Antes de enfileirar: (a) checa **cooldown de 5 dias úteis** via
    `BusinessDayCalculator` (novo utilitário — não existe cálculo de dia útil no projeto hoje;
    `PeriodRangeResolver.java:22` só trata semana corrida); ADMIN é isento da checagem (mesmo
    modelo dual do resto do RBAC). Se bloqueado → **HTTP 429** com `nextAllowedAt` no corpo. (b)
    busca o último `agent_performance_reports` `done` do mesmo `agent_name` (qualquer solicitante)
    e grava seu id em `previous_report_id`, resolvendo a comparação **antes** de enfileirar — regra
    de negócio no Java, não no Python.
  - `GET` — lista filtrando por `requested_by = principal` quando não-ADMIN; ADMIN vê todos com
    coluna de solicitante.
  - `GET /{id}` — mesma checagem de posse; 404 (não 403) para relatório alheio, para não vazar
    existência.
  - `GET /{id}/pdf` — exporta o relatório em PDF (ver abaixo).
  - `GET /agent/{agentName}/next-allowed` — retorna a data em que o supervisor logado poderá gerar
    de novo para aquele agente (UI desabilita o botão com essa informação em vez de só reagir ao
    429).
  - `GET /agent/{agentName}/evolution` — série temporal de `agent_evolution_snapshots` para o
    gráfico de tendência; mesma regra de posse (via `EXISTS` contra `agent_performance_reports.requested_by`).
- Endpoints internos para o Python: `GET /internal/insights/reports/pending`,
  `POST /{id}/processing|error`, `POST /{id}/result`.
- Agregação dos dados do relatório é **SQL no Java** (médias, evolução, piores itens da ficha,
  findings mais frequentes do agente no período) — o LLM recebe só o agregado e escreve a
  narrativa; isso limita tokens/custo e impede o LLM de inventar números. O **delta numérico** de
  evolução entre o relatório atual e o `previous_report_id` também é calculado no Java e gravado em
  `evolution_json` + espelhado em `agent_evolution_snapshots`; o LLM só narra o delta já calculado.
- **PDF**: adicionar **openhtmltopdf** (Apache 2.0) ao `pom.xml` — gera PDF a partir de HTML/CSS,
  sem motor de browser embutido; escolhida por não ter a licença AGPL do iText e ser a opção padrão
  para esse caso em Spring Boot. Isolado a um único endpoint (renderiza um template Thymeleaf
  dedicado, único ponto do projeto com server-side rendering — não introduz o padrão em nenhum
  outro lugar).

### Python

- `poll_loop` existente ganha uma segunda verificação por ciclo: relatórios `pending` → monta
  prompt com o agregado (e, se houver `previous_report_id`, o agregado anterior lado a lado) →
  gera narrativa estruturada (pontos fortes, pontos de melhoria priorizados, recomendações,
  comparação textual da evolução já calculada) → `POST /result`. Reusa semáforo, retry e token
  accounting existentes.
- Se as versões de ficha usadas nos dois relatórios divergirem, o relatório sinaliza "ficha
  alterada entre os períodos — comparação parcial" em vez de comparar itens incompatíveis
  silenciosamente.

### Frontend

- Nova aba **"Relatórios"** (`ReportsTab.tsx`, resource `insights.reports`): autocomplete de
  atendente, filtro de datas, botão "Gerar" (desabilitado com contagem regressiva quando em
  cooldown, via `next-allowed`); tabela com data/hora, solicitante (coluna só para ADMIN), status,
  visualização web do conteúdo e botão "Exportar PDF".
- Seção "Evolução desde o último relatório" dentro do relatório — tabela de deltas por item com
  setas ↑/↓/=.
- Sub-view **"Histórico do Agente"** — gráfico de linha (nota total ao longo do tempo) + tabela de
  evolução por item, usando o endpoint `/evolution`. Supervisor só acessa histórico de agentes que
  ele mesmo reportou; ADMIN, todos.

---

## 6. Fase 3 — Portal do supervisor (upload em lote)

### Migration `V40__insights_uploads.sql`

- `call_audio_files` ganha `source VARCHAR DEFAULT 'verint'`, `uploaded_by VARCHAR (username,
  nullable)`, `upload_batch_id UUID nullable` + índices.
- `upload_batches`: `id, uploaded_by VARCHAR, created_at, file_count INT, notes TEXT`.

### Infraestrutura

- Novo diretório no host: **`/opt/audio_upload`** (separado de `/opt/audio`, não subpasta —
  elimina por construção o risco de o `discovery.py` do Verint, que faz `os.listdir` não-recursivo
  em `INSIGHTS_AUDIO_DIR`, cruzar os dois fluxos caso um ajuste futuro torne o scan recursivo).
- `docker-compose.yml`:
  - `backend`: novo mount **RW** `- /opt/audio_upload:/opt/audio_upload:rw` (hoje o backend não
    monta áudio nenhum — sem RO existente para conflitar).
  - `insights`: mount **RO** adicional `- /opt/audio_upload:/opt/audio_upload:ro`, nova env
    `INSIGHTS_UPLOAD_AUDIO_DIR=${INSIGHTS_UPLOAD_AUDIO_DIR:-/opt/audio_upload}`.

### Backend Java

- `InsightsUploadController` (`/api/v1/insights/uploads`, novo resource `insights.uploads`):
  - `POST` multipart — máx. **100 arquivos/lote**, **50 MB/arquivo** (constantes nomeadas;
    `spring.servlet.multipart` e limite de corpo no Caddy ajustados de acordo); extensões aceitas
    `wav/mp3/ogg/m4a` (validação real de conteúdo fica no Python via `ffprobe`, que já roda no
    container `insights`). Salva em `/opt/audio_upload/{batchId}/` com nome sanitizado (reusa a
    defesa `resolveWithinBase` de `InsightsController.java:173`), registra cada arquivo como
    `pending` com `source='upload'` e `call_ref` sintético (`up-{batchId}-{seq}`).
  - Listagens de chamadas/processamento ganham filtro por `source`; na visão do portal, não-ADMIN
    é filtrado por `uploaded_by = principal`.
- `InsightsCostService` ganha filtro `source` (reusa a coluna nova, só um `WHERE` a mais na query
  já existente — sem tabela de custo nova).

### Python

- `discovery.py` roda **dois scans independentes** por ciclo: um em `INSIGHTS_AUDIO_DIR` (Verint,
  regra atual .wav+.xml por regex) e um em `INSIGHTS_UPLOAD_AUDIO_DIR` (uploads já registrados como
  `pending` pelo Java — só confirma que o arquivo físico existe antes de processar, sem precisar de
  descoberta por regex).
- `process_pair` ganha branch `source='upload'`: sem XML da Verint, metadados vêm do registro feito
  no upload (nome do atendente, direção, observação livre); `audio_decode.py` já converte qualquer
  formato aceito via ffmpeg, sem mudança.

### Frontend

- Nova aba **"Meus Envios"** (`SupervisorPortalTab.tsx`) — tela unificada: dropzone (até 100
  arquivos, contadores e validação client-side), lista dos lotes do usuário com status por arquivo
  (**com polling de 10s enquanto houver `pending/processing`** — diferente da aba Processamento
  original, que deliberadamente não tem polling), e ao concluir, dados da ligação/nota/insights
  inline (reusa o modal de detalhe da aba Chamadas). ADMIN vê lotes de todos com coluna de quem
  enviou.
- Duas sub-abas de custo dentro do mesmo portal, **reusando componente com prop de origem** (DRY —
  não duplicam código): `SupervisorCostsTab.tsx` e `SupervisorCostsDashboardTab.tsx`, espelhando
  `InsightsCostsTab`/`InsightsCostsDashboardTab` parametrizados por `source='upload'`. Regra de
  posse: supervisor vê custo só dos seus lotes (`uploaded_by=principal`); ADMIN vê tudo. Resource
  RBAC reusado: `insights.uploads` (não cria resource separado só para custo, está dentro do mesmo
  portal).

---

## 7. Dependências entre fases

- **Fase 2 depende da Fase 1** — o relatório fica muito mais rico com notas de ficha; sem ela, se
  limitaria a findings/sentimento (ainda funcional, mas menos valioso).
- **Fase 3 é independente** das outras duas — pode ser antecipada se preferir.
- Cada fase = 1 migration + 1 deploy (`backend`, `insights`, `frontend`) — deploys independentes,
  sem big-bang, seguindo o padrão de todas as entregas anteriores do projeto.

---

## 8. Riscos consolidados

| Risco | Prob. | Mitigação |
|---|---|---|
| Custo Gemini sobe (+1 chamada LLM por ligação avaliada; relatórios geram mais chamadas) | ALTA | Avaliação só roda com ficha ativa; tokens contabilizados nas tabelas novas e somados na aba Custos IA existente (padrão `InsightsCostService`); relatório recebe agregado SQL, não transcrições brutas |
| LLM devolve nota fora da escala ou inventa número no relatório/evolução | MÉDIA | Clamp determinístico no Java (lição do overflow real de `aderencia_script` em produção); números de relatório e delta de evolução calculados por SQL/Java, LLM só narra |
| Dois mounts de áudio (`/opt/audio` RO + `/opt/audio_upload` RW) aumentam superfície de configuração | BAIXA | Documentado no `CLAUDE.md` e no `install.sh`; variáveis de ambiente com default, nunca path hardcoded |
| openhtmltopdf é dependência nova no backend Java | BAIXA | Lib madura, Apache 2.0, sem risco de licença; uso isolado a um único controller |
| Escopo "supervisor vê só o seu" burlável via ID direto | MÉDIA | Checagem de posse no service (não só na listagem); 404 para registro alheio; revisão obrigatória por `security-reviewer` antes do commit de cada fase (authz é gatilho automático de revisão de segurança) |
| Upload de arquivo malicioso/não-áudio | MÉDIA | Limite de tamanho + extensão no Java; validação de conteúdo por `ffprobe` no Python (falha → status `error` no lote); nome de arquivo nunca usado cru em path (reuso de `resolveWithinBase`) |
| Reavaliação ao trocar ficha reprocessaria STT (custo) se implementada ingenuamente | BAIXA no MVP | Versão da ficha gravada na avaliação; reavaliação isolada da etapa de STT fica documentada como possível v2, não implementada agora |
| `previous_report_id` encadeado formar corrente longa | BAIXA | Consulta de série usa `agent_evolution_snapshots` (linear por `agent_name`+`created_at`), não recursão pela cadeia de `previous_report_id` |
| Comparação de evolução com fichas de versões diferentes | MÉDIA | `evolution_json` registra a versão da ficha de cada lado; se divergir, relatório sinaliza "comparação parcial" em vez de comparar itens incompatíveis |
| Race condition no cooldown (dois cliques simultâneos) | BAIXA | Índice único parcial em `(requested_by, agent_name)` para status em voo, como cinturão de segurança além da checagem de aplicação |
| Regra de cooldown mal compreendida (supervisor acha que é global por atendente, não por ele mesmo) | BAIXA | Mensagem de erro explícita citando data e que é por solicitante |

---

## 9. Validação por fase

```bash
# Backend
mvn -q compile   # ou: docker compose build backend (Maven indisponível localmente em algumas sessões)

# Python
python -m ast insights/src/*.py

# Frontend (SPA insights)
cd insights-platform/frontend && npx tsc --noEmit

# Deploy incremental por fase
docker compose up -d --build backend insights frontend

# Smoke test manual por fase (JWT forjado ADMIN e não-ADMIN, testando o escopo de posse)
curl -sS -H "Authorization: Bearer $JWT_ADMIN" https://app.voiphash.com.br/api/v1/insights/...
curl -sS -H "Authorization: Bearer $JWT_SUPERVISOR" https://app.voiphash.com.br/api/v1/insights/...
```

Toda entrega precisa de linha nova em `frontend/src/data/releases.ts` (regra obrigatória do
projeto, ver memória `asteriskia_release_notes_mandatory`).

---

## 10. Aceite

- [ ] Fase 1 — fichas, avaliação por IA, auto-fail, dashboard de agentes abaixo da média — deployada e validada
- [ ] Fase 2 — relatórios, PDF, evolução entre relatórios, histórico navegável, cooldown de 5 dias úteis — deployada e validada
- [ ] Fase 3 — upload em lote, pasta separada, custos por origem — deployada e validada
- [ ] `security-reviewer` rodado em cada fase antes do commit (authz é gatilho obrigatório)
- [ ] `release-notes` atualizado a cada entrega
- [ ] Memória do projeto atualizada ao final de cada fase (padrão já usado nas entregas anteriores de Insights)

---

## Complexidade estimada: **ALTA (Large)**

- Fase 1 (Fichas): a maior — migration + CRUD + pipeline de avaliação + UI + dashboard.
- Fase 2 (Relatórios): média-alta — ganhou histórico/evolução/PDF/cooldown nesta revisão.
- Fase 3 (Upload): média — risco concentrado em infraestrutura (mount novo) e segurança de upload.

**PLANO GRAVADO EM**: `.claude/plans/insights-quality-management.plan.md` — retomável em qualquer
sessão futura lendo este arquivo.

**AGUARDANDO CONFIRMAÇÃO**: Posso prosseguir com a implementação?
- **"sim"** — começo pela Fase 1 (Fichas de avaliação)
- **"modifique: [...]"** — mais um ajuste antes de começar
- **"começa pela fase X"** — inverto a ordem (Fase 3 é a única sem dependência das outras)
