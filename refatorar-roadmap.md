# Roadmap de Refatoração VoipIA — Fase 1 (Auditoria)

> Gerado em 2026-07-13 por auditoria arquitetural (4 agentes paralelos, um por módulo).
> **Este arquivo é o artefato vivo do plano.** Marque `[x]` conforme executar. Não apagar até 100% concluído.
> Fase 2 (execução) segue o processo obrigatório do `refatorar.txt`: diagnóstico → diff → trade-off → edge cases → testes.
>
> Convenções: commits `telecom:` (backend/frontend/asterisk) ou `agents:` (agents-platform/ai-agent).
> Validar antes de commitar: `bash -n` (shell), `python -m py_compile` (Python), `tsc --noEmit` (TS), `mvnw compile` (Java).
> Deploy = git pull no main → rebuild do container afetado. Toda entrega precisa de entrada em `frontend/src/data/releases.ts`.

## Legenda
- Severidade: 🔴 alta · 🟡 média · ⚪ baixa
- Tipo: **[R]** risco real · **[E]** estilo
- Esforço: P (pequeno) · M (médio) · G (grande)

---

## Ondas de execução (ordem sugerida)

### 🚨 ONDA 0 — Segurança crítica (isolar, fazer primeiro, PRs pequenos)
Correções de segurança que não podem esperar decomposição estrutural.

- [x] **O0.1** `agents-platform/backend/executor.py:604-705` + `routers/agents.py:56-71` — **[R] 🔴** Bypass de autorização: gate "só ADMIN define cmd/fix_cmd" só cobre SSH; agente tipo `database` usa `checks[].dsn`+`checks[].query` sem verificação equivalente → qualquer usuário com `PERM_WRITE_agents.agents` executa SQL arbitrário em qualquer DSN alcançável. Estender `_rules_has_ssh_exec` para cobrir dsn/query. (agents:) — ✅ **fase 15**, deployado e testado em produção 2026-07-13.
- [x] **O0.2** `agents-platform/backend/main.py:33,78` — **[R] 🔴** Swagger/OpenAPI públicos sem JWT em produção (expõe schema de secrets/servers). Desabilitar `docs_url/redoc_url/openapi_url` condicionado a env, ou exigir ADMIN. (agents:) — ✅ **fase 15**, deployado e testado em produção 2026-07-13.

### 🩹 ONDA 1 — Risco real, baixo esforço, alto valor (correções cirúrgicas)
Bugs funcionais e falhas silenciosas. Sem refatoração estrutural.

- [x] **O1.1** `backend/.../config/GlobalExceptionHandler.java:49-57` + `domain/call/CallRecordService.java:204,222` + `domain/masterdata/ClientController.java:96,101,120` — **[R] 🔴** `orElseThrow(RuntimeException)` → 500 em vez de 404. Criar `ResourceNotFoundException` + handler dedicado (404). [Grupo A Java] (telecom:) — ✅ **fase 16**, testado em produção (`/calls/{id-inexistente}` e `/clients/{id-inexistente}/operations` → 404).
- [x] **O1.2** Frontend promises sem `.catch` (falha silenciosa): `components/ModuloURA.tsx:592,707-717`; `Auditoria.tsx:76`; `ModuloConectividade.tsx:372-373` — **[R] 🔴/🟡**. Adicionar tratamento de erro + feedback ao usuário. [Grupo A React] (telecom:) — ✅ **fase 16**, `.catch` adicionado seguindo o padrão já usado no projeto (`console.error` + limpar estado).
- [x] **O1.3** ai-agent hot-path de voz — **[R] 🔴/🟡**: `providers/gemini.py:244-249` engole `CancelledError` (deixar propagar); `main.py:203` task sem referência forte (guardar em set global); `services/audio_cache.py:47-51,58-59` I/O de disco bloqueante no event loop (usar `asyncio.to_thread`). [Grupo B ai-agent] (agents:) — ✅ **fase 16**, `py_compile` limpo, deployado; validação funcional completa (ligação real) pendente por não ser possível gerar tráfego de voz real neste teste.
- [x] **O1.4** `agents-platform/backend/routers/reports.py:37-46` — **[R] 🔴** bug funcional: falhas de agente `database` (`report["checks"]` na raiz) nunca aparecem no relatório. Corrigir extração. (agents:) — ✅ **fase 16**, testado em produção com agente `database` real (falha de auth) — aparece corretamente em `failures[]`.
- [x] **O1.5** `agents-platform/backend/executor.py:916-939` — **[R] 🔴** auto-fix sempre usa `servers[0]`, pode disparar correção no host errado. Usar servidor correto por check. (agents:) — ✅ **fase 16**, corrigido (resolve por nome do check no `report["servers"]`, fallback documentado para `servers[0]` se não houver match); `py_compile` limpo — **não testado ao vivo** com múltiplos servidores SSH reais (sem ambiente seguro disponível), validado por leitura de código.
- [x] **O1.6** `agents-platform/backend/executor.py:963-965` — **[R] 🟡** retenção via `hash(str)%100` (não-determinístico, PYTHONHASHSEED). Trocar por job periódico no scheduler. (agents:) — ✅ **fase 16**, trocado para `execution_id.int % 100` (determinístico, sem depender de `PYTHONHASHSEED`); `py_compile` limpo.
- [x] **O1.7** `frontend .../ModuloURA.tsx:55,603,722; Softphone.tsx:248` — **[R] 🟡** `eslint-disable exhaustive-deps` sem justificativa; confirmar se `:722` mascara dependência real de filtros. (telecom:) — ✅ **fase 16**, confirmado que ambos os casos são intencionais (busca sob demanda, assinatura WS só no mount) — adicionados comentários de justificativa, sem mudança de comportamento.

### 🐛 Achados emergentes durante o teste da fase 16 (fora do roadmap original, corrigidos por bloquear a validação)
- **`agents-platform/backend/executor.py:878-879`** (`run_agent`, cálculo de `no_targets`) — `rules` chega como string JSON crua do banco (asyncpg não decodifica `jsonb` automaticamente) e `scheduler.py` nunca normaliza antes de chamar `run_agent`; `.get("checks")` na string quebrava com `AttributeError` **toda execução de agente sem `server_ids`/`target_urls`** (todo agente `type=database`, entre outros). Corrigido com `json.loads` condicional. — ✅ fase 16.
- **`agents-platform/backend/routers/reports.py:40`** (`execution_report`) — mesmo problema: `report_json` (`JSONB`) chega como string, `.get("servers")` quebrava com `AttributeError` — endpoint de relatório de execução estava **500 para qualquer execução**, não só agentes `database`. Corrigido com `json.loads` condicional (cobre também `/execution/{id}/html`, que reusa a função). — ✅ fase 16.

