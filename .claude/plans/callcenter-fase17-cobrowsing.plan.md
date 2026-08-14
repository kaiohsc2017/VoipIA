# Plano — Fase 17 do Call Center: co-browsing gravado do chat

**Plano-mãe:** `.claude/plans/modulo-callcenter-omnicanal.plan.md` §8, FASE 17 (linha 1080), decorrente de **D6** (linha 192), condicionada por **D8** (linha 217 — aplicação é interna). **Complexidade XG.** Depois da Fase 10; não bloqueia nada.

> **Escopo confirmado com o usuário em 2026-08-14** (após uma rodada de esclarecimento sobre live
> screen-share vs. gravação assíncrona): mantém o desenho original do plano-mãe — **rrweb / captura
> de eventos de DOM** (não vídeo real via `getDisplayMedia`), aplicado **só ao canal de chat**,
> disparado **automaticamente sempre que o agente está atendendo um chat** (sujeito a toggle por
> agente), com **retenção de 60 meses** (igual à voz — decisão explícita do usuário, diferente da
> recomendação original de 30 dias). Monitoramento de tela supervisor→agente ao vivo foi levantado
> pelo usuário mas **fica fora desta fase** — será planejado como tarefa separada, com mais
> detalhe, quando o usuário decidir avançar nela. Base legal/DPO para gravar a tela do colaborador
> durante o chat já foi validada pelo usuário.

## 1. Recorte — fora de escopo (registrar para não reintroduzir)
Co-browsing **interativo** (controle remoto), captura de tela real (`getDisplayMedia`), replay ao vivo, co-browsing em voz, monitoramento de tela supervisor→agente (proposto pelo usuário, mas adiado para tarefa futura própria), qualquer análise de IA sobre o replay, captura na tela do agente. **Confirmado:** sem chamada de IA, esta fase **não** cria frente no Financeiro; se algum dia processar replay com LLM, passa a ser obrigatória.

## 2. Contexto real do código (validado pelo planner)
| Peça | Onde | Uso na Fase 17 |
|---|---|---|
| `CcChatSession` | `backend/.../domain/callcenter/chat/CcChatSession.java` | Ancoragem: já tem `businessUnit`, `startedAt`, `closedAt`, `transcriptPath` |
| `PublicCallCenterChatController` | `.../chat/PublicCallCenterChatController.java` | Modelo exato: `permitAll`, validação **manual** do token `chat_customer` contra o `{id}` da URL, rate limit, `resolveIp` confiando em `X-Forwarded-For` só vindo do Caddy |
| `JwtService.generate/validateChatCustomerToken` | `backend/.../config/JwtService.java` | Reusar o **mesmo** token — não criar um segundo |
| `PublicChatRateLimiter` | `.../chat/PublicChatRateLimiter.java` | Novo bucket para lotes de eventos |
| `ChatTranscriptExportService` | `.../chat/ChatTranscriptExportService.java` | Modelo de escrita: `@Async` em classe separada, `afterCommit`, **nunca lança**, `media/chat/YYYY/MM/DD/` |
| `CallCenterRecordingController.audio` | `.../recording/CallCenterRecordingController.java` | **404 nunca 403** (id inexistente / fora de BU / arquivo ausente) + `auditService.log(..., "callcenter.recording.play", ...)` |
| `CallCenterRecordingService.resolveAudioFile` | idem (l. 199) | Defesa de path traversal: só nome-base + subpasta derivada de `startedAt`, canonicalizado contra escape |
| `CallCenterRecordingRetentionService` | idem | Só apaga a linha se o arquivo físico sumiu (senão vira órfão); modelo direto para retenção de 60 meses aqui |
| Mídia | `docker-compose.yml:411` (`media/chat:rw`), `.gitignore`, `scripts/git-hooks/pre-commit-media-guard.sh` (`--diff-filter=ACMR`) | Co-browsing vai **dentro** de `media/chat` — herda git-ignore e hook, **sem env/mount novo** |
| Widget | `frontend/public-widget/callcenter-chat-widget.js` (JS puro, 225 linhas, sem build) | Recebe a captura. **Achado:** `frontend/nginx.conf` **não tem `location /widget/`** — o widget não é servido hoje; bloqueante para validação real |
| `cc_agents` | domain agente (Fase 4/12) | Recebe o toggle `cobrowse_enabled` (D17-14) |
| RBAC | `ResourceCatalog.java`, `SecurityConfig.java` | §6 |

