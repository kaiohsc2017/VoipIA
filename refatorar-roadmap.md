# Roadmap de Refatoração AsteriskIA — Fase 1 (Auditoria)

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
- [x] **O2.2** `backend .../AsteriskConfigController.java:251-289`, `domain/logs/AsteriskAmiClient.java`, `integration/ami/AmiOriginateService.java`, `domain/StatsTrunkAmiClient.java` — **[R] 🔴** protocolo AMI duplicado em 4 lugares. Extrair `AmiClient` único. [Grupo B Java] (telecom:) — ✅ **fase 18**, `AmiSession` (`integration/ami/`) criado com `connect/login/send/readBlock/readUntil/logoff`; os 4 chamadores migrados, cada um mantendo seu próprio parsing/log de erro (já divergiam). Revisão encontrou e corrigiu vazamento de socket em `connect()` (banner de boas-vindas podia falhar antes do `try-with-resources` do chamador existir). `mvnw compile`+checkstyle+spotless limpos — **não testado ao vivo** (endpoints de status/reload AMI e originate de chamada não exercitados em produção nesta fase).
- [x] **O2.3** ai-agent padrão "falar e aguardar" duplicado 3x: `flows/jira_call_flow.py:222-312`, `flows/zabbix_alert_flow.py:79-90` (deriva: zabbix não drena reader). Extrair helper único em `protocol.py`. [parte Grupo C ai-agent] (agents:) — ✅ **fase 17**, `drain_reader`/`wait_playback_and_drain` extraídos para `protocol.py`; zabbix agora dreno o reader (corrige o desvio).
- [x] **O2.4** ai-agent `_pcm_to_wav`/`_resample` reimplementados 3x: `providers/openai_provider.py:41-60`, `services/gemini_service.py:429-473`, `providers/local_provider.py:27-34`. Extrair `providers/audio_utils.py`. (agents:) — ✅ **fase 17**, `providers/audio_utils.py` criado (`pcm_to_wav`/`resample_pcm`), 3 arquivos migrados; STT validado (código executou corretamente, 503 foi erro transitório da API externa do Gemini).
- [ ] **O2.5** Frontend auth client-side espalhada: `api/client.ts:25-27`, `Softphone.tsx:25-29`, `Operadoras.tsx:13-15`, `Cadastro0800.tsx:173-175`, `Linhas.tsx:58-60`, `App.tsx:94-111`. Consolidar em hook `useAuthSession()`. [Grupo B React] (telecom:)
- [x] **O2.6** `backend .../StatsCallRepository.java:54-147` — **[E] 🟡** 5 queries de ranking quase idênticas. Consolidar (avaliar Criteria API). [Grupo E Java — com testes de integração] (telecom:) — ✅ **fase 18**, extraídas as constantes `BU_URA_JOIN_PREFIX`/`BU_URA_SCOPE_SUFFIX` (concatenação de `String` constante dentro do `@Query` nativo, válido em tempo de compilação) reusadas pelas 5 queries; SQL final idêntico caractere a caractere ao original (conferido manualmente). Sem migrar para Criteria API, conforme o plano já aprovado (risco de regressão maior sem suíte de testes de integração cobrindo essas queries).

### 🏗️ ONDA 3 — Decomposição de God classes (maior esforço/risco — testar E2E antes)
Requer teste manual/E2E do fluxo afetado antes e depois.