### 🔗 ONDA 2 — Duplicação estrutural (média alavancagem)
Consolidar código duplicado antes de decompor God classes.

- [x] **O2.1** ai-agent duplicação Gemini — **[R] 🔴**: `services/gemini_service.py:124-421` classe `GeminiService` morta (nunca instanciada) + duplicação com `providers/gemini.py` (raiz do TypeError histórico de schema de tool). Mover helpers (`_get_global_client`, `_execute_tool`, `_TOOLS`, `_pcm_to_wav`, `_resample_pcm`) p/ módulo neutro, deletar `GeminiService` e o `.bak`. [Grupo A ai-agent] (agents:) — ✅ **fase 17**, `GeminiService`+`.bak` deletados; helpers movidos para `providers/gemini_shared.py`; testado ao vivo em produção (LLM e TTS reais via Gemini funcionando).
- [x] **O2.2** `backend .../AsteriskConfigController.java:251-289`, `domain/logs/AsteriskAmiClient.java`, `integration/ami/AmiOriginateService.java`, `domain/StatsTrunkAmiClient.java` — **[R] 🔴** protocolo AMI duplicado em 4 lugares. Extrair `AmiClient` único. [Grupo B Java] (telecom:) — ✅ **fase 18**, `AmiSession` (`integration/ami/`) criado com `connect/login/send/readBlock/readUntil/logoff`; os 4 chamadores migrados, cada um mantendo seu próprio parsing/log de erro (já divergiam). Revisão encontrou e corrigiu vazamento de socket em `connect()` (banner de boas-vindas podia falhar antes do `try-with-resources` do chamador existir). Deployado e testado ao vivo em produção 2026-07-14: `GET /api/v1/logs/asterisk/status` (login AMI + `Command` real) e `GET /api/v1/stats/trunk-status` (login AMI + `pjsip show contacts` real, `status: ONLINE`, `rttMs: 20`) confirmam que `AmiSession.connect/login/send/readBlock/readUntil/logoff` funcionam corretamente contra o Asterisk real. `POST /asterisk-config/{tronco,rotas}` (reload de config real) e `AmiOriginateService` (originar chamada real) **não foram exercitados** por serem ações com efeito colateral em produção (reload de tronco/dialplan ao vivo, discagem real) — usam os mesmos primitivos já validados, risco residual baixo.
- [x] **O2.3** ai-agent padrão "falar e aguardar" duplicado 3x: `flows/jira_call_flow.py:222-312`, `flows/zabbix_alert_flow.py:79-90` (deriva: zabbix não drena reader). Extrair helper único em `protocol.py`. [parte Grupo C ai-agent] (agents:) — ✅ **fase 17**, `drain_reader`/`wait_playback_and_drain` extraídos para `protocol.py`; zabbix agora dreno o reader (corrige o desvio).
- [x] **O2.4** ai-agent `_pcm_to_wav`/`_resample` reimplementados 3x: `providers/openai_provider.py:41-60`, `services/gemini_service.py:429-473`, `providers/local_provider.py:27-34`. Extrair `providers/audio_utils.py`. (agents:) — ✅ **fase 17**, `providers/audio_utils.py` criado (`pcm_to_wav`/`resample_pcm`), 3 arquivos migrados; STT validado (código executou corretamente, 503 foi erro transitório da API externa do Gemini).
- [x] **O2.5** Frontend auth client-side espalhada: `api/client.ts:25-27`, `Softphone.tsx:25-29`, `Operadoras.tsx:13-15`, `Cadastro0800.tsx:173-175`, `Linhas.tsx:58-60`, `App.tsx:94-111`. Consolidar em hook `useAuthSession()`. [Grupo B React] (telecom:) — ✅ **fase 19**, novo `hooks/useAuthSession.ts` exporta `authSessionFromToken(token)` (função pura, usada por `App.tsx` que já mantém `role`/`perms` como estado próprio) e `useAuthSession()` (lê `localStorage` direto, usado por `Operadoras`/`Cadastro0800`/`Linhas`, que só liam o token uma vez por render). `decodeTokenPayload` exportado de `client.ts` e reusado por `Softphone.tsx` (antes fazia `atob`+`JSON.parse` cru, sem o tratamento de base64 URL-safe/UTF-8 que a versão compartilhada já tinha). `tsc -b && vite build` e `npm run build` limpos; ESLint mostrou os mesmos 16 erros pré-existentes (confirmado via `git stash`) — nenhum introduzido pela mudança. Deployado em produção 2026-07-14: `docker compose up -d --build frontend` healthy; teste E2E via navegador (chrome-devtools MCP) **não foi possível neste VPS** (sem `DISPLAY`, ambiente headless sem servidor X — limitação de ambiente, não do código). Validação alternativa: reproduzida em Node a lógica exata de `decodeTokenPayload`/`authSessionFromToken` contra um JWT ADMIN forjado real — `hasWrite` correto para ADMIN (`true`) e para USER sem `perm` (`false`), `extension` decodificado corretamente para o Softphone; confirmado que o bundle `Operadoras-*.js` servido em produção (200, hash novo) contém a lógica consolidada (`hasWrite` presente no JS minificado). Sem erros nos logs do container `frontend`.
- [x] **O2.6** `backend .../StatsCallRepository.java:54-147` — **[E] 🟡** 5 queries de ranking quase idênticas. Consolidar (avaliar Criteria API). [Grupo E Java — com testes de integração] (telecom:) — ✅ **fase 18**, extraídas as constantes `BU_URA_JOIN_PREFIX`/`BU_URA_SCOPE_SUFFIX` (concatenação de `String` constante dentro do `@Query` nativo, válido em tempo de compilação) reusadas pelas 5 queries; SQL final idêntico caractere a caractere ao original (conferido manualmente). Sem migrar para Criteria API, conforme o plano já aprovado (risco de regressão maior sem suíte de testes de integração cobrindo essas queries). Deployado e testado ao vivo em produção 2026-07-14 com dados reais (`GET /stats/calls/ranking?dateFrom=2026-06-01&dateTo=2026-07-14`): `topClients`, `byCallType`, `topSubjectsByCallType` e `avgDurationByCallType` retornaram valores corretos; `topResolutions` veio vazio por não haver `jira_resolution` preenchido nos registros de teste (esperado, não é regressão). Sem exceções nos logs do backend durante o teste.