## 2.1 Disparo automático — regra de negócio
Sempre que um agente **assume** (claim) um chat que ele está atendendo, e o agente tem
`cobrowse_enabled=true` na própria configuração (D17-14: toggle por agente, default `false`), o
widget do cliente inicia a captura rrweb automaticamente (sujeito ao consentimento — ver §5.2).
Isso é **automático do ponto de vista do agente** (não pede um clique dele a cada atendimento);
o cliente ainda vê o aviso de consentimento (já validado legalmente, mas a UI de aceite
permanece — é a interface entre o sistema e a pessoa gravada, distinta da validação jurídica).

## 3. Fatiamento (recomendado — mantido)
| Sub-fase | Entrega | Compl. |
|---|---|---|
| **17a** | Migration, entidade, toggle `cc_agents.cobrowse_enabled`, banner de consentimento, endpoint de aceite/revogação, `location /widget/`. **Sem capturar nada.** | M |
| **17b** | rrweb no widget (mascarado), lote, `POST .../cobrowse-events`, `.jsonl.gz` em disco, tetos, disparo automático ligado ao claim do chat | G |
| **17c** | Player (`rrweb-player`) na aba Gravações, RBAC, escopo de BU, auditoria | G |
| **17d** | Retenção de 60 meses (scheduler + expurgo + eliminação sob demanda), documentação | M |

**Regra inegociável:** 17d entra na mesma janela de 17b ou no deploy seguinte — dado sensível acumulando sem expurgo é o pior estado possível.

## 4. Decisões pendentes do usuário (revisadas)
| # | Pergunta | Status |
|---|---|---|
| D17-1 | Fatiar 17a–17d? | ✅ Sim (mantido) |
| D17-2 | Biblioteca `rrweb` | ✅ Confirmado (mantido do plano original) |
| D17-3 | Empacotamento no widget (build esbuild vs. vendorizar) | **Em aberto** — recomendo build mínimo (esbuild) só do widget |
| D17-4 | Retenção | ✅ **60 meses** (confirmado — igual à voz, diferente da recomendação original de 30 dias) |
| D17-8 | Base legal/DPO para gravar tela do colaborador | ✅ **Já validado pelo usuário** |
| D17-14 | Toggle por agente ou global | ✅ **Por agente** (`cc_agents.cobrowse_enabled`, default `false`) — confirmado pelo fluxo descrito |
| D17-15/16 (monitoramento supervisor→agente) | Escopo de tela ao vivo supervisor→agente | ✅ **Fora desta fase** — planejar depois, separadamente |

## 5. Desenho técnico