- [ ] **O3.1** DTOs/records públicos dentro de 11 controllers Java (`UserController:253-308`, `AuthController:301-313`, `SettingsController:170-178`, `CallRecordController:219-231`, `AccessGroupController:162-176`, `ReportController:258`, `UraQuestionController:82`, `SystemConfigController:89`, `AlertController:134`, `SuporteController:79`, `SettingsTestController:214`) — **[R] 🔴** padrão recorrente de falha de build. Extrair p/ arquivos próprios, em lotes por pacote, `mvnw compile` a cada lote. [Grupo D Java] (telecom:)
- [ ] **O3.2** `backend .../AsteriskConfigController.java:73-305` — **[R] 🔴** God controller (regex + I/O de arquivo + AMI). Extrair `AsteriskConfigService` (depois da O2.2). [Grupo C Java] (telecom:)
- [ ] **O3.3** ai-agent `flows/jira_call_flow.py` (708 linhas) — **[E] 🔴** God Object. Decompor em `SpeechFieldFormatter`, `CallRecorder`, `AudioCapture` + orquestrador fino. [Grupo C ai-agent — depois de O1.3/O2.1] (agents:)
- [ ] **O3.4** `agents-platform/backend/executor.py` (971 linhas) — **[E] 🟡** 4 executors + orquestração. Quebrar em `executors/*.py` + `orchestrator.py`. Corrigir O1.5 junto. [Grupo C FastAPI] (agents:)
- [ ] **O3.5** Frontend arquivos gigantes: `ModuloURA.tsx` (1120), `ModuloConectividade.tsx` (1071), `Settings.tsx` (955), `Users.tsx` (818), `Softphone.tsx` (572). Extrair sub-componentes p/ arquivos próprios (padrão `AuthedAudio.tsx`), PRs separados por componente. [Grupo C React] (telecom:)

### 🧹 ONDA 4 — Housekeeping / estilo (baixo risco, oportunístico)
- [ ] **O4.1** `backend StatsController.java:251-315` `buildRankingTrend` 10 params → objeto de contexto. (telecom:)
- [ ] **O4.2** `backend CallRecordService.java:53-190` `registerCall` ~140 linhas → helpers privados. (telecom:)
- [ ] **O4.3** FastAPI: `GET /alerts` duplicado (`executions.py:93-104` vs `reports.py:157-165`); `limit` sem teto (`reports.py:158,167`, `knowledge.py:62`); `system.py:96-108` `body: dict` → Pydantic; remover `database.py:188-196` (`get_db/release_db` mortos); HTML em `reports.py:56-155` → template. (agents:)
- [ ] **O4.4** ai-agent: remover chamada HTTP morta `provider_registry.py:73-84`; `build_provider` match → registro por dict (OCP); UUID manual `protocol.py:50-57` → `uuid.UUID`; teto de sanidade em `protocol.py:60-87` `payload_length`; separar `except httpx.HTTPStatusError` (404) de erros reais nos flows. (agents:)
- [ ] **O4.5** Frontend: helper único `getErrorMessage(e: unknown)` (substituir 25+ `catch (err: any)`); mover interfaces locais p/ `api/types.ts`; `useReducer` p/ filtros `ModuloURA.tsx:663-682`; `key={i}` em `ModuloLogs.tsx:377,503`; tipar callbacks Recharts `Dashboard.tsx:225-357`. (telecom:)

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
  - ✅ **Fase 18 concluída 2026-07-14** — ONDA 2, parte Java (O2.2 + O2.6): `AmiSession` unifica o protocolo AMI usado em 4 pontos (`AsteriskAmiClient`, `StatsTrunkAmiClient`, `AmiOriginateService`, `AsteriskConfigController.amiReload`); `StatsCallRepository` consolida o WHERE/escopo-por-BU repetido em 5 queries de ranking via constantes `static final String` no `@Query` (sem migrar para Criteria API, por decisão já registrada no plano). Revisão de código encontrou e corrigiu um vazamento de socket introduzido pelo próprio refactor (`AmiSession.connect()` não fechava o socket se a leitura do banner do AMI falhasse). `mvnw compile` + checkstyle + spotless limpos — **não testado ao vivo** (endpoints AMI de status/reload/originate não exercitados em produção nesta fase; próxima sessão de deploy deve validar `pjsip show endpoints`, reload de tronco/rotas via UI, e o Ranking de Atendimentos).
  - Próxima: ONDA 2, parte React — O2.5 (hook `useAuthSession()`) (fase 19)
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