### 🏗️ ONDA 3 — Decomposição de God classes (maior esforço/risco — testar E2E antes)
Requer teste manual/E2E do fluxo afetado antes e depois.

- [x] **O3.1** DTOs/records públicos dentro de 11 controllers Java (`UserController:253-308`, `AuthController:301-313`, `SettingsController:170-178`, `CallRecordController:219-231`, `AccessGroupController:162-176`, `ReportController:258`, `UraQuestionController:82`, `SystemConfigController:89`, `AlertController:134`, `SuporteController:79`, `SettingsTestController:214`) — **[R] 🔴** padrão recorrente de falha de build. Extrair p/ arquivos próprios, em lotes por pacote, `mvnw compile` a cada lote. [Grupo D Java] (telecom:) — ✅ **fase 20**, 25 DTOs extraídos em 8 lotes por pacote; `SettingsTestController.TestResult` renomeado para `SettingsCheckResult`; `mvnw compile`+checkstyle+spotless limpos a cada lote; revisado por agente `java-reviewer` — aprovado sem achados. **Achado real do build de produção**: `mvn compile` (usado durante os lotes) não roda `test-compile`, então um teste (`AuthControllerTest.java`, referenciando `AuthController.LoginRequest` pelo caminho aninhado antigo) só quebrou no `docker compose up --build` real (que roda `mvn package`) — corrigido, suíte completa rodada (223 testes, 1 falha pré-existente e não relacionada em `ClientControllerTest`, confirmada via worktree no commit anterior à ONDA 3). Deployado e testado ao vivo em produção 2026-07-14: `POST /auth/login` (credenciais erradas → 401 com `ErrorResponse`), `GET /users`, `GET /access-groups`, `GET /settings` — todos 200, sem exceções nos logs.
- [x] **O3.2** `backend .../AsteriskConfigController.java:73-305` — **[R] 🔴** God controller (regex + I/O de arquivo + AMI). Extrair `AsteriskConfigService` (depois da O2.2). [Grupo C Java] (telecom:) — ✅ **fase 21**, `AsteriskConfigService` criado seguindo o padrão de `AsteriskAclService`; controller fica só com request/response/auditoria; `mvnw compile`+checkstyle+spotless limpos. Deployado e testado ao vivo em produção 2026-07-14: `GET /asterisk-config/tronco` retornou o bloco `[tronco-sip]` corretamente via `AsteriskConfigService.readTroncoBlock()`. `POST /tronco`/`POST /rotas` (reload real de config/AMI) não foram exercitados, mesmo critério de risco da fase 18.
- [x] **O3.3** ai-agent `flows/jira_call_flow.py` (708 linhas) — **[E] 🔴** God Object. Decompor em `SpeechFieldFormatter`, `CallRecorder`, `AudioCapture` + orquestrador fino. [Grupo C ai-agent — depois de O1.3/O2.1] (agents:) — ✅ **fase 22**, decomposto em `flows/speech_field_formatter.py`, `flows/audio_capture.py`, `flows/call_recorder.py` + orquestrador fino; corrigida junto a race do VAD singleton (instância por chamada). Revisado por agente `python-reviewer` — aprovado. Deployado e testado ao vivo em produção 2026-07-14 com **3 ligações reais** pelo softphone 9001→ramal 1000: 2 completaram o fluxo inteiro (saudação → 4 perguntas via STT/VAD → gravação WAV de ~57s → `POST /calls/register` 201) sem nenhuma exceção nos logs; a 1ª caiu cedo por queda de conexão pontual (sem erro registrado, caminho de degradação graciosa já existente antes do refactor).
- [x] **O3.4** `agents-platform/backend/executor.py` (971 linhas) — **[E] 🟡** 4 executors + orquestração. Quebrar em `executors/*.py` + `orchestrator.py`. Corrigir O1.5 junto. [Grupo C FastAPI] (agents:) — ✅ **fase 23**, decomposto em `executors/{ssh,web,log,database}_executor.py`+`common.py`+`__init__.py` + `orchestrator.py`; `executor.py` vira shim fino. Revisado por agente `python-reviewer` — aprovado. **Achado emergente durante o teste em produção**: `SSHTestExecutor`/`WebMonitorExecutor`/`LogMonitorExecutor` quebravam com `AttributeError` sempre que `rules` chegava como string JSON crua do banco (bug pré-existente idêntico ao original, confirmado via `git show`; só `DatabaseExecutor` já tratava isso) — corrigido nos 3 aplicando o mesmo `isinstance(rules, str)`+`json.loads` já usado no `DatabaseExecutor`. Deployado e testado ao vivo em produção 2026-07-14: criado e rodado um agente `web_monitor` real (1/1 OK após o fix) e um agente `database` real contra o Postgres interno (query real, contagem de URAs correta); `ssh_test`/`log_monitor` não puderam ser testados contra servidor SSH real (nenhum cadastrado no ambiente), mas usam o mesmo padrão de correção já validado. Agentes de teste removidos após validação.
- [x] **O3.5** Frontend arquivos gigantes: `ModuloURA.tsx` (1120), `ModuloConectividade.tsx` (1071), `Settings.tsx` (955), `Users.tsx` (818), `Softphone.tsx` (572). Extrair sub-componentes p/ arquivos próprios (padrão `AuthedAudio.tsx`), PRs separados por componente. [Grupo C React] (telecom:) — ✅ **fase 24**, 6 commits (1 por item do plano): (1) `AudioPlayer.tsx`+`KpiBar.tsx`; (2) `HistoricoModal.tsx`+`DashboardKPIs.tsx`+`connectivityHelpers.ts`; (3) `AsteriskFilePanel.tsx`; (4) `DashboardTab.tsx`+`RankingTab.tsx` (cluster de cards); (5) `TestModal.tsx` (com prop-drilling de form/setForm); (6) `CreateUserModal.tsx`+`EditUserModal.tsx`+`TotpModal.tsx`+`userModalTypes.ts`. Escopo confirmado só nesses 6 itens — aba "Chamadas"/bloco "config"/`Softphone.tsx` ficaram de fora, conforme decisão já registrada. `tsc -b`+`vite build` limpos a cada commit. Revisado por agente `react-reviewer` — aprovado sem achados. Deployado em produção 2026-07-14 (frontend healthy, bundles confirmados servindo o código novo, sem erros nos logs). Teste E2E via navegador não foi possível neste VPS (sem servidor X, mesma limitação já registrada na fase 19).