### 5.1 Migration (confirmar número: topo hoje é **V72**) — `V73__callcenter_cobrowsing.sql`
```sql
CREATE TABLE cc_cobrowse_sessions (
    id                BIGSERIAL PRIMARY KEY,
    chat_session_id   BIGINT NOT NULL UNIQUE REFERENCES cc_chat_sessions(id) ON DELETE CASCADE,
    business_unit_id  BIGINT REFERENCES business_units(id),
    consent_status    VARCHAR(20) NOT NULL,   -- pending|granted|denied|revoked
    consent_at        TIMESTAMP,
    consent_text_hash VARCHAR(64),
    revoked_at        TIMESTAMP,
    file_path         VARCHAR(255),
    size_bytes        BIGINT NOT NULL DEFAULT 0,
    event_count       INTEGER NOT NULL DEFAULT 0,
    truncated         BOOLEAN NOT NULL DEFAULT FALSE,
    started_at        TIMESTAMP NOT NULL,
    last_event_at     TIMESTAMP,
    purged_at         TIMESTAMP
);
CREATE INDEX idx_cc_cobrowse_started_at ON cc_cobrowse_sessions(started_at);
CREATE INDEX idx_cc_cobrowse_bu ON cc_cobrowse_sessions(business_unit_id);

ALTER TABLE cc_agents ADD COLUMN cobrowse_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE cc_cobrowse_retention_config (
    id VARCHAR(20) PRIMARY KEY DEFAULT 'default',
    retention_days INTEGER NOT NULL DEFAULT 1826,  -- 60 meses
    last_purge_at TIMESTAMP, last_purge_deleted_count INTEGER, updated_by VARCHAR(120));
INSERT INTO cc_cobrowse_retention_config (id) VALUES ('default') ON CONFLICT DO NOTHING;
```
**Deliberado:** eventos **não** vão para o banco (nem particionados) — padrão do projeto para mídia é disco + ponteiro (`cc_recordings.file_path`, `cc_chat_sessions.transcript_path`). `business_unit_id` desnormalizada como em `cc_recordings`. Toggle vai em `cc_agents` (não em `cc_chat_channels` — é por agente, não por canal, conforme confirmado).

### 5.2 Consentimento (17a)
Aviso claro **distinto** do de gravação de voz, dois botões explícitos. Sem aceite, o chat funciona normalmente, sem insistência. Revogável a qualquer momento → para no cliente, `POST /chat/public/sessions/{id}/cobrowse-consent {granted:false, textHash}`, marca `revoked` e **apaga** o já capturado (`purged_at`). Indicador permanentemente visível durante a captura.

### 5.3 Captura e transporte (17b)
> **Revertido em 2026-08-14 a pedido explícito do usuário:** o mascaramento client-side (rrweb) e
> o sanitizador server-side (`CobrowseEventSanitizer`) descritos abaixo foram **removidos**. A
> captura é integral — tudo visível na tela do colaborador é gravado, sem exceção. Isso não foi
> pedido originalmente; foi adicionado por conta própria seguindo a recomendação padrão de
> segurança/LGPD, e revertido assim que o usuário apontou que não fazia parte do pedido.
>
> ~~Mascaramento client-side: `maskAllInputs:true`, `maskInputOptions` (password/email/tel/number),
> `blockClass:'cc-cb-block'`, `recordCanvas:false`, `inlineImages:false`, `collectFonts:false`.~~
> ~~Reforço server-side: `CobrowseEventSanitizer` portando a heurística de `insights/src/masking.py`
> (CPF/cartão/telefone), sem IA.~~
- **Lote:** flush a cada 5s ou 64KB + `sendBeacon` em `visibilitychange`/`pagehide`; gzip.
- `POST /api/v1/callcenter/chat/public/sessions/{id}/cobrowse-events` `{seq, events[]}` sob o `permitAll` de `/chat/public/**` já existente + validação manual do token.
- **Disparo automático:** ao agente fazer `claim` do chat (`CcChatService`), se `agent.cobrowseEnabled`, sinaliza ao cliente (via resposta do endpoint de claim ou polling já existente) para iniciar a captura — sujeita ao consentimento do cliente.
- **Guardas:** consentimento `granted`; sessão não encerrada; rate limit dedicado (~20 lotes/min/sessão); teto de corpo 512KB; teto acumulado por sessão (10MB ou 60min) → `truncated=true`, nunca quebra a conversa.
- **Disco:** append em `media/chat/YYYY/MM/DD/<sessionId>.events.jsonl.gz`, serviço `@Async` no molde de `ChatTranscriptExportService`, nunca lança. Lock por `sessionId` para append concorrente.

### 5.4 Player (17c)
`GET /callcenter/cobrowsing` (paginado, escopo de BU) e `GET /callcenter/cobrowsing/{id}/events`, **404 nunca 403**, auditoria de reprodução. Mesma defesa de path traversal de `resolveAudioFile`. Sub-view na aba **Gravações** do `callcenter-platform`. Replay **obrigatoriamente em `<iframe sandbox>`** (o `.jsonl` contém HTML de origem não confiável).