### 🧹 ONDA 4 — Housekeeping / estilo (baixo risco, oportunístico)
- [x] **O4.1** `backend StatsController.java:251-315` `buildRankingTrend` 10 params → objeto de contexto. (telecom:) — feito via records `RankingQueryContext`/`RankingCurrentResults`.
- [x] **O4.2** `backend CallRecordService.java:53-190` `registerCall` ~140 linhas → helpers privados. (telecom:) — `buildCallRecord`/`firstFieldValueMatching`/`applyJiraIntegration`/`notifyNewCall`.
- [x] **O4.3** FastAPI: `GET /alerts` duplicado (`executions.py:93-104` vs `reports.py:157-165`); `limit` sem teto (`reports.py:158,167`, `knowledge.py:62`); `system.py:96-108` `body: dict` → Pydantic; remover `database.py:188-196` (`get_db/release_db` mortos); HTML em `reports.py:56-155` → template. (agents:) — `fetch_recent_alerts` compartilhada, `le=500`/`le=50` nos limits, `RetentionConfigRequest`/`SecretRequest`, `routers/report_html.py` novo.
- [x] **O4.4** ai-agent: remover chamada HTTP morta `provider_registry.py:73-84`; `build_provider` match → registro por dict (OCP); UUID manual `protocol.py:50-57` → `uuid.UUID`; teto de sanidade em `protocol.py:60-87` `payload_length`; separar `except httpx.HTTPStatusError` (404) de erros reais nos flows. (agents:) — tudo feito; `MAX_SANE_PAYLOAD_LENGTH=8000`.
- [x] **O4.5** Frontend: helper único `getErrorMessage(e: unknown)` (substituir 25+ `catch (err: any)`); `key={i}` em `ModuloLogs.tsx:377,503`; tipar callbacks Recharts `Dashboard.tsx:225-357`. (telecom:) — feito. **Escopo reduzido em 2 pontos, por decisão própria de baixo risco**: `useReducer` para filtros de `ModuloURA.tsx` **não feito** — esses filtros pertencem à aba "Chamadas", já excluída explicitamente do escopo da ONDA 3 por decisão registrada (maior superfície/risco, `handleDrillDown` cruza abas); "mover interfaces locais p/ `api/types.ts`" **não feito** — auditoria não achou nenhuma interface duplicada entre componentes (nomes únicos, sem DRY real a resolver), mover interfaces só-locais seria churn sem ganho.

---

## Débito conhecido/aceito (NÃO refatorar sem decisão de produto)
- Senha SIP `VITE_SIP_PASSWORD` no bundle público (`Softphone.tsx:38-44`) — credencial estática compartilhada, já aceito no CLAUDE.md.
- Access token JWT em `localStorage` (`client.ts:25-27`) — resíduo parcialmente aceito (refresh já em cookie httpOnly).
- Fallback admin via env var (`AuthController.java:43-47`) — compatibilidade retroativa, risco aceito.
- `extensionPasswordFor()` devolve fórmula fictícia (`UserController.java:310-312`) — pendência conhecida.

## Tabelas de achados completas (por módulo)
Ver transcrições dos agentes de auditoria. Resumo de contagem: Java 14 · React 19 · FastAPI 15 · ai-agent 16 = **64 achados**.

---

## Progresso
- Fase 1 (auditoria): ✅ concluída 2026-07-13
- Fase 2 (execução): 🔄 em andamento
  - ✅ **Fase 15 concluída 2026-07-13** — ONDA 0 completa (O0.1 + O0.2), deployado em produção, testado com JWTs forjados (ADMIN/USER com e sem claim perm), agentes de teste removidos após validação.
  - ✅ **Fase 16 concluída 2026-07-13** — ONDA 1 completa (O1.1 a O1.7, 4 módulos), deployado em produção, testado (Java 404, agente `database` real no FastAPI); 2 bugs pré-existentes emergentes corrigidos (`no_targets`/`report_json` recebiam string JSON crua sem `json.loads`); O1.3/O1.5 validados por leitura de código + `py_compile` (sem tráfego de voz/SSH real disponível para teste ao vivo).
  - ✅ **Fase 17 concluída 2026-07-13** — ONDA 2, parte ai-agent (O2.1, O2.3, O2.4): `GeminiService` morta deletada, helpers movidos p/ `providers/gemini_shared.py`, utilitários de áudio unificados em `providers/audio_utils.py`, padrão "falar e aguardar" extraído p/ `protocol.py` (corrige o desvio do zabbix_alert_flow que não drenava o reader). Testado ao vivo em produção via `docker exec` (LLM e TTS reais funcionando; STT bateu 503 transitório da API do Gemini, não relacionado à mudança).
  - ✅ **Fase 18 concluída e deployada em 2026-07-14** — ONDA 2, parte Java (O2.2 + O2.6): `AmiSession` unifica o protocolo AMI usado em 4 pontos (`AsteriskAmiClient`, `StatsTrunkAmiClient`, `AmiOriginateService`, `AsteriskConfigController.amiReload`); `StatsCallRepository` consolida o WHERE/escopo-por-BU repetido em 5 queries de ranking via constantes `static final String` no `@Query` (sem migrar para Criteria API, por decisão já registrada no plano). Revisão de código encontrou e corrigiu um vazamento de socket introduzido pelo próprio refactor (`AmiSession.connect()` não fechava o socket se a leitura do banner do AMI falhasse). `mvnw compile` + checkstyle + spotless limpos. Deployado (`docker compose up -d --build backend`) e testado ao vivo em produção: login AMI real via `/logs/asterisk/status` e `/stats/trunk-status` (tronco `ONLINE`, `rttMs: 20`), e as 5 queries de ranking consolidadas via `/stats/calls/ranking` com dados reais — sem exceções nos logs. `POST /asterisk-config/{tronco,rotas}` (reload de config real) e originar chamada real via `AmiOriginateService` não foram exercitados por serem ações com efeito colateral em produção.
  - ✅ **Fase 19 concluída e deployada em 2026-07-14** — ONDA 2, parte React (O2.5): hook `useAuthSession()`/`authSessionFromToken()` consolidando a leitura de token+role+perms repetida em `App.tsx`, `Operadoras.tsx`, `Cadastro0800.tsx`, `Linhas.tsx`; `decodeTokenPayload` exportado de `client.ts` e reusado por `Softphone.tsx`. Build de produção (`tsc -b && vite build`) limpo. Deployado (`docker compose up -d --build frontend`, healthy). Teste E2E via navegador não foi possível neste VPS (sem `DISPLAY`) — validado por reprodução em Node do algoritmo real contra JWT forjado (ADMIN `hasWrite` true, USER sem perm false, extensão do Softphone decodificada certo) + confirmação de que o bundle novo servido em produção contém a lógica consolidada.
  - **ONDA 2 completa** (O2.1 a O2.6, todos os 4 módulos).
  - **Plano detalhado da ONDA 3 aprovado 2026-07-14** (ver seção própria abaixo), com 4 decisões do usuário já registradas.
  - ✅ **Fases 20+21 concluídas e deployadas em 2026-07-14** — ONDA 3, Grupo Java (O3.1 + O3.2): 25 DTOs extraídos de 11 controllers em 8 lotes + `SettingsCheckResult` renomeado; `AsteriskConfigService` extraído. Corrigido 1 teste quebrado (`AuthControllerTest`) que só apareceu no build real (`mvn package`, não `mvn compile`). Suíte completa: 223 testes, 1 falha pré-existente não relacionada. Deployado e testado ao vivo (`/auth/login`, `/users`, `/access-groups`, `/settings`, `/asterisk-config/tronco`).
  - ✅ **Fase 22 concluída, deployada e testada em 2026-07-14** — ONDA 3, Grupo ai-agent (O3.3): `jira_call_flow.py` decomposto, race do VAD corrigida. Validado com 3 ligações reais em produção (2/3 completaram o fluxo inteiro, sem exceções).
  - ✅ **Fase 23 concluída, deployada e testada em 2026-07-14** — ONDA 3, Grupo FastAPI (O3.4): `executor.py` decomposto; achado emergente (rules como string JSON crua) corrigido em 3 dos 4 executors. Validado ao vivo (web_monitor + database reais).
  - ✅ **Fase 24 concluída, deployada em 2026-07-14** — ONDA 3, Grupo React (O3.5): 6 sub-componentes extraídos em 6 commits. Revisado por agente `react-reviewer`, aprovado. Deployado em produção.
  - **🎉 ONDA 3 completa** (O3.1 a O3.5, todos os 4 módulos: Java, ai-agent, FastAPI, React). Fases 20-24.
  - ✅ **Fase 25 concluída em 2026-07-14** — ONDA 4 completa (O4.1-O4.5, todos os 4 módulos), 1 commit por módulo. Revisado por 4 agentes de review em paralelo (java-reviewer, 2x python-reviewer, react-reviewer) — nenhum CRITICAL/HIGH; 1 LOW real corrigido (cast morto em `Users.tsx` após adotar `getErrorMessage`), demais eram nuances cosméticas/informativas sem regressão. `mvn compile`+`test-compile`, `py_compile`, `tsc -b`+`vite build`+`eslint` (baseline de erros pré-existentes preservado, sem regressão) limpos.
  - **🎉 ONDA 4 completa — roadmap de refatoração 100% executado** (ONDA 0 a ONDA 4, fases 15-25).
- Convenção de numeração: ONDA 0 = fase 15 (O0.1 + O0.2, agents-platform); próximas ondas continuam a sequência (fase 16, 17...) por commit atômico, não necessariamente 1 commit por onda inteira.

---

## Plano detalhado — ONDA 0 (aprovado, aguardando execução)

**Módulo:** `agents-platform/backend` (FastAPI) · **Commits:** `agents:`

### Diagnóstico confirmado
- **O0.1** — `routers/agents.py:56-65` (`_rules_has_ssh_exec`) só inspeciona `check["cmd"]`/`check["fix_cmd"]`. `executor.py:648-705` (`DatabaseExecutor`) executa `check["dsn"]`+`check["query"]` via `asyncpg.connect`+`fetchrow` sem gate equivalente. Não-ADMIN com `PERM_WRITE_agents.agents` cria agente `type=database` e roda SQL arbitrário em qualquer DSN alcançável na rede Docker.
- **O0.2** — `main.py:33` (`_PUBLIC_PREFIX` inclui `/docs`,`/openapi`) + `main.py:78` (`FastAPI(...)` sem desabilitar docs). Schema completo exposto sem JWT em produção.

### Mudanças propostas
- **O0.1**: em `routers/agents.py`, adicionar `_rules_has_db_exec(rules)` (True se algum check tiver `dsn` ou `query`). Estender `_require_admin_for_ssh_exec` → `_require_admin_for_privileged_exec` para barrar quando `_rules_has_ssh_exec(rules) or _rules_has_db_exec(rules)` e role ≠ ADMIN, com mensagem 403 distinta para o caso database. Chamadas em `create_agent:103` e `update_agent:130` inalteradas.
- **O0.2**: em `main.py`, remover `/docs`/`/openapi` de `_PUBLIC_PREFIX` (linha 33); passar `docs_url`/`redoc_url`/`openapi_url` condicionados a `AGENTS_ENABLE_DOCS` (default desligado) em `FastAPI(...)` (linha 78).

### Testes de aceitação
- ADMIN cria agente `database` → 200. Não-ADMIN cria agente `database` → **403**. Não-ADMIN cria `web_monitor` sem dsn/query → 200 (não regride). SSH cmd por não-ADMIN → 403 (preservado).
- `curl /agents/api/docs` e `/agents/api/openapi.json` sem JWT → 404/401.
- Validação: `python -m py_compile routers/agents.py main.py` + `docker compose up -d --build agents-api` + `docker compose ps`.

### Riscos e mitigação
Quebrar criação legítima por ADMIN (mitigado por teste explícito) · front dependendo de `/docs` (não há link no NAV) · falso positivo do gate (checa presença real de dsn/query, não só a chave).

### Checklist de aceitação da ONDA 0
- [ ] Não-ADMIN não cria/edita agente `database`; ADMIN continua criando
- [ ] `/docs` e `/openapi.json` retornam 404/401 sem JWT
- [ ] `py_compile` limpo, container `agents-api` healthy
- [ ] Entrada em `frontend/src/data/releases.ts`
- [ ] Marcar O0.1/O0.2 como `[x]` nas Ondas de execução acima

---

## Plano detalhado — ONDA 3 (aprovado em 2026-07-14, aguardando execução)

**Decisões tomadas** (respondem as 4 perguntas no fim desta seção): (1) `SettingsTestController.TestResult` renomeia para `SettingsCheckResult` ao extrair; (2) a race do VAD singleton em `jira_call_flow.py` é corrigida junto da O3.3 (instanciar por chamada, em vez de singleton de módulo); (3) `executor.py` vira shim fino reexportando `run_agent`/`_build_ssh_kwargs` após a O3.4; (4) escopo da O3.5 confirmado só nos itens 1-6 (baixo/médio risco) — aba "Chamadas" do ModuloURA, bloco "config" do Settings e `Softphone.tsx` ficam de fora desta onda.