### 5.5 Retenção (17d)
`CallCenterCobrowseRetentionService` espelhando o de gravação de voz, com **1826 dias (60 meses)** default, scheduler diário, tela de config, `DELETE /callcenter/cobrowsing/{id}` (write, auditado, preserva linha com `purged_at`).

## 6. RBAC
Novo `callcenter.cobrowsing` no `ResourceCatalog.java`, 4 pontos de sincronia (`ResourceCatalog.java`, `SecurityConfig.java`, `Sidebar.tsx`, `AccessGroups.tsx` do `callcenter-platform/frontend`). Não reusar `callcenter.gravacoes` — nasce só com ADMIN.
```
GET     /api/v1/callcenter/cobrowsing/**          → ROLE_ADMIN | PERM_READ_callcenter.cobrowsing
DELETE  /api/v1/callcenter/cobrowsing/**          → ROLE_ADMIN | PERM_WRITE_callcenter.cobrowsing
GET/PUT /api/v1/callcenter/cobrowse-retention/**  → ROLE_ADMIN | PERM_*_callcenter.cobrowsing
```
Endpoint do cliente (`/chat/public/**`) — confirmar explicitamente que não é acessível a JWT de staff comum (mesma classe de achado HIGH da Fase 23).

## 7. Riscos
| Risco | Sev. | Mitigação |
|---|---|---|
| PII no replay | Aceito, decisão explícita do usuário (2026-08-14) | Sem mascaramento — captura integral por escolha do usuário; risco de LGPD/PII transferido para a política de consentimento e retenção, não mitigado tecnicamente |
| Volume em 60 meses (bem maior que os 30 dias originalmente recomendados) | **Alto** | Medir uma sessão real de 10min antes de liberar; considerar compressão agressiva; entra no dimensionamento da Fase 10 |
| Código novo no browser do cliente | Alto | Bundle sob demanda só após aceite, sem CDN, sem `eval`; falha da captura nunca quebra o chat |
| XSS no player | Alto | `<iframe sandbox>` + revisão de CSP |
| DoS de disco | Médio | Rate limit, 512KB/lote, teto/sessão, `truncated` |
| Widget não servido pelo nginx | Bloqueante | `location /widget/` em 17a |

## 8. Testes
Backend: `CobrowseConsentServiceTest`, `CobrowseIngestServiceTest` (sem consentimento, sessão encerrada, toggle do agente desligado, `truncated`), `CobrowseFileResolverTest` (path traversal), `CobrowseRetentionServiceTest` (60 meses, linha preservada se delete falhar), `CallCenterCobrowsingControllerTest` (404 nunca 403, auditoria). Frontend: `tsc --noEmit` + `npm run build`; player em Chrome headless via CDP. Manual obrigatório: recusa gera zero requisições; medir tamanho de sessão real de ~10min.

## 9. Deploy
Por sub-fase, `docker compose up -d --build backend frontend`, migration confirmada em `flyway_schema_history`, validação via curl com JWT forjado inline. Release notes por sub-fase. Push `origin main` e `azure main:desenvolvimento`.

## 10. Critérios de conclusão
- [ ] Migration V73 aplicada
- [ ] Toggle por agente funcionando (`cc_agents.cobrowse_enabled`)
- [ ] Consentimento explícito, revogável, auditado
- [ ] Zero captura sem aceite — provado por observação de rede
- [x] Sem mascaramento — captura integral, decisão explícita do usuário (2026-08-14)
- [ ] Player em `<iframe sandbox>`, 404 fora de BU, auditoria de reprodução
- [ ] Retenção de 60 meses + expurgo + eliminação sob demanda
- [ ] Nada sob `media/` commitado
- [ ] `ecc:security-reviewer` sem CRITICAL/HIGH em aberto
- [ ] Suíte backend verde sem regressão; `tsc --noEmit`/`npm run build` limpos
- [ ] Documentação em `Documentacao.tsx` + release notes