Gerado em 2026-07-14 por 5 pesquisas paralelas (uma por item), leitura completa dos arquivos envolvidos. Maior esforço/risco que ONDA 0-2 — decomposição de God classes/God objects/God files em 4 módulos. Cada item é um commit isolado; ordem sugerida vai do menor pro maior risco dentro de cada módulo.

**Convenção de grupos** (paralelizável entre módulos, sequencial dentro de cada um): Grupo Java (O3.1 → O3.2), Grupo ai-agent (O3.3), Grupo FastAPI (O3.4), Grupo React (O3.5).

### O3.1 — DTOs em 11 controllers Java

**Módulo:** `backend` (Java) · **Commit:** `telecom:`

**Diagnóstico confirmado:** 11 controllers declaram DTOs (`record`/`static class`) aninhados, todos `public`, nenhum usado fora do próprio controller (confirmado por grep). Nenhuma pasta `dto/` existe hoje em nenhum pacote — convenção do repo é arquivo irmão flat no mesmo pacote, sem subpasta.

| Controller (pacote) | DTOs (nome) | Observação |
|---|---|---|
| `config.AuthController` | `LoginRequest`, `LoginResponse`, `ErrorResponse`, `StreamingTokenResponse` | Sem particularidade |
| `domain.ura.UraQuestionController` | `UraQuestionResponse` | Tem factory estático `from(UraQuestion)` |
| `domain.accessgroup.AccessGroupController` | `GroupRequest`, `PermissionEntry`, `GroupResponse`, `ErrorResponse` | `GroupRequest`/`GroupResponse` referenciam `PermissionEntry` — ok, mesmo pacote |
| `domain.alert.AlertController` | `UpdateStatusRequest` | Sem particularidade |
| `domain.pedido.SuporteController` | `AbrirProtocoloRequest` | Sem particularidade |
| `domain.settings.SettingsController` | `SuccessResponse`, `ErrorResponse`, `ApplyStartResponse`, `ApplyStatusResponse`, `HistoryEntryDTO` | Sem particularidade |
| `domain.settings.SettingsTestController` | `TestResult` (record local: `boolean success, String message`) → **renomear para `SettingsCheckResult`** ao extrair | Já existe entidade JPA `domain.connectivity.TestResult` (Módulo 2, usada em 10 arquivos) — pacotes diferentes, não colide em compilação, mas nome duplicado confunde. **Decisão tomada**: renomear. Precisa atualizar todos os usos do tipo dentro do próprio `SettingsTestController.java` (retorno de método, variável local) — o nome só é referenciado ali, sem impacto em outros arquivos |
| `domain.report.ReportController` | `ConnectivitySummaryDTO` | É `static class` (não record), campos públicos mutáveis, **construtor package-private** (sem modificador) — preservar essa visibilidade exata ao mover, não promover a `public` sem necessidade |
| `domain.call.CallRecordController` | `RegisterCallRequest`, `RegisterCallResponse` | Sem particularidade |
| `domain.config.SystemConfigController` | `ConfigDTO` | Sem particularidade |
| `domain.user.UserController` | `CreateUserRequest`, `UpdateUserRequest`, `UserResponse` (factory `from(AppUser)`, referencia `BusinessUnit`), `ErrorResponse`, `ExtensionPasswordResponse` | `UserResponse.java` precisa do import `com.asteriskia.domain.masterdata.BusinessUnit` |

**Mudanças propostas:** extrair cada DTO para um arquivo próprio no mesmo pacote do controller de origem (sem subpasta `dto/`, seguindo a convenção atual do repo). `ErrorResponse` aparece em 4 pacotes diferentes (Auth/AccessGroup/Settings/User) — cada um vira um top-level distinto no seu próprio pacote, sem colisão.

**Lotes de execução (compilar a cada lote com `mvnw compile` via container Maven):**
1. `config` (AuthController — 4 DTOs)
2. `domain.settings` (SettingsController + SettingsTestController juntos — 6 DTOs; `TestResult` extraído já como `SettingsCheckResult.java`)
3. `domain.user` (UserController — 5 DTOs, atenção ao import de `BusinessUnit`)
4. `domain.accessgroup` (AccessGroupController — 4 DTOs)
5. `domain.call` (CallRecordController — 2 DTOs)
6. `domain.report` (ReportController — 1 classe, preservar construtor package-private)
7. `domain.ura` (UraQuestionController — 1 DTO com factory method)
8. Lote final "resto": `domain.config` + `domain.alert` + `domain.pedido` (1 DTO cada)

**Testes de aceitação:** `mvnw compile` limpo a cada lote (via `docker run --rm -v /opt/VoipIA:/app -v $HOME/.m2:/root/.m2 -w /app/backend maven:3.9-eclipse-temurin-21 mvn -q -o compile`); checkstyle+spotless limpos; smoke test com JWT forjado em pelo menos 1 endpoint por controller tocado (login, criar URA question, criar grupo de acesso, atualizar status de alerta, etc) para confirmar que o (de)serialization JSON continua idêntico.

**Riscos:** baixo em quase todos — extração mecânica, sem mudança de lógica. Único ponto de atenção real: preservar a visibilidade exata do construtor de `ConnectivitySummaryDTO` (package-private) e os imports de factory methods (`UraQuestionResponse.from`, `UserResponse.from`).

---

### O3.2 — `AsteriskConfigController` → `AsteriskConfigService`

**Módulo:** `backend` (Java) · **Commit:** `telecom:` · **Depende de:** O2.2 (concluída, fase 18)

**Diagnóstico confirmado:** o controller (290 linhas) mistura 5 responsabilidades: endpoints REST, parsing/regex de seção pjsip (`extractSection`/`replaceSection`), I/O de arquivo com escrita atômica (`readFile`/`writeFile`), mapeamento pjsip→env (`extractEnvFromPjsip`), protocolo AMI via `AmiSession` (`amiReload`, já usa a classe da O2.2). Nenhum outro arquivo referencia a classe além do wiring do Spring. Sem testes automatizados existentes (validação será manual via curl, como na fase 18).

**Precedente direto no projeto:** `SecurityController` (908→390 linhas) já foi extraído em `AsteriskAclService`/`FailToBanClient`/`JailConfigRepository`/`SecurityFileUtils` — os `@Service` extraídos usam `@Value` próprio, **não recebem `HttpServletRequest`**, e lançam `IOException` normalmente; o controller mantém `Authentication`/`HttpServletRequest`/`auditService.log(...)`. Seguir o mesmo padrão aqui.

**Mudanças propostas:** novo `AsteriskConfigService` (mesmo pacote `domain.settings`), `@Service`, com `@Value` próprios (`configDir`, `amiHost/Port/User/Password`):
- `String readTroncoBlock()` — `readFile`+`extractSection`
- `Map<String,String> saveTronco(String block)` — `readFile`+`replaceSection`+`writeFile`+`extractEnvFromPjsip`, retorna `envUpdates` (controller decide se chama `settingsService.writeSettings`, que precisa de `auth`/IP da request)
- `String reloadPjsip()` / `String reloadDialplan()` (ou manter `amiReload(String command)` genérico — decisão de nome, baixo risco)
- `String readRotas()` / `void saveRotas(String content)`
- privados: `extractSection`, `replaceSection`, `extractEnvFromPjsip`, `readFile`, `writeFile`

Controller fica só com: os 4 `@*Mapping`, `try/catch(IOException)` + `auditService.log(...)` + `settingsService.writeSettings(...)` + `extractIp` (helper de request). **Assinaturas públicas dos endpoints não mudam.**

**Testes de aceitação:** `GET/POST /api/v1/asterisk-config/{tronco,rotas}` continuam com o mesmo shape de JSON; `POST /tronco` continua atualizando `.env` e disparando `module reload res_pjsip`; `POST /rotas` continua disparando `dialplan reload`; escrita atômica (`.tmp`+`ATOMIC_MOVE`) preservada; `mvnw compile` limpo. **Atenção**: `POST /tronco` e `POST /rotas` disparam reload real via AMI em produção — mesmo cuidado da fase 18 (testar só os GETs livremente; os POSTs exigem confirmação antes de rodar em produção, já que reescrevem config real e podem bater no tronco/dialplan ao vivo).

**Riscos:** baixo — extração mecânica sem mudança de lógica. Nenhum import circular esperado (o novo service não depende de `SettingsService`/`AuditService`).

---

### O3.3 — Decomposição de `jira_call_flow.py`

**Módulo:** `ai-agent` (Python) · **Commit:** `agents:` · **Depende de:** O1.3, O2.1, O2.3, O2.4 (concluídas, fases 16-17)

**Diagnóstico confirmado:** arquivo real tem 667 linhas (era 708 antes das fases 16/17). Estrutura:
- **`SpeechFieldFormatter`** (baixo risco — tudo `@staticmethod`, sem estado, sem I/O): `_NOISE_PATTERNS`, `_NUMBER_WORDS`, `_build_stt_hint()`, `_normalize_transcription()`, `_matches_expected()`. Sem dependências externas — migração trivial.
- **`AudioCapture`** (risco médio — hot-path de voz): `_is_speech_frame()`, `_resolve_vad_aggressiveness()`, `_trim_silence()` (funções de módulo) + `_capture_audio()`/`_listen_and_transcribe()` (métodos de instância). Dependências: `self.reader`, `self.ai` (STT), `self.call_uuid`, `self._recorded_audio` (acumula pra WAV — precisa ser injetado/retornado por referência, não copiado).
- **`CallRecorder`** (risco médio — toca Jira/backend/disco): `_write_wav()`, `_create_jira_issue()`, `_guess_call_type()`, `_classify_subject()`, `_format_issue_key()`. Dependências: `self.ai`, `self.call_uuid`, `self.collected_answers`, `self._transcriptions`, `self.caller_number`, `self.ura_id`.
- **Orquestrador fino remanescente**: `__init__`, `execute()`, `_speak()`/`_play_cached()` (TTS/cache playback — ficam no orquestrador, dependem de `self.writer`/`self.reader`/`self.ai` diretamente), `_ask_question()` (orquestra formatter+capture), `_fetch_settings()`/`_fetch_questions()` (wrappers finos de `bc.get()`, não encaixam em nenhuma das 3 classes acima).

`zabbix_alert_flow.py` (116 linhas) não precisa da mesma decomposição — é um flow de 1 passo, consistente com só `jira_call_flow.py` estar no roadmap.

**Achado colateral, corrigido junto por decisão do usuário:** `_vad = webrtcvad.Vad(...)` é um **singleton de módulo** mutado via `_vad.set_mode()` a cada chamada, com o valor configurável por URA. Duas ligações simultâneas fazem `set_mode()` de uma pisar no modo da outra — **race condition pré-existente**, não introduzida por este refactor. **Decisão tomada**: instanciar `webrtcvad.Vad()` por chamada (dentro de `AudioCapture`, um por instância) em vez de singleton de módulo, eliminando a race enquanto o arquivo já está sendo mexido.

**Mudanças propostas:** criar `flows/speech_field_formatter.py`, `flows/audio_capture.py`, `flows/call_recorder.py` (ou módulo único `flows/jira_call_flow_helpers.py` se preferir menos arquivos — decisão de granularidade, baixo risco); `jira_call_flow.py` vira o orquestrador fino compondo as 3 classes via injeção no `__init__`.

**Riscos concretos:** (a) qualquer mudança na ordem de `await` dentro de `execute()`/`_ask_question()` quebra o timing real de voz (já teve bugs de `CancelledError`/task ref); (b) `self._recorded_audio` é lista mutável compartilhada — as classes extraídas precisam receber a mesma referência (composição, não cópia), senão o WAV final fica incompleto; (c) trocar o VAD de singleton pra instância por chamada precisa confirmar que `webrtcvad.Vad()` não tem custo de inicialização caro (é so um wrapper C leve, risco baixo) e que `set_mode()` continua chamado no momento certo do fluxo.

**Testes de aceitação:** `py_compile` de todos os arquivos novos; chamada de teste real fim-a-fim (boas-vindas → pergunta com campo de ramal/telefone via STT+normalização → confirmação → grava WAV → abre chamado Jira) via `docker exec`/ligação real; conferir que o WAV final contém a voz da URA e do cliente na ordem certa.

---

### O3.4 — Decomposição de `executor.py`

**Módulo:** `agents-platform/backend` (FastAPI) · **Commit:** `agents:` · **Depende de:** O1.5 (concluída, fase 16), ONDA 0 (concluída, fase 15)

**Diagnóstico confirmado:** arquivo real tem 1000 linhas. Estrutura: helpers de módulo compartilhados (`_build_ssh_kwargs`, `log`, `memory_recall`, `memory_save`, `ai_fallback`) + **4 executors** (`SSHTestExecutor`, `WebMonitorExecutor`, `LogMonitorExecutor`, `DatabaseExecutor` — a docstring do módulo está desatualizada e só lista 3) + dispatcher `EXECUTORS` + estado global (`_running_agents`, `_background_tasks`, corrigido na fase 16/O1.3) + orquestração (`_spawn_background_task`, `_send_all_alerts`, `_apply_retention`, `_calc_next_run`, `run_agent`).

**Import surface real** (grep no repo inteiro): só 3 símbolos são importados de fora — `scheduler.py` usa `run_agent`; `routers/servers.py` usa `_build_ssh_kwargs`; `routers/system.py` usa `_apply_retention` (import local). Nenhum router importa as classes `*Executor` ou `EXECUTORS` diretamente.

**O gate de segurança da ONDA 0 mora em `routers/agents.py`, não em `executor.py`** — a decomposição não toca nesse gate, só precisa preservar o formato `rules.checks[].dsn/query` que o `DatabaseExecutor` consome (o gate depende desse mesmo shape).

**Mudanças propostas:**
```
executors/
  __init__.py          # reexporta EXECUTORS, _build_ssh_kwargs (mantém import externo idêntico)
  common.py            # _build_ssh_kwargs, log, memory_recall, memory_save, ai_fallback
  ssh_executor.py       # SSHTestExecutor
  web_executor.py       # WebMonitorExecutor
  log_executor.py       # LogMonitorExecutor
  database_executor.py  # DatabaseExecutor
orchestrator.py         # _spawn_background_task, _send_all_alerts, _apply_retention,
                         # _calc_next_run, run_agent, _running_agents, _background_tasks, EXECUTORS
```
`executor.py` vira shim fino (`from orchestrator import run_agent; from executors import _build_ssh_kwargs`) reexportando os 3 símbolos usados externamente — evita tocar `scheduler.py`/`routers/servers.py`/`routers/system.py`. **Decisão tomada**: manter o shim fino (menor diff, os 3 import sites não precisam mudar).

**Riscos concretos:** (1) `_running_agents`/`_background_tasks` são estado de módulo — precisa haver **uma única fonte** desse estado (em `orchestrator.py`), nunca duplicado; (2) `DatabaseExecutor` tem tratamento de exceção sensível a segurança (regex de redação de DSN) — preservar exatamente; (3) `run_agent` roda SSH/queries/HTTP reais contra servidores monitorados — erro de import só aparece em runtime na primeira execução pós-deploy, não em `py_compile`; (4) cada `executors/*.py` deve importar só o que usa (evitar `import asyncssh` desnecessário em `database_executor.py`, por exemplo).

**Testes de aceitação:** criar/rodar via API um agente de cada tipo (`ssh_test`, `web_monitor`, `log_monitor`, `database`) e conferir execução+relatório; testar auto-fix com 2+ servidores (revalida O1.5); testar agente `type=database` como não-ADMIN (revalida gate da ONDA 0, mesmo não estando neste arquivo); `python -m py_compile` em todos os arquivos novos.

---

### O3.5 — Extração de sub-componentes React

**Módulo:** `frontend` (React) · **Commit:** `telecom:` · **1 componente por commit, conforme pede o item do roadmap**

**Diagnóstico confirmado** (linhas reais via `wc -l`, roadmap desatualizado): `ModuloURA.tsx` 1133, `ModuloConectividade.tsx` 1075, `Settings.tsx` 955, `Users.tsx` 818, `Softphone.tsx` 576.

**Achado principal**: 3 dos 5 arquivos já estão logicamente decompostos, só não fisicamente — `ModuloURA.tsx`, `ModuloConectividade.tsx` e `Settings.tsx` já declaram funções de componente top-level separadas dentro do mesmo arquivo, com props próprias, sem prop-drilling do pai (o padrão de `AuthedAudio.tsx`, só que ainda no mesmo arquivo). Mover cada uma pro próprio arquivo é mecânico e de baixo risco.

**Ordem sugerida (menor → maior risco), 1 componente por commit:**
1. `AudioPlayer.tsx`, `KpiBar.tsx` (de `ModuloURA.tsx`) — isolados, sem estado externo
2. `HistoricoModal.tsx`, `DashboardKPIs.tsx` (de `ModuloConectividade.tsx`) — isolados
3. `AsteriskFilePanel.tsx` (de `Settings.tsx`) — isolado
4. `DashboardTab.tsx`, `RankingTab.tsx` + cluster de cards (`CardSkeleton`, `RankingCard`, `AvgDurationCard`, `DraggableCard`) (de `ModuloURA.tsx`) — isolados mas maiores
5. Modal "novo/editar teste" (de `ModuloConectividade.tsx`) — precisa de props/handlers (acoplado a `form`/`showModal` do pai)
6. Modais de `Users.tsx` (Criar/Editar/Gerenciar 2FA) — sem pré-decomposição existente; precisa de hook `useUserModals` ou prop-drilling extenso (`form`, `editingUser`, `saving`, `totpModalUser`, `qrCode`, `secret`)

**Fora de escopo nesta onda (confirmado pelo usuário)**: aba "Chamadas" de `ModuloURA.tsx` (12+ estados de filtro entrelaçados — exigiria hook `useCallsFilters`, não um componente simples), bloco `activeTab === 'config'` de `Settings.tsx` (dezenas de variáveis de estado do pai), e `Softphone.tsx` inteiro (máquina de estado WebRTC/JsSIP, fluxo crítico validado ao vivo em produção — quase todo o arquivo é lógica de sessão SIP interdependente, pouco JSX presentational solto pra extrair com segurança). Fica pra uma rodada futura, quando fizer mais sentido (ex: com testes E2E cobrindo antes).

**Testes de aceitação:** `tsc -b && vite build` limpo a cada commit; smoke test manual da aba/seção afetada (visualmente, já que não há Playwright configurado neste projeto); para os itens 5 e 6 (que envolvem modais com submit), testar o fluxo de criar/editar completo antes de considerar concluído.

---

### Decisões registradas (todas as 4 perguntas do plano, respondidas pelo usuário em 2026-07-14)

1. **O3.1** — `SettingsTestController.TestResult` renomeia para `SettingsCheckResult` ao extrair.
2. **O3.3** — a race do VAD singleton é corrigida junto (instanciar `webrtcvad.Vad()` por chamada).
3. **O3.4** — `executor.py` vira shim fino reexportando os 3 símbolos usados externamente.
4. **O3.5** — escopo confirmado só nos itens 1-6; aba "Chamadas"/bloco "config"/`Softphone.tsx` ficam fora desta onda.
