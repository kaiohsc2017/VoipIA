# VoipIA — Contexto para o Claude Code

## Perfil de atuação

Você é um Engenheiro Sênior de Software e DevOps com profundo conhecimento em:

- **VoIP:** Asterisk 21 LTS, protocolo SIP/PJSIP, WebRTC, RTP/SRTP, AudioSocket, AMI, DTMF, codecs G.711a/u
- **Backend:** Java 21 + Spring Boot 3.3 (Tomcat 11), Flyway, JPA/Hibernate, WebSocket STOMP, WebClient reativo
- **Python:** asyncio, FastAPI, asyncpg, google-genai SDK, AudioSocket server assíncrono
- **Frontend:** React 18 + TypeScript, Vite, Nginx, JsSIP (softphone WebRTC)
- **Infra:** Docker Compose, Caddy 2 (TLS automático), PostgreSQL 16, Fail2ban, nftables, Ubuntu 24.04
- **Integrações:** Google Gemini (STT/LLM/TTS), Jira Cloud REST API v3, Zabbix JSON-RPC, Telegram Bot API

## Princípios de trabalho

- **Leia antes de agir** — sempre inspecione os arquivos relevantes antes de qualquer mudança
- **Cirúrgico** — altere apenas o necessário; sem refatorações desnecessárias ou "melhorias" não solicitadas
- **Código limpo** — comentários em português, nomes descritivos, sem código morto
- **Valide sempre** — antes de commitar: `bash -n` para shell, `python -m ast` para Python, `tsc --noEmit` para TypeScript
- **Commits atômicos** — um commit por problema resolvido, mensagem descritiva em português
- **Em dúvida, pergunte** — não assuma intenções; pergunte antes de decisões irreversíveis
- **Nunca exponha credenciais** — nem em logs, outputs, comentários ou mensagens de commit
- **Simples > sofisticado** — prefira a solução mais simples e comprovada

---

## Ambiente de produção

| Item | Valor |
|------|-------|
| VPS | `app.voiphash.com.br` — Ubuntu 24.04 LTS |
| IP público | `129.121.51.29` |
| Repositório no VPS | `/opt/VoipIA` |
| Remote Git | `github.com/kaiohsc2017/VoipIA` (`origin`), ver [git-workflow.md](.claude/rules/common/git-workflow.md) |
| Branch principal | `main` |
| `.env` real | `/opt/VoipIA/env/.env` |
| `.env` symlink | `/opt/VoipIA/.env` → aponta para o real |
| TLS | Caddy 2 — Let's Encrypt automático |
| Domínios | `app.voiphash.com.br`, `claw.voiphash.com.br` |

---

## Stack de containers

Rede Docker: `voipia-net` — bridge `172.16.7.0/24`

| IP | Container | Imagem / Build | Função |
|----|-----------|----------------|--------|
| `172.16.7.10` | `voipia-caddy` | `caddy:2-alpine` | Proxy reverso HTTPS — entrada de todo tráfego externo |
| `172.16.7.11` | `voipia-postgres` | `postgres:16-alpine` | Banco unificado (Telecom + Agentes) |
| `172.16.7.12` | `voipia-asterisk` | build `./asterisk` | PBX — Asterisk 21 LTS |
| `172.16.7.13` | `voipia-ai-agent` | build `./ai-agent` | Servidor AudioSocket Python — STT/LLM/TTS via Gemini |
| `172.16.7.18` | `asteriskia-insights` | build `./insights` | Serviço Python (loop de polling, sem porta própria) — transcreve/analisa via Gemini as gravações do call center corporativo Verint em `/opt/audio` (módulo apartado do domínio Asterisk, tela "Insights") |
| `172.16.7.14` | `voipia-backend` | build `./backend` | Spring Boot 3.3 — API REST + WebSocket STOMP |
| `172.16.7.15` | `voipia-frontend` | build `./frontend` | React 18 + Nginx — serve Telecom e Agentes |
| `172.16.7.16` | `voipia-agents-api` | build `./agents-platform/backend` | FastAPI — plataforma de agentes autônomos |
| `172.16.7.17` | `asteriskia-docker-helper` | build `./docker-helper` | Único container com acesso ao `docker.sock` (F-CRIT-10) — API interna estreita para `docker compose up`/`docker logs`/`docker exec` (asterisk), sem porta publicada no host, atrás de `X-Internal-Key` |
| host | `voipia-security` | build `./security` | Fail2ban + nftables — `network_mode: host` |

**IPs reservados:** `.1–.9` (gateway/infra) e `.250–.254` (infra)

---

## Fluxo de deploy

```bash
# Alterar código → commitar → rebuildar apenas o container afetado
git add <arquivos>
git commit -m "descrição clara"
git push origin main

# Rebuildar serviço específico (mais rápido)
docker compose up -d --build <nome-do-serviço>

# Rebuildar tudo
docker compose up -d --build

# Ver logs em tempo real
docker compose logs -f <serviço>

# Status dos containers
docker compose ps
```

**Atenção:** variáveis `VITE_*` são resolvidas em **build time**. Ao alterar qualquer `VITE_` no `.env`, rebuilde o frontend:
```bash
docker compose up -d --build frontend
```

---

## Banco de dados

- **Instância:** PostgreSQL 16 em `voipia-postgres:5432`
- **Banco unificado:** `asteriskia` (Telecom + Agentes na mesma instância)
- **Migrations Telecom:** Flyway — classpath `backend/src/main/resources/db/migration/` — V1 a V21
  aplicadas em produção; **V22 (grupos de acesso) commitada, aguardando deploy do backend**
- **Migrations Agentes:** `agents-platform/backend/migrate.py` — `CREATE TABLE IF NOT EXISTS` (idempotente)
- **Próxima migration Flyway:** V36 — confirme sempre com `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`

```bash
# Acesso direto (porta exposta apenas localmente)
psql -h localhost -p 5433 -U asteriskia -d asteriskia
```

---

## Roteamento Caddy → Nginx → React

```
Externo (HTTPS)
  └── Caddy (172.16.7.10)
        ├── /agents/api/*   → strip /agents → voipia-agents-api:8000
        ├── /agents/ws/*    → strip /agents → voipia-agents-api:8000 (WS)
        ├── /agents*        → voipia-frontend:80 (NÃO strip — nginx tem location /agents/)
        ├── /insights*      → voipia-frontend:80 (NÃO strip — nginx tem location /insights/)
        ├── /docs/*         → /srv/docs (file_server direto no Caddy)
        ├── /api/*          → voipia-backend:8080
        ├── /ws/*           → voipia-backend:8080 (STOMP)
        ├── /asterisk-ws*   → rewrite /ws → voipia-asterisk:8088 (WebRTC)
        └── /*              → voipia-frontend:80 (catch-all Telecom)
```

O Nginx interno serve:
- `/` → `/usr/share/nginx/html/` (build React Telecom)
- `/agents/` → `/usr/share/nginx/html/agents/` (React UMD Agentes)
- `/agents/api/` → proxy para `voipia-agents-api:8000` (fallback interno)
- `/insights/` → `/usr/share/nginx/html/insights/` (SPA própria — build Vite, `insights-platform/frontend/`)
  — sem proxy `/insights/api`: a SPA consome `/api/v1/insights/**` direto no backend Java (mesma
  origem), já coberto pela `location /api/`; diferente de Agentes, que tem FastAPI dedicado.

---

## Arquitetura do AI Agent (Python asyncio)

```
Ligação entra → Asterisk dialplan → AudioSocket(UUID, ai-agent:9092)
                                         │
                              ai-agent/src/main.py
                                         │
                              Detecta FLOW_TYPE via backend
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
            JiraCallFlow                           ZabbixAlertFlow
         (Módulo 1 — URA)                     (Módulo 3 — Alerta)
                    │
         STT (Gemini 2.5 Flash)
                    │
         LLM (Gemini 2.5 Flash) + Function Calling
                    │
         TTS (Gemini 2.5 Flash Preview TTS)
                    │
         PCM 8kHz/16bit/mono → frames 320 bytes → Asterisk
```

**Porta AudioSocket:** `9092` (interna — não exposta ao host)
**Protocolo:** frames de 3 bytes header (tipo + comprimento) + payload PCM

---

## Módulos do sistema Telecom

| Módulo | Ramal | Contexto Asterisk | Função |
|--------|-------|-------------------|--------|
| 1 — URA (multi-URA) | `1000` (URA legada/fallback) + `2000`-`2999` (URAs cadastradas) | `ramais-internos` / `recepcao-tronco` | Coleta dados via voz; abre issue no Jira se a URA tiver a integração ativada |
| 2 — Teste conectividade | Agendado via `ConnectivityScheduler.java` | — | Discagem automática para verificar números |
| 3 — Alertas Zabbix | `1001` | `ramais-internos` | Liga para responsável ao detectar alerta crítico |

**Módulo 1 — múltiplas URAs configuráveis** (generalizado a partir da URA fixa original):
- Cada URA cadastrada tem ramal próprio na faixa **2000-2999** — dialplan usa extensão genérica
  `_2XXX`, nenhuma edição de `extensions.conf` é necessária ao criar uma URA nova pela UI.
- URA `id=1` (ramal `1000`, "Service Desk") é a **legada/fallback** — usada sempre que a resolução
  de URA falha (dialplan não registrou a tempo, CURL falhou), nunca derruba a chamada por isso.
- Correlação `callUuid → uraId` feita pelo **dialplan** via `CURL` para
  `POST /api/v1/internal/ura-routing` — TTL de 5 min em memória (`UraRoutingService`), sem
  persistência em banco.
- Toggle "integração Jira ativada" por URA — se desligado, a chamada é registrada normalmente mas
  nenhum chamado é aberto no Jira.
- Gestão pela UI: aba "URAs" (`UraManagementTab.tsx`) lista as URAs; "Configurar" abre as
  perguntas/mensagens daquela URA (`FluxoURATab.tsx`, aninhado). O filtro por URA na aba
  "Chamadas" é opcional ("Todas as URAs" por padrão).

**Ramais SIP registrados:**
- `9001` — softphone WebRTC (frontend React, senha em `RAMAL_9001_PASSWORD`/`VITE_SIP_PASSWORD`)
- `9002` — softphone físico/Zoiper (senha em `RAMAL_9002_PASSWORD`)
- `1001`, `1002` — ramais internos de teste (senhas em `RAMAL_1001_PASSWORD`/`RAMAL_1002_PASSWORD`)

Senhas SIP saíram do `pjsip.conf.template` (versionado) e são injetadas via `envsubst` no boot
(`asterisk/docker-entrypoint.sh`) — nunca hardcodar um valor real de senha nesta documentação.
Consulte o valor atual com: `grep '^RAMAL_9001_PASSWORD=' /opt/VoipIA/env/.env`

**Tronco SIP:** peer IP-based com `186.233.141.64` — sem usuário/senha, fechado por IP

---

## Documentação (página "Documentação" no Telecom)

Manual do sistema acessível pela Sidebar (seção SISTEMA, `resource_key: telecom.docs`, migration
V24 — liberado por padrão para os dois grupos seed). Página React em
`frontend/src/components/Documentacao.tsx` (+ `docs/DocsLayout.tsx`, `docs/sections/*.tsx`),
migrada de `agents-platform/frontend/docs.html` (removido) e expandida com seções novas sobre o
Telecom (URA multi-instância, Conectividade, Alertas Zabbix, RBAC granular, Softphone/ramais) além
do manual original da Plataforma de Agentes. O botão "Documentação" que existia no menu da
Plataforma de Agentes (abrindo `/agents/docs.html`) foi removido — o acesso agora é só pelo Telecom.

---

## Autenticação e RBAC granular (grupos de acesso — V22)

- **JWT (HS256)** emitido pelo backend Java (`AuthController`) — compartilhado com o FastAPI de
  Agentes via o mesmo `BACKEND_JWT_SECRET` (mesma lógica de padding da chave nos dois lados).
- **Refresh token**: cookie `asteriskia_refresh_token` (`HttpOnly; Secure; SameSite=Strict`,
  `Path=/api/v1/auth`) — nunca em `localStorage` (F-CRIT-13). `client.ts` usa
  `withCredentials: true`; `revokeSession()` faz logout sem disparar o evento
  `asteriskia:logout` de novo (evita loop com `App.tsx`).
- **Grupos de acesso** (`access_groups` + `access_group_permissions`, migration V22) substituem o
  binário `role` ADMIN|USER por grupos nomeados com permissão de leitura/escrita por menu
  (`resource_key`, ex: `telecom.settings`, `agents.secrets`). Catálogo de recursos fixo em código
  (`ResourceCatalog.java`, espelhado em `Sidebar.tsx`, no `NAV` do `agents-platform/frontend` e no
  `App.tsx` da SPA `insights-platform/frontend`) — os menus são fixos, só a matriz de permissões é
  dinâmica. Gestão pela UI: página "Grupos de Acesso" (`AccessGroups.tsx`, admin-only).
  Namespace `insights.*` (`insights.calls`/`dashboard`/`processing`/`scorecards`/`reports`/
  `uploads`) segue o mesmo padrão granular por aba do namespace `agents.*`; `telecom.insights_link`
  é só o item de menu que abre a SPA via iframe, sem relação com os dados. Namespace
  `financeiro.*` (`financeiro.ura`/`insights`/`envios`) protege o módulo Financeiro (submenu na
  Sidebar do Telecom) — `insights.costs` foi removido do catálogo quando as telas de custo do
  Insights migraram pra lá (V41).
- **Claim `role`** (`ADMIN`|`USER`) continua sendo emitida em paralelo (**dual-emit**) por
  compatibilidade — tokens antigos (antes do deploy do RBAC granular) só têm `role`, sem a claim
  `perm`, e continuam válidos até expirar/renovar (máx. 8h). **Claim `perm`**
  (`{resource_key: "r"|"w"|"rw"}`) é resolvida do grupo do usuário (`AccessGroupService`) no
  login/refresh/2FA e carrega a matriz de permissões.
- **Backend Java** (`SecurityConfig`): cada rota aceita `hasAnyAuthority("ROLE_ADMIN",
  "PERM_READ_<resource>")` (GET) / `"PERM_WRITE_<resource>"` (demais métodos) — `JwtAuthFilter`
  expande a claim `perm` em authorities `PERM_READ_*`/`PERM_WRITE_*`. `/access-groups/**`
  (gestão dos próprios grupos) e a retenção de dados do agents-platform continuam em `ROLE_ADMIN`
  puro — não têm `resource_key` (evita o grupo customizado precisar de si mesmo pra existir, ou
  não têm menu correspondente). Escrita em `/uras/**` aceita ADMIN, `INTERNAL`, ou
  `PERM_WRITE_telecom.modulo1`.
- **FastAPI de Agentes** (`agents-platform/backend/auth.py`): **não tem login próprio** — reusa o
  mesmo JWT/claims do Telecom. `require_permission(resource_key, action)` substituiu
  `require_admin` nos endpoints de escrita de `agents`/`servers`/`llm_config`/secrets de `system`;
  `require_admin` puro sobrevive só pra retenção (sem menu) e logs de execução em `executions.py`
  (risco de leak de DSN/senha em mensagem de erro, não uma decisão de visibilidade de menu).
- **Frontend**: `client.ts` (`getPermissionsFromToken`/`canRead`/`canWrite`) — hoje replicado nas
  três SPAs (Telecom, Insights e Agentes, `agents-platform/frontend/src/api/client.ts` desde a
  migração para Vite) — decodifica a claim `perm` do JWT (sem validar assinatura — é só hint de
  UI) para esconder nav/botões por recurso. ADMIN (`role` legada) sempre enxerga tudo, mesmo com
  token antigo sem `perm`. `Sidebar.tsx`/`App.tsx` das três SPAs usam esse par em vez do binário
  `adminOnly`.
- ✅ **Atribuir grupo de acesso customizado a um usuário pela UI** — implementado (commit
  `e3c73bb`, ver seção própria mais abaixo). Nota anterior desatualizada.

---

## Estrutura do repositório

```
VoipIA/
├── asterisk/
│   ├── Dockerfile              # Build Asterisk 21 com app_audiosocket
│   ├── docker-entrypoint.sh    # Injeta SIP_PUBLIC_IP no pjsip.conf no boot
│   └── config/
│       ├── pjsip.conf.template # Template com ${SIP_PUBLIC_IP} substituído no boot
│       ├── extensions.conf     # Dialplan: contextos recepcao-tronco, ramais-internos
│       ├── rtp.conf            # Porta RTP: 16000-16500
│       └── http.conf           # HTTP/WS na porta 8088 (WebRTC)
├── ai-agent/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── src/
│       ├── main.py             # Servidor AudioSocket asyncio — entry point
│       ├── config.py           # Lê .env dinamicamente (sem restart)
│       ├── protocol.py         # Frames AudioSocket: read_frame / write_audio
│       ├── flows/
│       │   ├── jira_call_flow.py    # Fluxo URA Módulo 1
│       │   └── zabbix_alert_flow.py # Fluxo alerta Módulo 3
│       ├── services/
│       │   ├── ai_service.py        # Orquestra STT → LLM → TTS
│       │   ├── gemini_service.py    # SDK google-genai (STT, LLM, TTS streaming)
│       │   ├── backend_client.py    # HTTP para o backend Spring Boot
│       │   └── provider_registry.py # Multi-provider (Gemini, Anthropic, OpenAI…)
│       └── providers/               # Adaptadores por provedor de IA
├── backend/
│   └── src/main/java/com/asteriskia/
│       ├── config/              # SecurityConfig, JwtService, AuthController, AppConfig
│       ├── domain/
│       │   ├── ai/              # AIProviderController — configuração de modelos
│       │   ├── alert/           # AlertService — Zabbix polling + ligação
│       │   ├── audit/           # AuditLog, TotpController
│       │   ├── call/            # CallRecord, CallRecordService, ExcelExport
│       │   ├── config/          # ConfigService — lê .env com TTL 60s
│       │   ├── connectivity/    # ConnectivityScheduler — Módulo 2
│       │   ├── logs/            # LogsController — stream docker logs via SSE
│       │   ├── masterdata/      # MasterDataController — dados de referência
│       │   ├── pedido/          # SuporteController — abre chamado no Jira (function calling da IA)
│       │   ├── report/          # ReportController — relatórios e Excel
│       │   ├── security/        # SecurityController — fail2ban via socket
│       │   ├── settings/        # SettingsService, SettingsController, SettingsTestController
│       │   ├── ura/             # UraQuestion — perguntas configuráveis da URA
│       │   └── user/            # UserController — gestão de usuários
│       └── integration/
│           └── jira/            # JiraIntegrationService — REST API v3
├── frontend/
│   ├── Dockerfile               # Multi-stage: node:22-alpine (Telecom) → node:22-alpine (Insights) → node:22-alpine (Agentes) → nginx:1.27-alpine
│   ├── nginx.conf               # SPA fallback + proxy /agents/api, /agents/ws + location /insights/ e /agents/ (alias)
│   └── src/
│       └── components/          # Dashboard, Settings, AISettingsPanel, Softphone, InsightsPage.tsx (iframe /insights/)… (inclui docs/ — Documentacao.tsx, migrado de agents-platform/frontend/docs.html)
├── agents-platform/
│   ├── backend/
│   │   ├── main.py              # FastAPI app + JWT middleware + WebSocket broadcast
│   │   ├── database.py          # Schema PostgreSQL + pool asyncpg + migrate_db()
│   │   ├── executor.py          # Motor: SSHExecutor, WebExecutor, DatabaseExecutor…
│   │   ├── scheduler.py         # Agendador cron/interval assíncrono
│   │   ├── notifier.py          # Telegram + webhook
│   │   ├── llm.py               # Multi-provider LLM
│   │   └── routers/             # agents, servers, executions, reports, knowledge, llm_config, system
│   └── frontend/                # Vite+React+TS, build próprio (migrado do React 18 UMD sem build
│                                 # step em 2026-07-19 — mesmo padrão da SPA de Insights); 8 telas em
│                                 # src/components/ (Dashboard/Agents/Servers/Knowledge/Logs/Alerts/
│                                 # Secrets/LlmSettings + AgentForm); backend FastAPI inalterado —
│                                 # api/client.ts tem dois axios (agents-backend + telecomApi p/
│                                 # login e streaming-token do WS de alertas)
├── insights-platform/
│   └── frontend/                # SPA independente de Insights (Vite+React, build próprio) — reusa o
│                                 # backend Java (/api/v1/insights/**), servida em /insights pelo mesmo
│                                 # nginx do frontend Telecom (mesmo padrão do agents-platform, mas
│                                 # sem backend próprio); componentes Insights*.tsx copiados do Telecom
├── security/
│   ├── Dockerfile
│   ├── entrypoint.sh
│   ├── lockdown-watcher.sh      # Watcher de comandos nftables (roda no host via systemd)
│   ├── voipia-lockdown.service  # Unit systemd instalado pelo install.sh
│   └── config/
│       ├── jail.d/              # asterisk.conf — 3 jails (auth, scanner, flood)
│       └── filter.d/            # Filtros regex para os jails
├── docker-helper/
│   ├── Dockerfile                # Único ponto do stack com Docker CLI + docker.sock
│   ├── requirements.txt
│   └── main.py                   # API interna: /compose/up, /logs/{svc}[/stream], /asterisk/log[/stream]
├── docs/
│   └── deploy-ubuntu.html       # Guia de instalação Ubuntu 22/24
├── tools/
│   └── agente-google.py         # Agente CLI local (Gemini + memória PostgreSQL/pg_trgm,
│                                 # ferramentas bash/write_file com confirmação)
├── docker-compose.yml
├── Caddyfile
├── .env.example
├── install.sh                   # Instalação automatizada Ubuntu 22/24
├── deploy.sh                    # Deploy (git pull + docker compose up)
└── CLAUDE.md                    # Este arquivo
```

---

## Variáveis de ambiente críticas

| Variável | Onde é usada | Observação |
|----------|-------------|------------|
| `SIP_PUBLIC_IP` | Asterisk `pjsip.conf` | **CRÍTICO** — IP público do VPS para NAT RTP/WebRTC. Injetado no boot pelo `docker-entrypoint.sh` |
| `GEMINI_API_KEY` | ai-agent, agents-api | Chave Google AI Studio |
| `GEMINI_MODEL_STT` | ai-agent | `gemini-2.5-flash` |
| `GEMINI_MODEL_LLM` | ai-agent | `gemini-2.5-flash` |
| `GEMINI_MODEL_TTS` | ai-agent | `gemini-2.5-flash-preview-tts` |
| `BACKEND_JWT_SECRET` | backend, agents-api | Compartilhado — HS256, 32+ chars |
| `POSTGRES_PASSWORD` | postgres, backend, agents-api | Senha pode conter caracteres especiais — agents-api faz URL-encode |
| `INTERNAL_API_KEY` | backend, ai-agent | Autenticação interna entre serviços |
| `JIRA_ISSUE_TYPE` | backend (Jira) | Tipo de issue da URA (ex: Task, Support) |
| `VITE_STUN_URL` | frontend (build time) | STUN para ICE do softphone WebRTC |
| `VITE_*` | frontend (build time) | Rebuild obrigatório ao alterar |

---

## Comandos de diagnóstico frequentes

```bash
# Verificar se SIP_PUBLIC_IP está correto no Asterisk (CRÍTICO para WebRTC)
docker exec voipia-asterisk grep "external_media_address" /etc/asterisk/pjsip.conf

# Corrigir SIP_PUBLIC_IP manualmente (se vazio após restart)
docker exec voipia-asterisk sed -i \
  's/external_media_address = $/external_media_address = 129.121.51.29/' \
  /etc/asterisk/pjsip.conf
docker exec voipia-asterisk asterisk -rx "module reload res_pjsip.so"

# Recarregar dialplan sem restart
docker exec voipia-asterisk asterisk -rx "dialplan reload"

# Status dos endpoints SIP
docker exec voipia-asterisk asterisk -rx "pjsip show endpoints"

# Recarregar Caddyfile sem downtime (admin API via socket Unix — não é mais TCP:2019)
curl --unix-socket /opt/VoipIA/caddy-admin/admin.sock http://localhost/load \
  -H "Content-Type: text/caddyfile" \
  --data-binary @/opt/VoipIA/Caddyfile

# Status do lockdown SIP no host
systemctl status voipia-lockdown
nft list chain ip filter DOCKER-USER 2>/dev/null

# Verificar healthcheck de um container
docker inspect --format='{{.State.Health.Status}}' voipia-agents-api

# Rede: verificar IPs atribuídos
docker network inspect asteriskia_voipia-net \
  --format '{{range .Containers}}{{.Name}}: {{.IPv4Address}}{{"\n"}}{{end}}'

# Acessar banco diretamente
docker exec -it voipia-postgres psql -U asteriskia -d asteriskia

# Verificar o docker-helper (único container com docker.sock — F-CRIT-10)
docker inspect --format='{{.State.Health.Status}}' asteriskia-docker-helper
curl -sf --unix-socket /opt/VoipIA/caddy-admin/admin.sock http://localhost/config/ >/dev/null && echo ok

# Forjar um JWT de teste (ADMIN ou USER) para testar RBAC sem criar usuário real —
# mesmo BACKEND_JWT_SECRET do .env, mesma lógica de padding do JwtService.java
python3 -c "
import time
raw = open('env/.env').read()
secret = next(l.split('=',1)[1].strip() for l in raw.splitlines() if l.startswith('BACKEND_JWT_SECRET='))
key = secret.encode(); key = key.ljust(max(32, len(key)), b'\x00')
from jose import jwt
now = int(time.time())
print(jwt.encode({'sub':'_teste_manual','role':'ADMIN','iat':now,'exp':now+300}, key, algorithm='HS256'))
"
```

---

## Pendências conhecidas (prioridade)

> **Áudio WebRTC validado e funcionando em produção** (confirmado pelo usuário em 2026-07-02) —
> a cadeia completa `softphone 9001 → ramal 1000 → AudioSocket → ai-agent:9092 → STT/LLM/TTS →
> RTP de volta` deixou de ser uma pendência crítica. Se voltar a apresentar problema, os pontos
> de verificação de sempre continuam válidos: `SIP_PUBLIC_IP` injetado no `pjsip.conf`,
> `external_media_address`/`external_signaling_address` não vazios, portas RTP `16000-16500/udp`
> abertas, ai-agent healthy na porta 9092, logs do ai-agent durante a chamada de teste.

> **Lista de pendências reais em aberto para o projeto (atualizada em 2026-08-14)** — ver
> `.claude/plans/callcenter-parte-iii-revisado.plan.md` e `.claude/plans/modulo-callcenter-omnicanal.plan.md`
> para o detalhe de cada item:
> 1. **Plano Call Center Parte III** — Fase 27 (gamificação/perfil de cliente/produtividade) ✅
>    deployada em 2026-08-14, encerrando a "camada analítica completa" (release 11 do plano
>    revisado: 9c → 26 → 27). Fase 10 (endurecimento/carga) está **encerrada**: fatia 1
>    (segurança/healthchecks/documentação) ✅ e parte 2 (particionamento de
>    `cc_interaction_events`/`cc_chat_messages`, migration V71) ✅ deployadas em 2026-08-14; o
>    teste de carga SIPp (parte 1) foi **descartado por decisão do usuário em 2026-08-15** — não
>    será feito (ver `.claude/plans/callcenter-fase10-seguranca-endurecimento.plan.md` §10-§11 para
>    o histórico da tentativa). Em seu lugar, foi produzida uma recomendação de hardware para 250
>    agentes simultâneos (ver seção "Recomendação de hardware — 250 agentes simultâneos" logo
>    abaixo desta lista de pendências) — a VPS atual (2 vCPU / 3.8Gi RAM, já em swap com a carga
>    de desenvolvimento) está muito abaixo do necessário para esse volume. Fase 17
>    (co-browsing) ✅ deployada em 2026-08-14. Fase 18 (IA local) **não tem código pendente** — é
>    um estudo/roadmap já integralmente escrito em `modulo-callcenter-omnicanal.plan.md` §18, com
>    decisão explícita de manter-se com API por ora; das duas pré-condições da Onda 1 (memória/RAG
>    local via `pgvector`, já disponível desde a V69 da Fase 25), a **recomendação de hardware já
>    foi produzida em 2026-08-15** (ver seção própria acima). Resta só medir custo real de 30 dias
>    de operação — isso **não é uma tarefa de código, é uma medição que depende de tempo real de
>    produção rodando**; não há atalho possível sem tráfego real. Fica formalmente pausada até
>    completar essa janela — nada a fazer aqui além de esperar e depois medir.
> 2. **Plano-mãe do Call Center, fases nunca concluídas**: **Fase 1/AD ✅ fechada em 2026-08-15**
>    (as 3 lacunas do `.claude/plans/callcenter-fase1-ad-lacunas.plan.md` — `employee_id`/
>    `employeeID` agora espelhado em `ad_users` (migration V84), `fetchAll()` pagina de verdade via
>    `PagedResultsDirContextProcessor` (não trunca mais acima de 1000 usuários), e tela nova em
>    Configurações → Active Directory (`AdSyncTab.tsx`) com status/sync manual/consulta de
>    usuário/CRUD de mapeamento de grupo — libera a Fase 14/screen pop para começar); **Fase 5 ✅
>    concluída em 2026-08-14** (5d
>    simulador v1.74, 5e.1 horário/feriados v1.75, 5e.2 transbordo+`transferir_ramal` v1.76, 5f.1
>    skill v1.77, 5f.2 tela de traço v1.78 — ver
>    `.claude/plans/callcenter-fases-5-7-9.plan.md` §2 para o detalhe de cada fatia; dos 7 nós do
>    catálogo antes bloqueados, `horario_funcionamento`/`transferir_ramal` foram desbloqueados por
>    esta fase e `pausar_gravacao`/`pesquisa_satisfacao` já estavam implementados desde as Fases
>    5c/21 — `agente_ia` [Fases A/B ✅ 2026-08-15], `coletar_entrada` [Fase 14 ✅ 2026-08-15] e
>    `consultar_api` [✅ 2026-08-15] já desbloqueados — **os 7 nós do catálogo original desta fase
>    estão todos implementados agora**); Fase 7/Chat — 7c blending ✅, 7d anexos ✅, 7e
>    Telegram ✅ (todas 2026-08-14, ver `asteriskia_callcenter_fase7e_telegram.md` e o mesmo plano
>    acima) — ✅ **fila real do chat público validada em 2026-08-15**: o canal `webchat`
>    (`cc_chat_channels.id=2`) ganhou `defaultQueueId` apontando para uma fila real (via
>    `POST /callcenter/filas` + `PUT /callcenter/chat/channels/2`, não SQL cru — mantém o ARA
>    nativo sincronizado), e o pipeline completo foi validado ponta a ponta via curl contra os
>    endpoints públicos reais: `POST /chat/public/sessions` (200, antes 503 sem fila), mensagem do
>    cliente persistida, sessão aparecendo em `GET /chat/queue/{id}` (visão do agente) com
>    status `waiting`, e encerramento via `POST /chat/{id}/close`. **Gap residual**: o arquivo
>    `frontend/public-widget/callcenter-chat-widget.js` (o JS embutível em si, rodando num
>    navegador de verdade) continua sem validação visual — só a API por trás dele foi confirmada
>    (mesma limitação de sempre: Chrome DevTools MCP indisponível nesta VPS). WhatsApp segue sem
>    credenciais (dependência externa, fora do nosso controle); **Fase 9 ✅ concluída em
>    2026-08-14** — 9c.1 agregado
>    de fluxo/URA (v1.82), 9c.2 agregado de chat FRT/ART/concorrência/contenção do bot (v1.83),
>    9c.3 timeline omnicanal paginada em banco (v1.84), 9c.4 rechamada 24h/7d + top tabulações
>    (v1.85), 9c.5 exportação Excel/PDF (v1.86), CFG-email — configuração SMTP em Sistema →
>    Configuração (v1.87), 9c.6 agendamento por Telegram/e-mail (v1.88), 9c.7 escala/aderência do
>    agente (v1.89) — ver `.claude/plans/callcenter-fases-5-7-9.plan.md` §2 para o detalhe de cada
>    fatia; **a leva inteira do plano (Fases 5, 7 e 9) está fechada**. Fase 10 (encerrada — teste
>    de carga SIPp descartado, ver item 1 acima); **Fase 14 ✅ concluída em 2026-08-15** (identidade do contato/screen
>    pop, v1.91 — ver `asteriskia_callcenter_fase14_identidade_screenpop.md`; desbloqueia a Fase
>    16); **Fase 16 ✅ concluída em 2026-08-15** (histórico do contato e copiloto de IA para o
>    agente, v1.92 — ver `asteriskia_callcenter_fase16_copiloto_ia.md`); Fase 17 ✅ (co-browsing,
>    deployada em 2026-08-14); Fase 18 (IA local,
>    roadmap concluído — sem código pendente, ver item 1 acima).
> 3. **Maior incerteza do projeto — validada parcialmente com tráfego real em 2026-08-15.**
>    Nenhum ramal SIP estava registrado nesta VPS (1001, 1002 e o ramal 4001 do único agente
>    cadastrado, Kaio, todos "Unavailable" — sem telefone/softphone real conectado), então uma
>    chamada *totalmente* real (com um agente humano atendendo) não pôde ser feita nesta sessão.
>    Em vez disso, foi originada uma chamada real (não mock/curl) via
>    `asterisk -rx "channel originate Local/<fila>@ramais-internos application Wait <n>"` — sem
>    SIPp (decisão do usuário de descartar SIPp continua valendo; isso não gera carga, só exercita
>    o caminho funcional uma vez) — que atravessou de verdade o dialplan `_5XXX`, gerou
>    `QueueCallerJoin`/`QueueCallerAbandon` reais capturados pelo `CallCenterAmiEventListener` e
>    criou uma `cc_interactions` real no banco. `AgentConnect` (agente atendendo) segue não
>    confirmado — falta repetir o teste com um telefone/softphone real registrado no ramal do
>    agente.
>    **4 bugs reais encontrados e corrigidos só por causa deste teste** (nenhuma revisão estática
>    os teria pego, porque dependiam do comportamento real do dialplan/rede):
>    1. **CRITICAL funcional** — `CUT(REC_CONFIG,;,1)` no `extensions.conf.template` usava `;`
>       como delimitador, mas `;` é o caractere de comentário do formato `extensions.conf` — a
>       linha era truncada no parse, e `REC_PART`/`REC_FLAG`/`CONSENT_PATH` **sempre** resolviam
>       vazio desde que a feature existe. Resultado prático: a opção "não gravar" por fila
>       **nunca funcionou** em produção — toda fila sempre gravava, independente do
>       `recordingEnabled=false` configurado. Corrigido escapando o delimitador (`\;`), validado
>       em produção com uma chamada real confirmando `REC_FLAG=false` e `GotoIf(...1?skiprec)`
>       pulando corretamente o `MixMonitor`.
>    2. **HIGH funcional** — `CURL(url,extension=${EXTEN})` no dialplan sempre faz **POST**
>       (comportamento do `func_curl` do Asterisk ao receber um segundo argumento), mas o endpoint
>       `queue-recording-config` só aceitava GET — toda chamada real recebia 405 silenciosamente
>       (o dialplan não trata erro de CURL, só segue com `REC_CONFIG` vazio/erro). Corrigido
>       movendo o parâmetro para a query string da própria URL (`?extension=${EXTEN}`, sem
>       segundo argumento no CURL).
>    3. **HIGH operacional** — `CallCenterAmiEventListener` abria a conexão AMI persistente com
>       `SO_TIMEOUT=0` (bloqueio infinito); ao reiniciar o container do Asterisk durante este
>       teste, o listener ficou preso num `read()` que nunca retornou — **sem nenhum log de
>       erro**, e sem reconectar sozinho (a lógica de reconexão existe, mas nunca é acionada
>       porque a exceção de I/O nunca chega). Na prática: qualquer restart do Asterisk em produção
>       silenciosamente paralisa toda a ingestão de eventos de fila/agente até alguém reiniciar o
>       backend manualmente — ninguém saberia, porque não há log. Corrigido com timeout finito
>       (60s) — timeout vira `SocketTimeoutException` (subtipo de `IOException`), reaproveitando
>       o mesmo caminho de reconexão automática já existente. Teste de regressão novo
>       (`AmiSessionTest`) prova o comportamento com um servidor TCP fake em silêncio.
>    4. **CRITICAL operacional — o mais grave dos quatro, e o único que só apareceu ao testar de
>       verdade em produção** (o bug 3 acima foi corrigido primeiro e *parecia* resolver o
>       problema, mas um segundo teste real de restart do Asterisk provou que não): quando o
>       Asterisk é reiniciado de forma **graciosa** (`docker compose restart`, SIGTERM — diferente
>       do restart abrupto que expôs o bug 3), o socket é fechado limpo (EOF), e
>       `AmiSession.readBlock()`/`readUntil()` devolviam silenciosamente uma string vazia em vez
>       de lançar exceção — o chamador via isso como "bloco em branco" válido, dava `continue` e
>       chamava `readBlock()` de novo no mesmo socket já morto, entrando num **laço apertado sem
>       nenhuma espera: 100% de uma CPU inteira, para sempre, sem nunca reconectar**. Medido ao
>       vivo via `docker stats` (98,5% de CPU, travado por mais de 2 minutos sem nenhum log).
>       Corrigido lançando `EOFException` (subtipo de `IOException`) quando o EOF é atingido antes
>       do bloco/sentinela fechar, nos dois métodos — propaga pro mesmo caminho de reconexão.
>       Revalidado ao vivo: `docker compose restart asterisk` → log real
>       `"conexão perdida (Conexão AMI encerrada pelo peer (EOF) antes do bloco fechar.)"` →
>       reconecta sozinho em ~5s após o Asterisk voltar, CPU do backend em 0,57% (normal). Teste de
>       regressão novo prova o EOF gracioso especificamente (distinto do teste do bug 3, que cobre
>       o socket silencioso/preso).
>    Os 4 bugs foram corrigidos e revalidados com testes reais (não só automatizados) de restart
>    do Asterisk antes de considerar esta validação concluída. Fica documentado no javadoc de
>    `CallCenterAmiEventListener`/`AmiSession`.
> 4. **Débitos transversais (fora do Call Center)**: CSP **✅ migrado para enforcement real em
>    2026-08-15** (ver seção "Débito de segurança" mais abaixo). BU — **✅ fechado por completo em
>    2026-08-15**: Insights do Call Center (`/calls`, detalhe, áudio — commits `a859bfd`/`7ee536b`),
>    `/dashboard` (4 queries de agregado) e o relatório 9c (`/calls`, `/chats`, exportação e
>    agendamento por Telegram/e-mail — este último fechado em 2026-08-15, migration V88) todos
>    filtram por `BusinessUnitContext` agora (ver seção "Controle de acesso por BU" mais abaixo
>    para o detalhe completo). Alertas Zabbix **nunca terá** segmentação por BU — decisão de produto
>    definitiva do usuário (2026-08-15), não é mais um gap. Jira sem credenciais reais (dependência
>    externa, fica para quando o usuário trouxer as credenciais).
>    **Catálogo de nós do Flow Builder do Call
>    Center 100% implementado desde 2026-08-15** — os 3 últimos nós saíram do estado bloqueado
>    nesta entrega: `agente_ia` (Fase A — CRUD de persona/prompt/modelo, migration V87,
>    `cc_ia_agents`/`cc_ia_agent_turns`; Fase B — execução real, `AgenteIaNodeHandler` +
>    `CallCenterIaAgentConversationService`, laço limitado de pergunta→resposta, canal `both`, sem
>    RAG ainda, escala pra `fallbackQueue` por timeout/custo/erro/`maxTurns`) e `consultar_api`
>    (`ConsultarApiNodeHandler` — URL nunca livre no fluxo, só referência a chave do `.env`
>    validada por allowlist, escrita restrita a `PERM_WRITE_telecom.settings`/`ROLE_ADMIN`; guard
>    de host privado/loopback como defesa em profundidade). `FlowGraphValidator` continua
>    bloqueando a publicação de qualquer nó novo que seja adicionado sem handler. **Fila real do
>    chat público ✅ configurada e validada em 2026-08-15** (ver item 1 acima) — só falta validação
>    visual do widget JS num navegador real e credenciais do WhatsApp.

### 📐 Recomendação de hardware — 250 agentes simultâneos (2026-08-15)
Produzida em substituição ao teste de carga SIPp (descartado, ver item 1 da lista de pendências
acima). É dimensionamento por cálculo/composição de literatura + observação da VPS atual, **não**
validado por teste de carga real — trate como ponto de partida, não como garantia.

**Diagnóstico da VPS atual**: 2 vCPU / 3.8Gi RAM — já em ~2,3Gi usados e ~2,9Gi de swap em uso só
com a carga de *desenvolvimento* (múltiplos containers, sem tráfego real de 250 agentes). Está
ordens de grandeza abaixo do necessário para o volume-alvo; não é uma questão de "aumentar um
pouco", é preciso trocar de classe de servidor.

**Premissas de carga** (250 agentes = 250 ramais SIP registrados simultaneamente, cenário de pico
com boa parte em conversação ativa ao mesmo tempo — não só logados):
- **Áudio (Asterisk)**: Asterisk só repassa RTP entre softphone↔tronco/fila (sem transcodificação
  na maioria das chamadas — só o Módulo 1/NPS/`agente_ia` passam por AudioSocket pro ai-agent).
  G.711 ≈ 87 kbps por perna com overhead RTP; 250 chamadas simultâneas bidirecionais ≈ **45-90
  Mbps** só de mídia, fora sinalização SIP/WebRTC. Co-browsing/gravação simultânea (Fases 17/8)
  soma I/O de disco, não rede.
- **Backend Java (Spring Boot + WebSocket STOMP)**: 250 conexões persistentes (softphone,
  Desktop do Agente com polling de 3-5s do copiloto/histórico/chat) + pool de conexões ao
  Postgres. JVM sob esse volume de threads/heap precisa de headroom real, não os 1Gi/1 vCPU
  atuais do `docker-compose.yml`.
- **PostgreSQL**: já particionado (V71/V72) mas o volume de escrita cresce linear com agentes
  ativos (`cc_interaction_events`, `cc_chat_messages`, `cc_agent_states`, embeddings pgvector da
  Fase 25). Precisa de I/O de disco rápido (NVMe) e RAM suficiente pra `shared_buffers`/cache —
  é tipicamente o primeiro gargalo antes da CPU num call center desse tamanho.
- **ai-agent (Python/AudioSocket+Gemini)**: só atende as chamadas que passam por IA (URA,
  pesquisa NPS falada, nó `agente_ia`) — bem menos que 250 simultâneas na prática, mas cada sessão
  mantém streaming ao Gemini (I/O de rede + algum CPU de framing PCM), não CPU-bound pesado.
- **insights (embeddings locais via pgvector, CPU)**: picos de CPU durante reindexação da KB
  (Fase 25) e processamento de gravações — não é hot-path de chamada, pode tolerar fila.

**Recomendação — topologia de 2 servidores** (separar banco do resto reduz o maior risco de
contenção de I/O sob carga real):

| Servidor | Papel | vCPU | RAM | Disco | Rede |
|----------|-------|------|-----|-------|------|
| **App** | Caddy + Asterisk + backend Java + ai-agent + frontend + insights + agents-api + docker-helper + security | **16-24 vCPU** | **32 GB** | 200 GB SSD/NVMe | 1 Gbps dedicado (headroom p/ pico de RTP) |
| **Banco** | PostgreSQL 16 dedicado (pgvector já em uso desde V69) | **8 vCPU** | **32 GB** (permite `shared_buffers` generoso + cache de SO) | 200-500 GB **NVMe** (IOPS é o gargalo real, não capacidade) | 1 Gbps interno |

Se preferir um único servidor por simplicidade operacional (aceitável nesta fase, mas com menos
margem): **24-32 vCPU / 64 GB RAM / NVMe**, com os limites de `docker-compose.yml` (hoje
tipicamente `1 vCPU`/`1Gi` por serviço) revisados para refletir a RAM real disponível — os limites
atuais foram calibrados pra uma VPS de 2 vCPU/3.8Gi de desenvolvimento, não para produção.

**Pontos de atenção específicos**:
- Portas RTP `16000-16500/udp` (500 no total) já cobrem 250 chamadas simultâneas sem ajuste —
  **confirmar isso antes de qualquer outra coisa** se o volume real chegar perto do limite.
- `max_connections` do Postgres e o tamanho do pool HikariCP do backend precisam crescer juntos
  (250 agentes com polling multiplicam conexões); considerar PgBouncer se o pool direto não for
  suficiente.
- Bandwidth de rede externa: dimensionar para o pico de RTP (45-90 Mbps) **mais** tráfego de
  gravação simultânea sendo baixada por supervisores/relatórios — recomendar contratar 1 Gbps
  cheio, não só o mínimo calculado.
- Esta recomendação não substitui uma validação empírica: quando houver volume real ou uma janela
  seguinda (servidor dedicado, fora da VPS compartilhada com outros projetos), vale medir de fato
  antes de comprar hardware definitivo.

### ✅ Atribuir grupo de acesso customizado a um usuário pela UI (2026-08-15) — deployada e validada em produção
Fechava a última lacuna binária do RBAC granular (V22): a UI de usuários (`Users.tsx`) só permitia
o Perfil ADMIN|USER, nunca um dos grupos de acesso customizados já cadastrados em "Grupos de
Acesso". Sem migration nova — `app_users.access_group_id` já existia como FK obrigatória desde a
V22, só faltava a UI e a API aceitarem um valor explícito.
- **Backend**: `CreateUserRequest`/`UpdateUserRequest` ganharam `accessGroupId` (opcional).
  `UserService.resolveAccessGroup(accessGroupId, role)` — se informado, prevalece sobre o
  fallback binário `resolveGroupForRole(role)` (que continua existindo, sem quebrar o fluxo
  legado). `UserResponse` passou a expor `accessGroupId`/`accessGroupName`.
- **1 achado CRITICAL real corrigido antes do deploy** (`ecc:security-reviewer`): a rota
  `/api/v1/users/**` de escrita aceita `PERM_WRITE_telecom.users` além de `ROLE_ADMIN` — sem
  checagem adicional, um usuário com só essa permissão (não-ADMIN) conseguiria se auto-promover
  atribuindo o grupo "Administradores" (id=1) via `accessGroupId`, ou `role="ADMIN"` — escalada de
  privilégio vertical trivial e generalizada a qualquer grupo customizado futuro com permissões
  amplas. Corrigido com `isAdminCaller()` (mesmo padrão de `hasCallCenterQueueWriteAccess()`):
  `accessGroupId` explícito ou `role="ADMIN"` agora exigem `ROLE_ADMIN` de verdade no chamador,
  retornando 403 caso contrário — atribuir grupo/perfil ADMIN é operação de gestão de RBAC, mesmo
  nível de exigência já usado em `/access-groups/**`.
- **Frontend**: `CreateUserModal.tsx`/`EditUserModal.tsx` ganharam um seletor "Grupo de acesso
  customizado" (populado via `GET /access-groups`, mesmo endpoint de `AccessGroups.tsx`) — opção
  padrão "usar Perfil acima" preserva o comportamento binário quando nada é selecionado. Listagem
  de usuários mostra o nome do grupo customizado abaixo do badge Perfil quando `accessGroupId > 2`
  (ids 1/2 são os grupos seed Administradores/Usuários, já refletidos pelo badge).
- Suíte completa do backend **921/921 verde** (10 novos testes, 0 regressão — a única falha é o
  flake conhecido de `ffmpeg` ausente no container Maven ad hoc). `tsc --noEmit` e `npm run build`
  do frontend Telecom limpos. Release notes `v1.93` registrada.

### ✅ Fase 14 do plano-mãe do Call Center — identidade do contato e screen pop (2026-08-15) — deployada e validada em produção
Desbloqueada pela Fase 1/AD (espelho local `ad_users` já com paginação/employeeID corretos).
Cascata de identificação (D7/D8 do plano): login de rede já autenticado > entrada falada/digitada
confirmada > ANI, contra `ad_users` — nunca consulta o AD ao vivo. Migration **V85**:
`cc_interactions.resolved_ad_sam`/`identity_source`, mesmas 2 colunas em `cc_chat_sessions`,
índices trigram (`pg_trgm`, já em uso desde a V14) para busca aproximada de nome falado contra
`display_name`, tabela `cc_identity_resolution_log` (custo de IA da transcrição) e frente
`callcenter_identidade` no Financeiro.
- `CallCenterIdentityResolver` (pacote novo `domain/callcenter/identity/`): busca exata por login
  (chat interno via JWT); busca aproximada por nome falado via trigram (`AdUserRepository.
  findBestFuzzyMatchByDisplayName`, limiar 0.3) com **confirmação falada obrigatória** antes de
  usar (fail-closed — qualquer coisa além de "sim"/"isso mesmo"/"correto" é negativa); fallback
  por ANI normalizado com o mesmo `AniNormalizer` já usado na Fase 27 (remove código do país,
  insere o 9º dígito do celular). Transcrição de áudio curto chama o Gemini direto (síncrono,
  dentro da ligação — mesmo padrão de header `x-goog-api-key`/log sem `e.getMessage()` já usado
  pelo `CallCenterNpsTranscriptionScheduler` da Fase 21).
- Novo nó de fluxo **`coletar_entrada`** (`ColetarEntradaNodeHandler`) — desbloqueia mais um dos 7
  nós que ficaram pendentes desde a Fase 5b; propriedade `identificarContato` liga a cascata acima
  durante a coleta de voz.
- Painel do agente (Desktop) ganha bloco de identidade resolvida (nome/departamento/cargo/
  gerente/e-mail/telefone, tudo já espelhado localmente) e histórico dos até 10 atendimentos
  anteriores do mesmo contato (`GET /callcenter/interactions/{id}/contact-history`).
- **2 achados CRITICAL reais corrigidos antes do deploy** (`ecc:security-reviewer`): (1) o
  endpoint de histórico de contato aceitava `resolvedAdSam` como parâmetro do próprio chamador,
  sem validar contra a interação `{id}` — qualquer agente com `PERM_READ_callcenter.desktop`
  podia enumerar histórico e o bloco completo de identidade (nome/e-mail/telefone/cargo) de
  **qualquer** contato do AD; corrigido para sempre carregar o sam da interação já persistida,
  validada contra o agente autenticado (nunca o parâmetro do chamador); (2) o widget de chat
  público (`/callcenter/chat/public/**`, sem autenticação, `allowedOriginPatterns("*")`) devolvia
  `identityResolved: true/false` na resposta HTTP quando o cliente informava um `networkLogin` —
  um oráculo de enumeração de login válido do AD corporativo para qualquer visitante anônimo da
  internet (o próprio comentário do código já dizia querer evitar isso, mas o código não impunha);
  corrigido removendo o booleano da resposta pública — a identidade fica só persistida para
  consumo interno do agente.
- **1 bug real corrigido** encontrado ao rodar a suíte: `resolveByAni` comparava o ANI só com
  dígitos crus contra `ad_users.telephone_number`, sem remover "+55"/inserir o 9º dígito — nunca
  bateria com um telefone real cadastrado no AD; corrigido reusando o `AniNormalizer` (Fase 27)
  em vez de duplicar a lógica.
- Suíte completa do backend **892/893 verde** (a única falha é o flake conhecido e não
  relacionado de `ffmpeg` ausente no container Maven ad hoc). Deployado (migration V85 confirmada
  em `flyway_schema_history`) e validado em produção via curl: `contact-history` 403 sem token,
  widget público de chat responde 503 (sem fila configurada — comportamento esperado desde a Fase
  7b, sem regressão). Release notes `v1.91` registrada.
- **Gap aceito, documentado no código**: busca trigram por nome falado sem limite de tamanho/rate
  específico no fluxo de voz (diferente do chat público, que já tem `PublicChatRateLimiter`) —
  custo leve de CPU por chamada com `identificarContato=true`, aceitável no volume atual desta
  VPS de dev.

### ✅ Fase 16 do plano-mãe do Call Center — histórico do contato e copiloto de IA para o agente (2026-08-15) — deployada e validada em produção
Desbloqueada pela Fase 14. Migration **V86**: `cc_contact_profiles` (perfil traçado por IA,
`resolved_ad_sam`/`profile_json` jsonb/custo) + `cc_contact_profile_feedback` (útil/não útil por
ação sugerida) + frente `callcenter_copiloto` no Financeiro (a de pior perfil de custo do módulo —
dispara por contato, não por gravação).
- **16.1 — histórico unificado voz+chat** (`CallCenterContactHistoryService`, pacote novo
  `domain/callcenter/copilot/`): deliberadamente diferente do `CallCenterTimelineService` (Fase
  9c.3, relatório paginado por ANI normalizado) — aqui a chave é `resolved_ad_sam` (mais precisa
  que ANI, que varia entre celular/fixo/chat do mesmo contato) e o volume é sempre pequeno
  (últimos N contatos), consulta de hot-path do atendimento, não relatório de supervisor. Cache em
  memória de 45s por sam (mesmo padrão de TTL do `UraRoutingService`).
- **16.2 — perfil de IA** (`ContactProfileGenerator`, `@Async`): chamada DIRETA ao Gemini em Java
  (não via serviço Python, apesar do plano original sugerir isso) — mesmo padrão já estabelecido
  no domínio Call Center para geração de texto (Fase 8/14/21/25: header `x-goog-api-key`, nunca
  `e.getMessage()` em log). Saída estruturada via `responseSchema` (mesmo padrão do
  `CallCenterNpsTranscriptionScheduler`); `riscoEscalonamento` sempre clampado para `[0,1]` antes
  de persistir (lição do overflow numérico de `call_insights.aderencia_script`, Fase 8). **Geração
  nunca bloqueia o atendimento**: `ContactProfileService.getOrTrigger` devolve na hora
  (`READY`/`GENERATING`/`UNAVAILABLE`) e dispara a geração em segundo plano quando ausente/vencida
  (cache de 24h, configurável); dedup por `inFlight` (`ConcurrentHashMap.newKeySet`) evita
  multiplicar chamadas ao Gemini enquanto o frontend faz polling do mesmo contato.
- **16.3 — UI**: painel "Copiloto de IA" no Desktop do Agente (`DesktopAgenteTab.tsx`) — histórico
  + resumo/sentimento/temas/risco/ações sugeridas, cada ação com botão de feedback útil/não útil.
  Aproveitado para também exibir, pela primeira vez, o bloco de identidade da Fase 14 (a nota "AD
  ainda não disponível" no componente estava desatualizada — a Fase 14 nunca tinha sido conectada
  ao frontend antes desta fase).
- **1 achado HIGH real corrigido no frontend** (`ecc:react-reviewer`): o `useEffect` que dispara o
  polling dependia de `interaction?.identity` (objeto) — como `interaction` é repolado a cada 5s
  e o backend sempre devolve um objeto novo, o efeito reiniciava a cada poll mesmo sem o contato
  mudar, resetando o painel para "Gerando perfil…" e refazendo os fetches sem necessidade;
  corrigido dependendo de `interaction?.identity?.samAccountName` (primitivo estável). 1 achado
  MEDIUM também corrigido: uma chamada de rede dentro do updater funcional de `setProfile` (efeito
  colateral impuro, arriscado sob StrictMode) — movida para uma `ref` de status lida fora do
  updater.
- **RBAC sem endpoint/resource novo**: os 3 endpoints novos (`contact-history-unified`,
  `contact-profile`, `contact-profile/feedback`, todos sob `/callcenter/interactions/{id}/**`) já
  caem no matcher genérico existente (`PERM_READ`/`PERM_WRITE_callcenter.desktop`) — mesma
  disciplina anti-IDOR da Fase 14 aplicada desde o primeiro commit: nenhum dos três aceita
  `resolvedAdSam` do chamador, sempre derivado da interação `{id}` já validada contra o agente
  autenticado (`CallCenterInteractionService.ownedInteractionWithResolvedSam`); o feedback ainda
  valida que o `profileId` pertence ao mesmo sam da interação antes de salvar. Confirmado por
  `ecc:security-reviewer`: nenhum achado CRITICAL/HIGH.
- **Gaps aceitos, documentados no código**: sem escopo por BU (mesmo padrão já aceito no restante
  do domínio); `fetchAll`/histórico não pagina no banco antes do corte em memória (aceitável no
  volume atual desta VPS de dev); cache de histórico sem eviction de chaves antigas (mesmo padrão
  já aceito no `UraRoutingService`); o nó de fluxo `agente_ia` do catálogo (ainda
  `implementado=false`) **não é escopo desta fase** — apesar de listas de pendência antigas do
  `CLAUDE.md` associarem os dois, a especificação real da Fase 16 (`modulo-callcenter-omnicanal.
  plan.md` §16.1-16.4) é só o copiloto para o agente humano, sem nenhum trabalho de motor de
  fluxo; `agente_ia` fica como item separado em aberto.
- Suíte completa do backend **912/913 verde** (17 novos testes, 0 regressão — a única falha é o
  flake conhecido de `ffmpeg`). `tsc --noEmit` e `npm run build` do `callcenter-platform/frontend`
  limpos. Deployado (migration V86 confirmada em `flyway_schema_history`) e validado via curl.
  Release notes `v1.92` registrada.

### ✅ Fase 7e do plano `.claude/plans/callcenter-fases-5-7-9.plan.md` — Telegram (long polling) (2026-08-14) — deployada e validada em produção
Último item da release 14 (7c blending + 7d anexos + 7e Telegram), fecha o canal de chat com uma
segunda prova real da premissa "um flow engine, agnóstico de canal" (a primeira foi a Fase 24,
webchat vs. bot): o Telegram passa a ser só mais um `CcChatChannel` — o mesmo `CcChatService`, o
mesmo `ChatChannelDriver`, o mesmo motor de fluxo, sem nenhuma lógica de bot duplicada.
- **D2 confirmada: long polling, nunca webhook.** `TelegramLongPollingClient` (`@Scheduled`,
  padrão estrutural de `ChatAttachmentRetentionScheduler`/`CostAlertScheduler` — erro num canal
  nunca derruba o scheduler nem afeta os demais) chama `getUpdates` com offset incremental. Nenhuma
  rota pública nova — o backend só chama para fora, a rede corporativa não precisa aceitar nada.
  Migration **V79**: `cc_chat_channels.telegram_bot_token_ref` (referência ao token, nunca o valor
  em texto puro — mesmo padrão `_TOKEN`/`_CREDENTIAL` mascarado em `GET /settings`, resolvido em
  runtime via `EnvFileStore.readRaw()`), `cc_chat_sessions.external_ref` (chat_id do Telegram) +
  índice único parcial `(channel_id, external_ref) WHERE closed_at IS NULL` (nunca duas sessões
  simultâneas abertas pro mesmo chat_id), `cc_telegram_poll_state` (offset por canal, sobrevive a
  restart sem reprocessar updates antigos).
- **Token nunca vaza em log/exceção.** A própria API do Telegram exige o token no path da URL (sem
  alternativa de header oficial) — `TelegramApiClient` só loga `e.getClass().getSimpleName()` em
  qualquer falha HTTP, nunca `e.getMessage()` nem a URI da requisição (mesma disciplina já usada
  para a API key do Gemini em `CallCenterNpsTranscriptionScheduler`/`llm.py`). Testado com
  `ListAppender` do Logback forçando um erro HTTP com o token embutido na mensagem simulada e
  confirmando que nenhum evento de log emitido o contém.
- **Idempotência por `update_id`, defensiva além do offset do Telegram**: mensagem nova de um
  chat_id sem sessão aberta cria uma `cc_chat_session` via `CcChatService.startExternalSession`
  (novo método, `customerRef = "<tipo-do-canal>-<chatId>"`, reusa o mesmo `startSession` privado —
  publica `ChatBotSessionStartedEvent` normalmente se o canal tiver fluxo de bot); mensagem de um
  chat_id já em conversa reusa a sessão (`CcChatSessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull`)
  e chama o mesmo `CcChatService.postMessage("customer", ...)` do webchat.
- **Entrega de volta ao Telegram**: novo evento `ChatAgentMessageSentEvent`, publicado por
  `CcChatService.postMessage` (agente/sistema) e `postBotMessage` (motor de fluxo) — sem custo
  para sessões webchat (nenhum listener interessado, mesmo padrão de
  `ChatCustomerMessageReceivedEvent`); `TelegramLongPollingClient` ouve o evento e, só quando a
  sessão pertence a um canal Telegram com `externalRef` preenchido, chama `sendMessage`.
- CRUD de canal (`CallCenterChatChannelController`/`Service`, RBAC herdado — mesmo
  `callcenter.chat` de sempre, nenhum resource novo): canal `telegram` exige
  `telegramBotTokenRef` na criação/edição (400 claro, nunca 500); a leitura (`ChatChannelView`)
  devolve só a referência, nunca resolve/expõe o valor real do token. Frontend
  (`callcenter-platform/frontend`, `ChatTab.tsx`) ganhou um seletor de tipo de canal
  (Webchat/Telegram) e, condicionalmente, o campo de referência do token.
- **3 achados reais corrigidos antes do deploy** (`ecc:security-reviewer` + `ecc:java-reviewer`,
  em paralelo): (1) **CRITICAL** — `telegramBotTokenRef` só era validado como "não vazio", sem
  allowlist de padrão: qualquer usuário com só `PERM_WRITE_callcenter.chat` (nível de confiança bem
  menor que `telecom.settings`) podia apontar a referência para QUALQUER chave do `.env`
  (`POSTGRES_PASSWORD`, `BACKEND_JWT_SECRET`, `INTERNAL_API_KEY`...) e o
  `TelegramLongPollingClient` resolveria e vazaria esse segredo pra um servidor externo (Telegram)
  a cada ciclo de polling — corrigido com uma allowlist por padrão
  (`^CALLCENTER_TELEGRAM_BOT_TOKEN(_[A-Z0-9_]+)?$`) validada em duas camadas: na escrita
  (`CallCenterChatChannelService`) e de novo na leitura (`TelegramLongPollingClient.resolveToken`,
  defesa em profundidade contra uma linha que tenha chegado ao banco por outra via); (2) **HIGH**
  — `onAgentMessageSent` era um `@EventListener` comum, disparado ainda dentro da transação de
  `CcChatService#postMessage`/`postBotMessage` — a chamada HTTP bloqueante ao Telegram (até 15s)
  prendia uma conexão do pool fazendo puro I/O de rede, mesma classe de achado já corrigida antes
  neste projeto (Fase 21 NPS) — corrigido virando `@TransactionalEventListener(AFTER_COMMIT,
  fallbackExecution=true)`, mesmo padrão de `ChatFlowLauncherService.onBotSessionStarted`; (3)
  **MEDIUM** — o offset só era persistido ao fim do lote inteiro: uma falha no meio do
  processamento (ex.: sessão fechada por um agente entre a consulta e o post) fazia o próximo
  ciclo reprocessar TODOS os updates do lote, inclusive os já entregues com sucesso, duplicando
  mensagem no transcript — corrigido persistindo o offset update a update.
- Suíte completa do backend **855/855 verde** (22 testes novos entre `TelegramApiClientTest`,
  `TelegramLongPollingClientTest` e os acréscimos em `CcChatServiceTest`/
  `CallCenterChatChannelServiceTest`, 0 regressão). `tsc --noEmit` e `npm run build` do
  `callcenter-platform/frontend` limpos.
- **Gaps aceitos, documentados no código** (fora de escopo desta fatia, decisão do plano): mensagem
  sem texto (foto/sticker/etc.) do Telegram é ignorada — o update ainda avança o offset, mas nada
  vira mensagem de chat; sem anexo/mídia via Telegram nesta fatia (D6/Fase 7d é webchat-only); sem
  retomada de sessão após reload do lado do cliente (mesmo gap já aceito na Fase 7b); um bot real
  do Telegram não foi configurado/testado ponta a ponta nesta VPS de dev (sem credencial real) —
  só a infraestrutura de long polling, validada com `getUpdates`/`sendMessage` mockados.
- Deployado (`docker compose up -d --build backend frontend`, migration V79 confirmada em
  `flyway_schema_history`) e validado em produção: canal Telegram sem token configurado nunca
  chama a API (log de aviso, sem erro), CRUD do canal via curl com JWT ADMIN forjado inline
  (criar/editar canal `telegram` sem `telegramBotTokenRef` → 400; com referência → 200, resposta
  nunca inclui valor de token). Release notes `v1.81` registrada.

### ✅ Fase 10 do plano Call Center Parte III (parte 2) — particionamento (2026-08-14) — deployada e validada em produção; INTERNAL_API_KEY rotacionada de novo
Usuário reverteu a posição conservadora original da Fase 10 (§10-§11 de
`.claude/plans/callcenter-fase10-seguranca-endurecimento.plan.md`) e pediu para particionar agora,
mesmo sem volume real — decisão dele, registrada no plano.
- **Migration V71**: `cc_interaction_events`/`cc_chat_messages` viraram `PARTITION BY RANGE`
  mensal (`occurred_at`/`created_at`), 36 partições (2025-01 a 2027-12) + 1 partição `DEFAULT` em
  cada, para nunca falhar um `INSERT` por falta de partição. Confirmado **0 linhas** nas duas
  tabelas antes de escrever o SQL — elimina o risco normal dessa conversão (nada a migrar). PK
  virou composto (`id, occurred_at`/`id, created_at` — exigência do Postgres para PK em tabela
  particionada); confirmado que nenhuma FK de outra tabela referencia essas duas, e as entidades
  JPA usam só `id` (globalmente único via `BIGSERIAL`) — **zero mudança de código Java**. Testada
  em transação `BEGIN/ROLLBACK` direto em produção antes de aplicar de verdade via Flyway; depois
  de aplicada, um `INSERT` de teste (revertido) confirmou roteamento correto para a partição do
  mês certo. **Gap aceito, documentado no próprio SQL**: sem job de manutenção para criar
  partições além de 2027-12 — fica para quando fizer sentido (mesmo padrão de
  `AiModelPricingSyncScheduler`).
- **`INTERNAL_API_KEY` rotacionada pela segunda vez** nesta mesma Fase 10 — a chave já rotacionada
  na fatia 1 tinha sido exposta de novo em output de comando (`grep` no `extensions.conf` gerado)
  durante a investigação da parte 1 (teste de carga). Chave nova gerada e escrita no `.env` por
  script que nunca imprime o valor; containers `backend`/`ai-agent`/`docker-helper`/`insights`/
  `asterisk` recriados + `dialplan reload`. Validado **sem nunca expor a chave em texto puro**:
  hash SHA-256 truncado idêntico entre `.env` e o `extensions.conf` gerado; curl de dentro do
  próprio container confirmou 403 sem chave e autenticação bem-sucedida com a chave nova.
- Suíte completa do backend **662/662 verde** (0 regressão — a única falha na primeira rodada foi
  o flake conhecido de `ffmpeg` ausente no container Maven ad hoc, confirmado não-representativo
  ao reinstalar o binário nesse mesmo container e rodar só aquele teste: 5/5 verde).
  `tsc --noEmit` e `npm run build` do `callcenter-platform/frontend` limpos (sem alteração nesta
  fatia — validação de zero regressão).
- Deployado (`docker compose up -d --build backend` para a migration +
  `docker compose up -d --force-recreate backend ai-agent docker-helper insights asterisk` para a
  rotação de chave + `dialplan reload`) e validado em produção — todos os 11 containers
  `healthy`. Commit `42eb9c9`, push para `origin main` e `azure main:desenvolvimento`.
- Teste de carga SIPp (parte 1 da Fase 10) **descartado por decisão do usuário em 2026-08-15** —
  não será feito. Substituído por uma recomendação de hardware para 250 agentes simultâneos (ver
  seção própria mais abaixo neste arquivo).
- **Extensão do particionamento ao fluxo/URA (migration V72)**: `cc_flow_execution_steps` (traço
  nó a nó do Flow Builder, Fase 5b) também particionada por mês (`entered_at`), mesmo padrão da
  V71. `cc_flow_executions` (uma linha por chamada) **permanece não particionada** — restrição
  técnica real: `cc_flow_execution_steps.execution_id` tem FK para `cc_flow_executions(id)`, e o
  Postgres não permite `UNIQUE(id)` isolado numa tabela particionada, o que quebraria essa FK se o
  pai fosse particionado também. `cc_flow_execution_steps` também tem um padrão de `UPDATE ...
  WHERE id=?` (fechamento de passo em `FlowExecutionTraceService`, sem a coluna de partição) —
  funciona, mas perde pruning nesse UPDATE (documentado no SQL, aceitável no volume atual).
  Validada em transação de teste reproduzindo esse UPDATE real antes e depois da aplicação via
  Flyway (migration V72). Suíte do backend 662/662 verde, `tsc --noEmit`/`npm run build` do
  `callcenter-platform/frontend` limpos.

### ✅ Hardening Docker (GID 1500 compartilhado) — backend/ai-agent/agents-backend não-root (2026-08-14) — deployado e validado em produção
Fecha de vez o débito de segurança F-HIGH da auditoria de 2026-07-02 registrado mais abaixo neste
arquivo ("Débito de segurança — 2 de 3 fechados") — os 3 containers que ainda rodavam como root
agora têm UID próprio (1501 backend / 1502 ai-agent / 1503 agents-backend) no grupo compartilhado
`voipia-app` (GID 1500), necessário porque os três precisam ler/escrever os mesmos caminhos do
host: `/opt/VoipIA/env`, `/opt/VoipIA/asterisk/config`, `fail2ban_socket`/`security_cmds`
(volumes nomeados), `media/*`, `asterisk_recordings`/`asterisk_ari_recordings`.
- Essa era uma de **3 frentes de trabalho soltas e não commitadas** deixadas por uma sessão
  anterior (código do Dockerfile já parecia pronto, mas a preparação do host estava incompleta).
- **3 lacunas reais de permissão de host encontradas e corrigidas antes do rebuild** (o grupo/GID
  já existia de uma tentativa anterior, só parte dos caminhos tinha sido ajustada): (1)
  `asterisk_ari_recordings` (volume da Fase 21/NPS, montado `:rw` pelo backend) sem grupo/escrita
  corretos — todo `RecordingFinished` da pesquisa de satisfação falharia; (2) `extensions.conf` e
  `pjsip.conf.template` `root:root 644` — edição de dialplan pela UI quebraria pro usuário
  não-root; (3) `security/config/jail.d`/`filter.d` (diretórios **e** arquivos) `root:root` —
  o mais grave: o backend nem conseguia **ler** `jail.d/asterisk.conf` (modo 640, grupo `root`),
  e a escrita (`JailConfigRepository`, via escrita atômica com `.tmp`+rename) também exige escrita
  no diretório, não só no arquivo. Todos corrigidos com `chgrp voipia-app` + `chmod 2775`/`664`.
- Validado em produção: containers `healthy`, `docker inspect --format '{{.Config.User}}'`
  confirma `backend`/`aiagent`/`agentsapi` (não root), zero "permission denied"/EACCES nos logs,
  leitura funcional confirmada via API (`GET /api/v1/security/jails` e
  `GET /api/v1/asterisk-config/rotas` retornam 200 com conteúdo real). Escrita (mutação real de
  config) não foi testada — só leitura, pra não alterar estado de produção sem pedido explícito.
- `asterisk`/`coturn`/`security`/`docker-helper` continuam root — ver seção de débito de
  segurança mais abaixo pro porquê de cada um (portas privilegiadas, `NET_ADMIN`,
  `network_mode: host`, `docker.sock`).

### ✅ Fase 27 do plano Call Center Parte III — gamificação, perfil do cliente, produtividade (2026-08-14) — deployada e validada em produção
Encerra a "camada analítica completa" do plano revisado (9c → 26 → 27) com 3 relatórios novos,
todos GET on-the-fly sem persistência/cooldown (não geram nem agregam nada caro o bastante pra
justificar — diferente da Fase 26) sob `/api/v1/callcenter/reports/{gamification,customer-profile,
agent-productivity}`, RBAC herdado do matcher genérico já existente (`callcenter.reports`), sem
migration nova.
- **Gamificação**: ranking por NPS médio (`cc_agg_agent_daily`, Fase 9b) ponderado pelo volume de
  atendidas de cada dia — nunca a média simples dos dias. **Volume mínimo configurável** (default
  5 atendidas no período): agente abaixo do mínimo fica em lista à parte, sem posição — decisão
  explícita do plano ("agente com 3 chamadas e NPS 10 não é o melhor da operação").
- **Perfil do cliente**: agrupa `cc_interactions`/`cc_chat_sessions` por identidade normalizada de
  telefone (`AniNormalizer`, novo — remove código do país, insere/reconhece o 9º dígito do
  celular). **Gap conhecido, documentado no código**: sem `resolved_ad_sam` (Fase 14, ainda
  inexistente), é o único identificador disponível — voz e chat só correlacionam quando o
  telefone informado no chat normaliza pro mesmo dígito da ligação. Top assuntos por tabulação de
  voz + categoria do Insight (mesma cadeia `cc_recordings → call_audio_files → call_insights` já
  usada na Fase 9c).
- **Produtividade do agente**: resumo (volume/TMA/NPS/ocupação, de `cc_agg_agent_daily`) +
  timeline de login/pausa/logout (`cc_agent_states`, Fase 4) + pontos fortes/de melhoria —
  **reusa `AgentReportAggregationService` (Fase 8) tal como já existe, sem nenhuma chamada de IA
  nova**: extremos de `notaPorItem` (as 3 maiores/menores médias) viram "pontos fortes"/"pontos de
  melhoria" calculados em Java, nunca narrados por LLM nesta tela.
- **3 achados reais corrigidos** (`ecc:security-reviewer` + `ecc:java-reviewer` + `ecc:react-reviewer`
  em paralelo): (1) MEDIUM — `page` negativo no endpoint de listagem do perfil do cliente
  derrubava em 500 genérico (`PageRequest.of` rejeita `page < 0`, sem handler dedicado) — corrigido
  clampando o valor; (2) MEDIUM — `displayContact` (perfil do cliente) assumia que o último
  elemento da lista era o contato mais recente, mas a query derivada não garante ordem — corrigido
  buscando o máximo explícito por `queuedAt`/`startedAt`, com teste novo provando o caso fora de
  ordem; (3) HIGH no frontend — o painel de detalhe do "Perfil do cliente" não tinha a mesma
  guarda de sequência já usada na busca paginada da mesma tela, então a resposta de um cliente
  clicado primeiro podia sobrescrever a de um clicado depois, se chegasse fora de ordem — corrigido
  espelhando o padrão `searchSeq`. 1 MEDIUM de acessibilidade também corrigido (cabeçalho de
  coluna de ação sem rótulo).
- Suíte completa do backend **638/638 verde** (23 novos testes, 0 regressão — a única falha
  observada é o flake pré-existente e não relacionado de conversão ffmpeg). `tsc --noEmit` e
  `npm run build` do `callcenter-platform/frontend` limpos.
- Deployado (`docker compose up -d --build backend frontend`) e validado em produção via curl com
  JWT forjado: RBAC correto (403 sem token nos 3 endpoints), 404 em agente/contato inexistente,
  400 em contato inválido, `page=-1` retornando 200 (não mais 500). Release notes `v1.71`
  registrada.
- **Gap aceito, documentado no código**: sem escopo por BU (mesmo padrão já aceito no Insights do
  Call Center, Fase 8, e no relatório 9c) e sem paginação em banco na varredura de período do
  perfil do cliente (aceitável no volume atual desta VPS de dev, mesmo padrão já aceito em
  `CallCenterDetailReportService#searchChats`). Aderência à escala ficou de fora, como já previsto
  no plano — não existe conceito de escala/turno no sistema ainda.

### ✅ Fase 26 do plano Call Center Parte III — relatório de qualidade (2026-08-14) — deployada e validada em produção
Agrega `CallEvaluation`/`CallEvaluationItem` (Fase 8, já computados pela IA quando a chamada foi
avaliada contra uma ficha) por escopo (agente/fila/toda a operação) e período — **sem chamada de
IA nova**, por isso sem frente própria no Financeiro. Migration V70: `cc_holidays` (calendário de
feriados compartilhado com a futura Fase 5e — "construir uma só tabela" era instrução explícita
do plano), `cc_quality_reports`, `cc_quality_report_snapshots` (mesmo padrão de
`agent_evolution_snapshots`/V39, com coluna `source` desde o início).
- **Cooldown de 5 dias úteis por escopo** (não por par supervisor+escopo, diferente do relatório
  equivalente do Insights, V39) — ADMIN isento, feriados considerados via novo overload de
  `BusinessDayCalculator` (overload original preservado, nenhum consumidor existente afetado).
  Evolução item a item contra a execução anterior no mesmo escopo. RBAC reusa
  `callcenter.reports` (mesma aba "Relatórios"), path próprio `/quality-reports`.
- **1 achado real HIGH corrigido** (`ecc:security-reviewer`, revisão combinada
  segurança+qualidade Java numa única passada por orçamento de custo): a geração já restringia
  por BU corretamente, mas a releitura (`list`/`getById`) não — um relatório agregado por um
  ADMIN (sem restrição de BU) podia depois vazar pra um leitor restrito a uma única BU. Corrigido
  persistindo as BUs efetivamente agregadas (`scoped_bu_ids`) e filtrando a releitura por
  interseção com as BUs do leitor atual — relatório gerado sem nenhuma restrição fica **oculto**
  por padrão pra leitor restrito (fail-closed). 3 achados LOW também corrigidos: log de aviso no
  fail-open de `ccRecordingId` nulo, 3 métodos de repositório mortos removidos, erro 409/404 no
  CRUD de feriados em vez de 500 genérico.
- Suíte completa do backend **615/615 verde** (14 novos testes, 0 regressão). `tsc --noEmit` e
  `npm run build` do `callcenter-platform/frontend` limpos. Deployado (migration V70 confirmada
  em `flyway_schema_history`) e validado em produção via curl: ciclo completo (gerar relatório
  GERAL, criar/remover feriado, RBAC 403 sem token). Release notes `v1.70` registrada.

### ✅ Fase 9c do plano Call Center Parte III — relatório analítico de chamada e de chat (2026-08-14) — deployada e validada em produção
Relatório linha a linha (`GET /api/v1/callcenter/reports/calls` e `/chats`, RBAC
`callcenter.reports` — mesma aba "Relatórios" da 9a/9b), sub-view nova em
`ReportsQueueTab.tsx`/`DetailReportTab.tsx` no `callcenter-platform/frontend`. Sem migration
nova — só cruza dados já persistidos: `cc_interactions` (fila/agente/NPS/tempo de fila) →
`cc_recordings.interaction_id` → `call_audio_files.cc_recording_id` (Fase 8) →
`call_insights`/`call_insight_findings` (categoria/sentimento/achados) +
`cc_flow_executions.interaction_id` → `cc_flow_execution_steps` (nó `menu_opcoes`) para "opção
escolhida".
- **"Opção escolhida" resolvida com precisão, não heurística**: `CcFlowExecutionStep.takenEdge`
  guarda o id da aresta do React Flow (não o dígito) — o dígito real vem de reabrir o grafo JSON
  da versão publicada (`CcFlowVersion.graph`, `FlowGraph.parse`), achar a aresta por id, e ler seu
  `sourceHandle` (`"opt-<dígito>"`). Nunca regex sobre o id da aresta — testado explicitamente com
  um id que não bate no padrão de dígito, pra provar que a correlação é pelo `sourceHandle`.
- **3 achados reais corrigidos** (`ecc:security-reviewer` + `ecc:java-reviewer` +
  `ecc:react-reviewer` em paralelo): (1) **HIGH** — o cache de grafo de fluxo era recriado a cada
  linha da página em vez de compartilhado por toda a página, anulando o próprio propósito do
  cache (reparsava o mesmo JSON repetidas vezes); corrigido subindo o cache pra fora do loop de
  enriquecimento; (2) **MEDIUM** — endpoints sem teto de tamanho de página (`size`), um valor
  grande virava abuso barato de consultas por um usuário já autorizado; corrigido com teto de
  100; (3) **MEDIUM** — condição de corrida no frontend: sem guarda de sequência, uma resposta de
  busca antiga (filtro trocado antes da primeira request voltar) podia sobrescrever o resultado de
  uma busca mais nova; corrigido com contador de sequência por busca.
- **Gaps aceitos, documentados no código** (mesmo padrão já aceito em outras partes do domínio
  Call Center): sem filtro de BU (mesmo gap do Insights do Call Center, Fase 8); chat sem nota NPS
  (pesquisa de satisfação não liga a `chat_session` hoje) nem busca por trecho de transcrição
  (chat não tem índice full-text); filtro "opção escolhida" e o relatório de chat fazem varredura
  completa de tabela sem paginação no banco — aceitável no volume atual, documentado pra
  revisitar quando crescer.
- Suíte completa do backend **605/605 verde** (11 novos testes, 0 regressão). `tsc --noEmit` e
  `npm run build` do `callcenter-platform/frontend` limpos. Deployado
  (`docker compose up -d --build backend frontend`) e validado em produção via curl com JWT
  forjado: `/calls` e `/chats` retornam 200 (vazio, sem interações reais nesta VPS de dev) para
  ADMIN, 403 sem token. Release notes `v1.69` registrada.

### ✅ Fase 25 do plano Call Center Parte III — IA de autosserviço no chat (2026-08-14) — deployada e validada em produção
Base de conhecimento própria do Call Center (artigos + fontes externas por URL) indexada por
embeddings locais (`insights/src/embedding_server.py`), consultada pelo nó `consultar_base` do
motor de fluxo de chat. Migration V69 (`pgvector` no PostgreSQL 16 já existente — sem serviço
novo, embeddings locais em CPU no container `insights`, que já é Python: custo de embedding
zero).
- SSRF é o risco central do cadastro de fontes externas por URL — reusa exatamente o guard já
  existente em `notifier.py`/`SettingsTestController` (bloqueio de host privado/loopback, redirect
  3xx desabilitado); falha de busca nunca invalida o índice anterior (mesma disciplina do
  `AiModelPricingSyncScheduler`).
- Nó `consultar_base`: recupera os K trechos mais próximos e o LLM responde **apenas com base
  neles**, citando o artigo — sem trecho relevante acima do limiar, escala para fila humana,
  nunca inventa resposta. Pergunta idêntica normalizada dentro de uma janela curta reusa a
  resposta em cache (recuperação vetorial local não custa token, só a geração final chama a API).
- Frente `callcenter_autosservico` no Financeiro desde o dia 1 (§5.1 obrigatória pra toda frente
  de IA nova), com indicador de taxa de contenção do bot.
- **3 achados reais corrigidos** (`ecc:security-reviewer` + `ecc:java-reviewer` +
  `ecc:react-reviewer` em paralelo): (1) HIGH — `@Transactional` no caminho interativo do chat
  segurava conexão do pool por até ~45s de I/O bloqueante (embedding+Gemini) — removido; (2)
  MEDIUM — reindexação não era atômica entre apagar e recriar chunks — corrigido extraindo
  métodos `@Transactional` só da parte de banco; (3) MEDIUM — guard de SSRF não cobria IPv6
  Unique Local Address (`fc00::/7`) — corrigido.
- Suíte completa 594/596 (as 2 falhas pré-existentes e não relacionadas de sempre — timing/
  ffmpeg). `tsc --noEmit`/`npm run build` limpos nas duas SPAs afetadas. Deployado (migration V69
  confirmada em produção, containers backend/insights/frontend saudáveis) e validado via curl:
  `GET /api/v1/callcenter/kb/{stats,articles}` respondendo 200 (vazio, nenhum artigo cadastrado
  nesta VPS de dev). Release notes `v1.68` registrada.

### ✅ Fase 24 do plano Call Center Parte III — canais de chat e flow builder de chat (2026-08-13) — deployada e validada em produção
CRUD de canais (`cc_chat_channels` — fila padrão, horário, mensagem de saudação/ausência, fluxo
de bot, migration V68) + `ChatChannelDriver`/nó `coletar_texto`, provando a premissa central do
plano-mãe ("um flow engine, agnóstico de canal") pela primeira vez. Tempo real continua por
polling (sem WebSocket, decisão já registrada na Fase 7a).
- **3 achados HIGH corrigidos antes do deploy** (`ecc:security-reviewer`/`ecc:java-reviewer`/
  `ecc:react-reviewer` em paralelo): (1) sessão de bot podia ser "ressuscitada" depois de um
  agente/ADMIN encerrar a conversa manualmente — corrigido com guarda de status `"bot"` nos três
  métodos de escrita + evento `ChatSessionEndedEvent`; (2) `ChatBotSessionStartedEvent` publicado
  ainda dentro da transação de `startSession` podia disparar a thread do fluxo antes do commit
  (falha intermitente na primeira mensagem do bot) — listener virou
  `@TransactionalEventListener(AFTER_COMMIT)`; (3) nó `coletar_texto` sem aresta de saída nunca
  chamava `driver.end()`, prendendo a sessão em `status="bot"` para sempre — corrigido espelhando
  o padrão `followOrEnd` já usado por `menu_opcoes`. 2 MEDIUM também corrigidos (thread daemon
  sem pool/limite trocada por `ExecutorService` limitado; validação/CORS/acessibilidade do
  formulário de canal).
- Backend 568/568 verde (18 testes novos), `tsc --noEmit`/`npm run build` do
  `callcenter-platform/frontend` limpos. Deployado (migration V68 confirmada) e validado em
  produção via curl: `GET /callcenter/chat/channels` 200 para ADMIN, 403 sem token.

### ✅ Fase 22 do plano Call Center Parte III — painel do agente: métricas e históricos (2026-08-13) — deployada e validada em produção
`CallCenterDesktopService`/`Controller` (3 endpoints — resumo/histórico/pausas — sob
`currentAgent()`, nenhum aceita `agentId` do chamador). Reusa dados já existentes das Fases
4/8/21/23, sem migration nova. RBAC reusa `callcenter.desktop` (já existente da Fase 13).
- **Regra D21 fechada estruturalmente**: o histórico é somente leitura de artefato já
  existente — nunca enfileira, dispara ou reprocessa nada. Chamada ainda não processada aparece
  como `EM PROCESSAMENTO`, sem botão de ação. O serviço nem depende do serviço de ingestão de
  Insights, e há teste explícito (`verify(..., never()).save(any())`) que impede a regressão de
  alguém reintroduzir o disparo depois "pra melhorar a experiência".
- 5 achados reais corrigidos (1 HIGH — race condition sem cleanup no `useEffect` das sub-abas; 2
  MEDIUM — erro engolido silenciosamente, falta de acessibilidade; 2 LOW cosméticos).
- **Achado não-bloqueante, aceito por ora**: o link de gravação no histórico exige a permissão
  `callcenter.gravacoes` (diferente de `callcenter.desktop`) — um agente sem essa segunda
  permissão recebe 403 ao tentar ouvir a própria gravação (fail-closed, não é vulnerabilidade, é
  uma lacuna funcional/decisão de RBAC não pedida nesta fase).
- `mvn test` 532/532 verde (7 novos). Frontend: 3 sub-abas novas dentro do `DesktopAgenteTab.tsx`
  já existente, reusando `AuthedAudio.tsx`.

### ✅ Fase 21 do plano Call Center Parte III — pesquisa de satisfação/NPS (2026-08-13) — deployada e validada em produção
Maior fase do plano (complexidade G) — entregue por completo, incluindo a capacidade de
gravação real via ARI (inédita no projeto) e a chamada direta do backend Java à API do Gemini
(antes só o serviço Python fazia STT/LLM).
- **4 modos, escolhidos na criação da pesquisa (D17)**: `DTMF_SIMPLES`/`DTMF_MULTI` (dígito por
  pergunta, zero custo), `FALADA_IA` (resposta falada, gravada e depois transcrita+classificada
  por IA de forma **assíncrona** — nunca durante a chamada), `DTMF_COMENTARIO` (nota por dígito +
  comentário gravado opcional, transcrito só sob demanda, D21, nunca automático). Migration
  **V65**: `cc_surveys`/`cc_survey_questions`/`cc_survey_responses`, `cc_queues.survey_id` +
  `nps_alert_enabled`/`nps_alert_threshold`, `cc_interactions.nps_score`,
  `cc_agg_queue_daily`/`cc_agg_agent_daily.avg_nps_score`, mais o scope `callcenter_nps` no
  Financeiro (CHECK constraint de `financeiro_cost_alerts`).
- **Disparo pós-atendimento sem motor novo**: `Queue(${EXTEN},F(nps,${EXTEN},1))` (nos dois
  contextos `_5XXX`) manda o cliente para o contexto `[nps]` só quando o **agente** desliga
  primeiro — `Stasis(callcenter,nps-${EXTEN})` reusa o mesmo app ARI do Flow Builder;
  `AriEventListener` distingue pelo prefixo `"nps-"` no argumento e chama
  `CallCenterNpsExecutionService` em vez do motor de fluxo. O nó `pesquisa_satisfacao` (antes
  `implementado=false`) também passou a funcionar dentro de um fluxo comum — os dois caminhos
  compartilham `CallCenterSurveyRunner`, sem duplicar lógica de pergunta/resposta/nota.
- **Capacidade de gravação ARI nova** (`AriClient.record`, `AriRecordingTracker` espelhando
  `AriPlaybackTracker`, evento `RecordingFinished`) — grava no spool do próprio Asterisk
  (`/var/spool/asterisk/recording`, volume novo `asterisk_ari_recordings` compartilhado com o
  backend) e o backend move o arquivo pronto para `/opt/VoipIA/media/gravacao/nps` (mesma
  raiz de mídia permanente da Fase 20), apagando o original do spool.
- **Nota desnormalizada + alerta em tempo real**: `cc_interactions.nps_score` é recalculada a
  cada resposta com nota (`CallCenterSurveyRunner.recomputeInteractionNpsScore`) — dispara
  Telegram na hora se a fila tiver `nps_alert_threshold` configurado e a nota vier igual ou
  abaixo dele, sem esperar um job diário (diferente do padrão de `CallCenterSlaAlertService`,
  decisão desta fase por a pesquisa já ser um evento discreto, não uma taxa contínua).
- **Transcrição/classificação assíncrona** (`CallCenterNpsTranscriptionScheduler`, a cada 2 min):
  chama o Gemini direto via `WebClient`, reaproveitando a mesma chave já configurada em
  IA→Provedores (`AiProviderService.getRawKey("gemini")`) — nunca durante a chamada. Custo real
  calculado via `AiModelPricingRepository` (mesmo cálculo de `InsightsCostService`) e visível na
  nova aba "Pesquisas (NPS)" do Call Center, seção "Alerta de gasto de IA".
- **4 achados reais corrigidos antes do deploy** (`ecc:security-reviewer` + `ecc:java-reviewer`,
  em paralelo): (1) **CRITICAL** — a chave do Gemini ia na query string (`?key=...`); qualquer
  erro HTTP (`WebClientResponseException`) inclui a URI completa na mensagem, vazando a chave em
  log de erro — mesma classe de achado já corrigida antes neste projeto (API key do Gemini em
  `llm.py`), agora fechada também no primeiro ponto do backend Java que chama o Gemini direto
  (movida para header `x-goog-api-key`, e o catch genérico do scheduler não usa mais
  `e.getMessage()`); (2) **MEDIUM** — o áudio gravado nunca era de fato movido do spool
  transiente do ARI para `media/gravacao/nps` (a intenção estava documentada no
  `docker-compose.yml` mas a lógica nunca foi escrita) — corrigido, com teste real de arquivo
  temporário confirmando a movimentação; (3-4) **HIGH** (2×) — `CallCenterSurveyRunner.run` e
  `CallCenterNpsTranscriptionScheduler.processOne` estavam `@Transactional` envolvendo I/O
  bloqueante (até ~65s esperando o cliente na chamada; até 30s de chamada HTTP ao Gemini),
  prendendo uma conexão do pool sem fazer trabalho de banco na maior parte do tempo — corrigido
  removendo a anotação do nível externo (cada `save()` já é transacional por conta própria via
  Spring Data, e o recálculo de nota tem sua própria transação curta).
- Suíte completa do backend **491/491 verde** (24 novos, 0 regressão), `tsc --noEmit` e
  `npm run build` da SPA do Call Center limpos. Deployado (`docker compose up -d --build backend
  frontend` + `docker compose up -d --build asterisk`, migration V65 confirmada, dialplan reload
  sem erro) e validado em produção via curl: CRUD de pesquisa funcionando ponta a ponta (criada e
  desativada de novo — sem hard delete, só `active=false`), scope `callcenter_nps` do Financeiro
  respondendo 200.
- **Gap aceito, documentado**: nenhum teste desta fase envolveu tráfego de voz real (mesma
  ressalva já registrada para todo o motor ARI/Stasis desde a Fase 5b) — nomes de campo do
  evento `RecordingFinished` não confirmados contra este Asterisk real.

### ✅ Fase 23 do plano Call Center Parte III — chamadas de saída (2026-08-13) — deployada e validada em produção
Agente que disca um número externo pelo próprio softphone (ramal 4xxx) passa a gerar uma
`cc_interactions` como qualquer chamada receptiva — `direction` (`INBOUND`|`OUTBOUND`, migration
**V64**, default `INBOUND` preenche todo o histórico sem backfill manual) é a única diferença de
schema; `queue_id` já era nullable desde a Fase 4, nenhuma mudança necessária ali.
- **Correlação por CURL do dialplan, não por evento AMI de canal**: diferente do receptivo
  (`CallCenterAmiEventListener`, eventos `QueueCallerJoin`/`AgentConnect`/…), o início/fim da
  chamada de saída chega via CURL do próprio `extensions.conf.template` (novo contexto `_X.` em
  `ramais-internos`, mesmo padrão já usado por `queue-recording-config`/`recordings`) — decisão
  deliberada para não depender de nomes de campo AMI de canal (`Newchannel`/`DialBegin`) nunca
  validados contra este Asterisk. `CallCenterOutboundCallService.start`/`end` (novos) criam e
  encerram a interação; `answeredAt` é calculado de volta a partir de `${ANSWEREDTIME}` (duração
  da conversa, único dado que o Asterisk garante em chamada atendida).
- **Agregado 9b ganha corte por direção**: `cc_agg_agent_daily` (migration V64) ganha
  `outbound_placed`/`avg_outbound_talk_seconds` — sem isso, `findByAgentIdAndQueuedAtBetween`
  (que traz os dois sentidos) misturaria chamada de saída no `answered`/`avg_talk_seconds` do
  receptivo assim que existisse a primeira linha `OUTBOUND`. O agregado 9a (fila) não precisou de
  mudança: por ser sempre iterado por `queueRepository.findByActiveTrue()`, uma interação sem fila
  nunca chega lá.
- **3 achados reais corrigidos antes do deploy** (`ecc:security-reviewer` + `ecc:java-reviewer`,
  em paralelo): (1) **CRITICAL** — `${ANSWEREDTIME}` chega vazio (não ausente) em chamada não
  atendida (BUSY/NOANSWER/CANCEL/CONGESTION); o binding automático do Spring para `Integer` com
  string vazia lançava 400 antes de chegar ao service, deixando o agente travado em
  `EM_ATENDIMENTO` para sempre em toda chamada de saída não atendida — corrigido recebendo
  `String` no controller e parseando defensivamente no service; (2) **HIGH** —
  `/api/v1/internal/**` não tinha matcher próprio em `SecurityConfig`, caindo no
  `anyRequest().authenticated()` genérico: **qualquer JWT comum de usuário do Telecom** (sem
  nenhuma permissão de Call Center) já bastava para chamar os endpoints internos como se fosse o
  próprio Asterisk — gap pré-existente (afetava também `/internal/callcenter/recordings` e
  `/internal/ura-routing`), fechado de uma vez com `hasAuthority("ROLE_INTERNAL")` explícito; (3)
  **HIGH** — o padrão `_X.` deixava qualquer ramal do contexto `ramais-internos` (não só agente
  4xxx — inclusive 1001/1002 de teste) discar de saída pelo tronco sem limite algum, mesmo quando
  o backend não registrava a interação — corrigido com `GotoIf($[${REGEX("^4[0-9][0-9][0-9]$",
  ${CALLERID(num)})}]?...)` rejeitando (`Hangup(21)`) ramal fora da faixa de agente antes do
  `Dial()`. Um **MEDIUM** também corrigido: `provisionAra` não fixava `callerid` no `PsEndpoint`
  do ramal de agente (diferente dos ramais estáticos 1001/1002/9001/9002, que já têm) — sem isso,
  um cliente SIP com as credenciais de um ramal podia enviar outro `CallerID` no INVITE e atribuir
  a chamada de saída a outro agente; corrigido fixando `callerid` no provisionamento.
- Suíte completa do backend **466/466 verde** (12 novos, 0 regressão), `tsc --noEmit` e
  `npm run build` do `callcenter-platform/frontend` limpos. Deployado (`docker compose up -d
  --build backend frontend` + `docker compose up -d --build asterisk`, migration V64 confirmada
  em `flyway_schema_history`, dialplan reload sem erro) e validado em produção via curl: JWT
  comum de USER agora recebe 403 nos endpoints internos (antes recebia 200 — regressão de
  segurança fechada), `X-Internal-Key` continua funcionando para o Asterisk, e `outbound-end` com
  `answeredSeconds` vazio responde 200 (não mais 400).
- **Nota operacional desta sessão**: o valor de `INTERNAL_API_KEY` apareceu sem querer no output
  de um comando de validação (grep sem redação no `extensions.conf` gerado) — recomendado
  rotacionar a chave (`.env` → `INTERNAL_API_KEY`, mais `dialplan reload` e restart do backend)
  na próxima janela de manutenção, por precaução.

### ✅ Fase 13 do plano Call Center Parte III — softphone do agente (2026-08-13) — deployada e validada em produção
Softphone WebRTC do shell do Telecom passa a registrar com a **credencial do próprio ramal do
agente de Call Center** (4xxx), não mais uma senha única compartilhada — e ganha um painel de
chamada fixo no Desktop do Agente da SPA do Call Center, arquitetura de **um único UA SIP** por
sessão de navegador (D10-A do plano).
- **Credencial (D9-A)**: `GET /callcenter/agentes/me/sip-credentials` resolve sempre pelo agente do
  usuário logado (`currentAgent()`, nunca aceita id do chamador) — RBAC `callcenter.desktop`,
  limitado a 10 requisições/min por usuário (`SipCredentialsRateLimiter`, mesmo padrão em memória
  de `PublicChatRateLimiter`), auditado a cada leitura. `POST /agentes/{id}/rotate-secret` (RBAC
  `callcenter.ramais`, não `callcenter.desktop` — rotacionar não é ação do próprio agente sobre
  si mesmo) gera novo secret e espelha no auth ARA (`PsAuth`) na mesma transação.
- **`Softphone.tsx`**: lógica JsSIP extraída para `hooks/useSipPhone.ts`, reusável. Ordem de
  resolução de credencial: (1) agente de Call Center → ramal 4xxx + secret próprio; (2) claim
  `extension` do JWT + `VITE_SIP_PASSWORD` (ramais legados 9xxx); (3) nenhum dos dois → estado
  explícito `'no-extension'`, **sem** o fallback silencioso para `'9001'` que existia antes (o
  achado de segurança que motivou esta fase — o softphone registrava silenciosamente no ramal de
  outra pessoa).
- **Painel fixo no Desktop do Agente (D10-A)**: `useShellBridge` ganhou as mensagens `callState`
  (shell→iframe) e `callAction` (iframe→shell), com a mesma tripla validação de origem já usada
  pelo resto da ponte. Quando a SPA do Call Center roda **embutida** no shell, o painel só reflete
  o estado do softphone do shell e envia comandos via `callBridge.ts` (pub/sub em memória,
  `Softphone.tsx` ↔ `CallCenterPage.tsx`, mesma janela) — nunca instancia o próprio UA. Quando a
  SPA roda **direta** em `/callcenter/` (sem shell), instancia o próprio `useSipPhone`, agora com
  um parâmetro `enabled` para nunca haver dois UAs registrados no mesmo ramal.
- **1 achado real CRITICAL/HIGH corrigido antes do deploy** (`ecc:security-reviewer` +
  `ecc:react-reviewer`, unânimes): a primeira versão de `DesktopAgenteTab.tsx` chamava
  `useSipPhone()` sem condição — como o endpoint ARA do ramal usa `maxContacts(1)` com
  `removeExisting("yes")`, abrir a aba Desktop do Agente embutida no shell registraria um segundo
  UA que **evictava o registro real do softphone do shell**, podendo derrubar uma chamada em
  andamento. Corrigido passando `enabled=!isEmbedded` ao hook — achado de ambas as revisões, já
  corrigido no código antes de qualquer uma delas terminar.
- **1 achado MEDIUM corrigido**: dois `setTimeout` que retornavam o estado para `'idle'` (em
  `endSession` e no handler de reconexão de WebSocket) não eram rastreados em ref e não eram
  limpos no cleanup do efeito — podiam disparar `setState` após desmonte. Corrigido com
  `idleTimerRef`, nas duas cópias do hook.
- **1 achado LOW corrigido**: `useShellBridge.ts` aceitava o payload de `callState` só checando
  truthiness, sem validar o shape — agora exige `typeof data.payload.status === 'string'`, mesmo
  padrão já usado por `navigate`/`tabChanged` no mesmo arquivo.
- Suíte completa do backend **459/459 verde** (3 novos testes do rate limiter, 0 regressão),
  `tsc --noEmit` e `npm run build` das duas SPAs limpos. Deployado
  (`docker compose up -d --build backend frontend`) e validado em produção via curl com JWT
  forjado: `GET /sip-credentials` 403 sem token, 404 (não 500) para usuário sem vínculo de agente;
  `POST /rotate-secret` 403 sem token; rate limit confirmado (10 requisições passam, a 11ª retorna
  429). Validação visual no navegador não foi feita (sem acesso a browser nesta sessão).

### ✅ Fase 20 do plano Call Center Parte III — padronização de mídia em /opt/VoipIA/media/ (2026-08-13) — deployada e validada em produção
Move a mídia de produção do Call Center e do Insights (gravação de voz, transcript de chat, upload
de "análise sob demanda") de caminhos fora do repo (`/opt/gravacoes/*`, `/opt/audio_upload`) para
`/opt/VoipIA/media/{gravacao,chat,anuncios,sobdemanda}`, sob a raiz do repositório.
- **Risco central tratado primeiro**: mídia sob a raiz do repo é dado de cliente/PII, e o repo é
  espelhado em GitHub e Azure DevOps — `media/` nunca pode ser comitado. `.gitignore` (`media/*` +
  `!media/.gitignore`) verificado com `git check-ignore -v` **antes** de qualquer bind mount
  existir, mais `scripts/git-hooks/pre-commit-media-guard.sh` instalado como
  `.git/hooks/pre-commit` (segunda camada, recusa qualquer arquivo staged sob `media/`).
- **1 achado real de segurança (HIGH) corrigido antes do deploy**: a primeira versão do hook usava
  `--diff-filter=ACM`, que exclui renomeação (`git mv <arquivo-já-rastreado> media/...` passava
  sem bloqueio, sem precisar de `-f`) — corrigido para `ACMR`, validado em repositório git isolado
  reproduzindo o cenário exato do achado.
- **`add.txt` também pedia `/opt/VoipIA/media/sobdemanda`** para uploads de análise sob
  demanda — mapeado para o portal do supervisor (Quality Management, V40,
  `INSIGHTS_UPLOAD_AUDIO_DIR`, antes `/opt/audio_upload`), incorporado à fase por ser a mesma
  classe de problema.
- **1 bug real de leitura, descoberto antes de migrar dado nenhum**: diferente da gravação de voz
  (`resolveAudioFile` usa só o nome-base, indiferente ao prefixo persistido), o streaming de
  upload sob demanda (`InsightsController.pathRelativeToBase`) compara o caminho persistido contra
  o **baseDir atual** para preservar o subcaminho `{batchId}/{arquivo}` — sem um `UPDATE` real (não
  cosmético) de `call_audio_files.wav_path`, todo upload já enviado pararia de ser encontrado após
  mover os arquivos físicos. Migration **V63** cobre isso, escopado só a `source='upload'`.
- **1 bug real de execução, encontrado ao migrar o único arquivo real desta VPS**:
  `rsync -a origem/ destino/` sincroniza os atributos do próprio diretório de destino contra a
  origem, sobrescrevendo o `chown root:voipia-app` + `chmod 2770` (setgid) aplicado antes da
  cópia — bug latente desde a Fase 11 (nunca disparado lá porque a origem estava vazia).
  `scripts/migrar-gravacoes.sh` (generalizado para N pares origem/destino) corrigido reaplicando a
  permissão depois do `rsync`.
- Suíte completa do backend **452/452 verde** (0 regressão). Deployado
  (`docker compose up -d --build backend insights frontend` + `docker compose up -d asterisk` para
  regenerar `extensions.conf`) e validado em produção: dialplan gerado já com `REC_DIR` novo,
  `dialplan reload` sem erro, e o streaming do único áudio real desta VPS (upload sob demanda)
  funcionando ponta a ponta pelo novo caminho (200, tamanho correto).

### ✅ Fase 19 do plano Call Center Parte III — tela de Gestão (ranges + NPS global) (2026-08-13) — deployada e validada em produção
Primeira fase do plano revisado em `.claude/plans/callcenter-parte-iii-revisado.plan.md`, que
confrontou o pedido novo do usuário (`add.txt`) com o código já entregue — 7 telas pedidas de novo
já existiam (confirmado na Fase 0-III via Chrome headless, sem nenhuma exceção JS) e saíram do
escopo; esta fase cobre uma lacuna real.
- **Migration V62** (`cc_settings`, chave/valor genérico) + `CcSettingsService` — as faixas de
  ramal de agente (`4000-4999`), fila (`5000-5999`) e fluxo (`6000-6999`), antes constantes fixas
  em `CallCenterAgentService`/`CallCenterQueueService`/validação de `CallCenterFlowService`, agora
  são lidas de configuração, com o valor atual como default (deploy sem configurar se comporta
  igual a antes). **Mudar o range nunca realoca ramal existente** (decisão do usuário, D20) — a
  tela só avisa quantos ramais ativos ficaram fora da faixa nova.
- **Interruptor global de pesquisa de satisfação (NPS)** em `cc_settings` — desligado, nenhuma
  fila pesquisa; será consumido pela pesquisa de satisfação por chamada numa fase futura.
- **Correção transversal do padrão de erro**: `findById` de agente, fila e fluxo, e o `update` de
  skill, passaram de `IllegalArgumentException` (caía no catch-all e virava 500 genérico) para
  `ResponseStatusException(404)` — mesmo padrão já usado em `CcChatService`.
- **1 achado real de segurança corrigido antes do deploy** (`ecc:security-reviewer`): a validação
  de range aceitava faixa negativa (`start=-1000, end=-1` passava porque, em Java, `-1000 % 1000
  == 0`), permitindo a um usuário com `PERM_WRITE_callcenter.config` persistir uma faixa
  inutilizável e travar para sempre a alocação de ramal/fila/fluxo daquele tipo — corrigido com
  piso explícito (`start >= 1000`).
- **3 achados reais de qualidade corrigidos no frontend** (`ecc:react-reviewer`): botão "Salvar"
  do modal de range sem `disabled` (permitia enviar `NaN`→`null` ao backend sem aviso claro);
  banner de aviso sem auto-dismiss; non-null assertion numa leitura que pode ser `undefined`.
- Suíte completa do backend **451/451 verde** (0 regressão), `tsc --noEmit` e `npm run build` da
  SPA do Call Center limpos. Validado em produção via curl com JWT forjado inline (GET 200 para
  ADMIN, 403 sem token; PUT com range inválido/colidindo com o Telecom → 400 claro) e validação
  visual via Chrome headless (tela renderiza sem exceção JS).

### ✅ Submenu Insights/Agentes na Sidebar do Telecom (2026-08-01) — deployada e validada em navegador headless
Pedido do usuário: Insights e Agentes eram itens *folha* na Sidebar — clicar abria um iframe em
tela cheia (`InsightsPage.tsx`/`AgentesPage.tsx`) com a sidebar própria de cada SPA embutida,
duas navegações laterais em sequência. Passaram a ser `NavParent` com submenu indentado, mesmo
padrão já usado pelo Financeiro (6 abas em Insights, 8 em Agentes) — **só** quando acessados
via login pela página principal do Telecom; login direto em `/insights` ou `/agents` continua
com a sidebar própria intacta, sem nenhuma mudança. Plano completo em
`.claude/plans/insights-agentes-submenu-telecom.plan.md`.
- **Sem remontar o iframe a cada troca de aba** (recarregaria a SPA inteira): as N abas de cada
  módulo mapeiam pro mesmo elemento JSX (`<InsightsPage tab=…>`/`<AgentesPage tab=…>`) em
  posição fixa no `App.tsx` do Telecom — React reconcilia, só a prop `tab` muda. A troca de aba
  viaja por `postMessage` bidirecional (`useShellBridge.ts`, um hook por SPA — duplicação
  intencional, mesmo precedente de `client.ts`/`AuthedAudio.tsx` entre as 3 SPAs Vite
  independentes): handshake `ready` no boot, `navigate` shell→iframe, `tabChanged`/`alertCount`
  iframe→shell. Cada SPA detecta `window.self !== window.top` pra decidir se esconde a própria
  sidebar — zero query param, zero mudança no `src` do iframe.
- **RBAC sem migration**: cada filho do submenu já usava resource_key existente
  (`insights.*`/`agents.*` + `telecom.insights_link`/`agents_link`) — nenhum resource novo no
  `ResourceCatalog.java`.
- **4 bugs reais encontrados e corrigidos antes do deploy**, por revisão estática
  (`ecc:react-reviewer`/`ecc:security-reviewer` em paralelo) e por validação em navegador de
  verdade: (1) CRITICAL — `useShellBridge()` chamado depois do early-return de login nos dois
  `App.tsx` das SPAs, quebrando a ordem de hooks do React no instante do login/logout dentro da
  SPA embutida; (2-3) HIGH — closure obsoleta em `onTabChange`/`onAlertCount` do lado do shell
  (faltava `useRef`) e falta de checagem de `event.source` nos 4 listeners `postMessage` (só
  validavam `origin`); (4) HIGH, achado só na validação dinâmica — `lastSentTab`/
  `lastReceivedTab` começavam `null`, a SPA postava a aba *default* no boot e desfazia o clique
  que abriu o módulo (ex: clicar "Servidores" voltava pro "Dashboard").
- **Segunda rodada** (`/code-review`) achou 4 issues **pré-existentes**, fora do escopo desta
  feature mas corrigidos por serem bugs reais: `CallAudioFileRepository.findBySwitchCallId`
  retornava `Optional` sem `switch_call_id` ter índice único (uma duplicata lançava
  `IncorrectResultSizeDataAccessException` dentro da transação de ingestão, abortando
  transcrição/insights já pagos em tokens Gemini) — virou `List`; `TransferResolutionService`
  ganhou guard de auto-correlação nos dois sentidos (2 testes novos, suíte final 260/260 verde);
  `InsightsTab.tsx` — `isAdmin` saiu de escopo de módulo pra `useAuthSession()` dentro do
  componente (ficava presa no role da sessão anterior ao trocar de usuário sem reload da SPA).
  **Não corrigido, deliberadamente**: divergência de campo de data (`callStarttime` vs
  `processedAt`) entre `findCosts`/`summarizeByMonth` do `InsightsCostService` — é decisão
  proposital e testada (fix anterior do bug "custo IA acumulado zerado"), reverter reintroduziria
  esse bug.
- Validação em Chrome headless manual + CDP puro via WebSocket nativo do Node 22 (Chrome
  DevTools MCP falhou de novo nesta VPS com "Target closed") — 15 checks automatizados + 4
  screenshots confirmaram indentação idêntica ao Financeiro, iframe nunca remonta, sync
  SPA→menu funcionando, guard `event.source` bloqueando spoof same-origin, e a regressão pedida
  (login direto preserva sidebar própria).
- Release notes `v1.46` registrada.

### ✅ Insights → Chamadas: campos do XML Verint + descoberta de transferência (2026-07-24) — deployada e validada em produção
Pedido do usuário: mapear todos os campos do `.xml` da Verint (hoje só 10 viravam coluna, o resto
ficava só em `xml_raw`) e montar um MVP da tela de Chamadas com eles, mais uma feature adicional
pedida no meio do caminho — descobrir para qual ramal/atendente uma chamada foi transferida. Plano
completo em `.claude/plans/insights-chamadas-campos-xml.plan.md` (10 decisões do usuário registradas,
inclusive duas rodadas de revisão de densidade de coluna/filtro após o mockup ficar grande demais).
- **13 colunas novas em `call_audio_files`** (migration V43) — grupos A (Identificação), B
  (Qualidade) e C (Técnico/Auditoria, sempre admin-only e sempre só no detalhe, nunca vira coluna).
  **Tabela final: 16 colunas** (10 existentes + 6 novas: Nº do cliente, Ramal, ANI, Quem desligou,
  Ramal destino, Atendente destino) — bem menos que a primeira tentativa (~30), depois que o
  usuário revisou campo a campo contra a recomendação do assistente.
- **ANI por direção**: em chamadas `Outbound` (Efetuadas), a coluna ANI exibe o `dnis` bruto em vez
  do `ani` bruto (que seria o ramal do próprio atendente) — regra só de exibição
  (`InsightsAudioFileDto.resolveDisplayAni`), o dado bruto persistido nunca é alterado.
- **Descoberta de transferência (tabela nova `call_transfer_events`)**: o XML **não tem** o
  ramal/número de destino como campo direto — só dá pra saber correlacionando com outra gravação
  já ingerida (`globalcallid` de um `Begin_Call` == `switch_call_id` de outra chamada).
  `TransferResolutionService` tenta nos dois sentidos (origem→destino e destino→origem, ordem de
  ingestão não é garantida) a cada ingestão/backfill. **Taxa de acerto confirmada em produção: 0/7**
  no lote inicial — a gravação de destino ainda não existe no recorte atual de `/opt/audio`; "Não
  identificado" é o estado normal esperado, documentado como tal na UI (não é bug).
- Backfill metadata-only (`insights/src/backfill_metadata.py`) rodou nas 42 chamadas `done`
  existentes sem reprocessar STT/LLM (0 falhas).
- RBAC: grupo C (+ `targetSwitchCallId` no histórico de transferências e no filtro de mesmo nome)
  só para ADMIN — testado em produção via curl com token forjado ADMIN vs USER comum: campo
  ausente do JSON (não `null`) e filtro `targetSwitchCallId` silenciosamente ignorado para não-ADMIN.
- Testes novos: 17 no backend (`mvn test` 257/257 verde) + 15 no Python (`pytest insights/`,
  fixtures reais de `/opt/audio` incluindo XML sintético com 2 transferências em sequência).
  Frontend (`insights-platform/frontend`): `tsc --noEmit` + `npm run build` limpos.
- Release notes `v1.43` registrada.

### ✅ Novo módulo Financeiro — centraliza Custo de IA (2026-07-20) — implementado, pendente deploy/validação em produção
Pedido do usuário: um módulo Financeiro no menu, com submenu URA / Insights / **Análise Sob
Demanda** (nome escolhido no lugar de "Análise Individual" — é a frente antes chamada
"Custo IA (Envios)"), reunindo as telas de Custo IA/Dashboard de Custos que hoje viviam
espalhadas (aba do Módulo URA + abas da SPA Insights). Plano completo em
`.claude/plans/modulo-financeiro.plan.md`.
- **Movido, não duplicado**: as abas de custo saíram do `ModuloURA.tsx` e da SPA Insights
  (`insights-platform/frontend`) — `Financeiro.tsx` (novo, `frontend/src/components/`) as
  centraliza, reaproveitando os componentes já existentes (`CostsTab`/`CostsDashboardTab`
  para URA; `InsightsCostsTab`/`InsightsCostsDashboardTab` portados da SPA Insights para o
  Telecom, parametrizados por `basePath`, para Insights/Análise Sob Demanda). Nenhum endpoint
  de dados novo — só mudou a permissão que protege cada rota `/costs/**` e o frontend que
  consome.
- **RBAC**: namespace novo `financeiro.ura`/`financeiro.insights`/`financeiro.envios`
  (migration `V41__financeiro_rbac_namespace.sql`, copia concessões de `telecom.modulo1`/
  `insights.costs`/`insights.uploads` sem apagar as origens, que continuam protegendo o
  resto de suas telas). `insights.costs` foi **removido** do `ResourceCatalog.java` — não
  protegia mais nada além do custo. 4 pontos de sincronia atualizados (`ResourceCatalog`,
  `SecurityConfig`, `Sidebar.tsx`, `AccessGroups.tsx`).
- **Submenu expansível na Sidebar** — padrão novo, inédito até esta entrega (a Sidebar do
  Telecom era 100% plana); implementado com um item `NavParent`/`children[]` genérico, sem
  lib nova.
- **Alerta de gasto em USD por frente** (pedido adicional do usuário): aba "Alerta de
  Gasto" em cada submenu — limite mensal configurável, verificado diariamente por
  `CostAlertScheduler` (migration `V42__financeiro_cost_alerts.sql`, espelha
  `AiModelPricingSyncScheduler`) e notificado via `TelegramBotService.sendMessage` (já
  existente) — no máximo uma vez por mês por frente. `CostAlertServiceTest` (6 testes)
  cobre limite atingido/não atingido/já notificado/desabilitado.
- **Dashboard principal**: novo gráfico `LineChart` (recharts) com evolução mensal das 3
  frentes no mesmo gráfico + novo `KpiCard` de custo acumulado do mês corrente somando as
  3 frentes — busca só as frentes com permissão de leitura, tolera 403 por frente sem
  quebrar o restante do Dashboard.
- Backend: `mvn compile` limpo, suíte completa 232 testes (só a falha pré-existente e
  não-relacionada de `ClientControllerTest` — já conhecida antes desta sessão). Frontend:
  `tsc --noEmit` e `npm run build` limpos nas duas SPAs (Telecom e Insights).
- **Pendente**: deploy real (`docker compose up -d --build backend frontend`, migrations
  V41/V42 aplicam no boot) e validação visual no navegador (submenu, as 3 abas por frente,
  config de alerta, Dashboard) — sem acesso a browser nesta sessão.

### ✅ Agentes migrado de React UMD (single-file) para Vite+TS (2026-07-19) — implementado, pendente deploy/validação em produção
Plano completo em `.claude/plans/agentes-migracao-vite-spa.plan.md` (9 fases). O antigo
`agents-platform/frontend/index.html` (1688 linhas, React 18 UMD sem build step, `js/` com
`react`/`react-dom` vendorizados manualmente) virou um projeto Vite+React+TypeScript completo,
mesmo padrão da SPA de Insights — 8 telas em `src/components/` (`DashboardTab`, `AgentsTab` +
`AgentForm`, `ServersTab`, `KnowledgeTab`, `LogsTab`, `AlertsTab`, `SecretsTab`,
`LlmSettingsTab`), `Sidebar.tsx`/`Login.tsx`/`useAuthSession.ts` copiados/adaptados de
`insights-platform/frontend`. **Backend FastAPI não mudou nada** — `agents-platform/backend/`
continua exatamente como estava; o novo `api/client.ts` tem **dois** clientes axios porque
Agentes fala com dois backends: `api` (agents-backend, `baseURL:'/agents'`, sem envelope de
resposta, paginação offset/limit, erros em `{detail: string|array}`) e `telecomApi` (backend
Java, só para login e para obter o token de streaming do WebSocket de alertas — Agentes não tem
login nem refresh-token próprios).
- **3 correções confirmadas com o usuário** (além da troca de tecnologia): (1) login de Agentes
  passa a suportar 2FA (antes recusava mesmo com o usuário tendo ativado); (2) Base de
  Conhecimento/Secrets/Config. IA passam a esconder botões de escrita de quem só tem leitura
  (antes só Agentes/Servidores faziam esse gating — o backend sempre bloqueou certo via
  `require_permission`, era só a UI que não escondia); (3) gráfico de disponibilidade por agente
  no Dashboard passa a usar `recharts` (antes eram barras de progresso CSS puro).
- `frontend/Dockerfile` ganhou um 3º estágio de build (`agents-builder`, espelhando
  `insights-builder`) — `Caddyfile`/`frontend/nginx.conf` **não mudaram** (o roteamento por path
  já era compatível, achado confirmado na pesquisa antes de implementar).
- **Pendente**: deploy real (`docker compose build/up frontend`) e validação manual no navegador
  (login com 2FA, WebSocket de alertas, as 8 telas, CRUDs) — sem acesso a browser nesta sessão.

### ✅ Insights virou SPA independente (2026-07-19) — implementado, pendente deploy/validação em produção
Plano completo em `.claude/plans/insights-spa-independente.plan.md` (5 fases) e memória
`asteriskia_insights_spa_independente_plan`. Segue o mesmo padrão do módulo Agentes: novo frontend
Vite próprio (`insights-platform/frontend/`) servido em `/insights` pelo mesmo nginx do frontend
Telecom (`frontend/nginx.conf` — `location /insights/`; `Caddyfile` — `@insights-ui`), com o item
"Insights" do menu Telecom virando um iframe (`InsightsPage.tsx`, espelho de `AgentesPage.tsx`).
Backend **não mudou** — a SPA consome `/api/v1/insights/**` direto no mesmo Spring Boot. RBAC
migrou de um resource único (`telecom.insights`) para namespace granular por aba (`insights.calls`/
`dashboard`/`processing`/`costs`) + `telecom.insights_link` só pro item de menu (migration V37,
preserva permissões já concedidas). Os 6 componentes `Insights*.tsx` antigos do Telecom foram
deletados (migrados para a SPA); `AuthedAudio.tsx` foi copiado, não movido — é compartilhado com
URA/Alertas. `tsc --noEmit` limpo nas duas SPAs e `npm run build` da SPA de Insights ok;
**faltou validar**: `mvn compile` do backend (Maven não disponível no ambiente desta sessão) e o
deploy real (`docker compose up -d --build backend frontend caddy`, depois `curl -I
https://app.voiphash.com.br/insights/` e teste de login/abas/áudio na SPA).

### ✅ Feature: tela Insights — transcrição/análise de IA do call center Verint (2026-07-17) — deployada e validada em produção
Módulo novo e apartado do domínio Asterisk (sem FK com `call_records`/`uras`) — analisa gravações
`.wav`+`.xml` do sistema corporativo de gravação Verint em `/opt/audio` (diretório compartilhado
com outros serviços). Plano completo em `.claude/plans/insights-transcricao-chamadas.plan.md` e
memória `asteriskia_insights_feature`.
- ✅ Migration `V35__call_insights.sql` (4 tabelas, full-text `tsvector`+GIN), serviço Python
  `insights/` (novo container `asteriskia-insights`, 172.16.7.18, sem porta própria), backend Java
  `domain/insights/`, frontend (tela "Insights" na Sidebar entre URA e Conectividade) — tudo
  deployado (`docker compose up -d insights backend frontend`) e validado com dados reais.
- ✅ **Fila inicial de 42 chamadas reais processada 100%** (27 baixa / 7 alta / 4 urgente / 4 média
  criticidade, 284 achados). Streaming de áudio confirmado tocável (`ffmpeg` transcodifica G.729A →
  PCM no `InsightsController.getAudio`; `backend` monta `/opt/audio:ro`).
- ✅ **2 bugs reais encontrados e corrigidos durante a validação em produção**: (1) `numeric field
  overflow` em `call_insights.aderencia_script` — Gemini retornou valor fora de 0-1, corrigido com
  clamp defensivo nos dois lados (Python + Java); (2) `@NotEmpty` em `IngestInsightsRequest.segments`
  rejeitava com HTTP 400 chamadas com transcrição de 0 segmentos (áudio curto/silencioso) — sem fix
  causaria loop infinito de reprocessamento pra chamadas genuinamente sem fala; trocado por
  `@NotNull`. Ambos recompilados/rebuildados/redeployados na hora, sem downtime dos demais serviços.
- Release notes `v1.27` já registrada — descrição confere com o comportamento real validado.

### ✅ Auditoria full-stack pós-RBAC (2026-07-02) — 33/33 achados corrigidos, deployado e testado
4 agentes `security-reviewer`/`react-reviewer` em paralelo (Java fora do RBAC, Python ai-agent+agents-platform,
os dois frontends, infraestrutura) acharam 33 problemas; achados de infra confirmados ao vivo em produção
(`ss`, `iptables -t nat`, `pg_hba.conf`). Ver memória `asteriskia-rbac-granular-feature` e o relatório completo
em https://claude.ai/code/artifact/b04225b4-fef1-4c3c-95f1-9951de5389c9.
- ✅ **3 CRITICAL corrigidos**: `AiProviderController` sem controle de acesso (`SecurityConfig.java` — matchers
  `/api/v1/ai/**` reusando `telecom.settings`); `install.sh` não gerava senha dos ramais SIP
  (`RAMAL_*_PASSWORD` hardcoded/vazio) — agora gera via `gen_pass` e injeta em `VITE_SIP_PASSWORD`;
  Postgres publicado em `0.0.0.0:5433` — `docker-compose.yml` agora vincula a `127.0.0.1`.
- ✅ **11 HIGH corrigidos** (deployado em produção, commits deb77ee+0f3d94e+f045af4): RCE via SSH com
  `PERM_WRITE_agents.agents` (bloqueado pra não-ADMIN em `agents.py`); SSRF em `notifier.py` e
  `SettingsTestController` (host privado/loopback bloqueado + redirect 3xx desabilitado no
  `RestTemplate`/`aiohttp` — resíduo aceito: DNS rebinding/TOCTOU não coberto, mitigar exigiria pin
  de IP na conexão real); upload/delete de knowledge base sem autorização (`require_permission`);
  senha SIP em texto plano em `GET /users` (endpoint dedicado `GET /{id}/extension-password` sob
  demanda — **nota**: continua devolvendo a fórmula fictícia `"webrtc"+extensão+"pass"`, não a senha
  real do `.env`, pendência pré-existente não resolvida por este fix); mount RW dos scripts do
  container `security` (restrito a `security/state/`); credencial TURN visível via
  `docker top`/`docker inspect Config.Cmd` (movida pra `environment:` + injeção em runtime —
  resíduo aceito: ainda visível via `docker inspect Config.Env`, mesmo nível de `POSTGRES_PASSWORD`);
  API key do Gemini vazava via query string e mensagem de erro (`llm.py` — agora em header, erro
  genérico pro usuário); vazamento de segredos com sufixo `_CREDENTIAL` no `GET /settings`
  (`SettingsService.java`); injeção de seção INI em `asterisk.conf` via `banaction`/valores de jail
  (`SecurityController.java`); áudio de alertas sem header de autenticação (`ModuloAlertas.tsx`,
  extraído junto com `ModuloURA.tsx` pro componente compartilhado `AuthedAudio.tsx`).
- ✅ **10 MEDIUM + 4 LOW corrigidos** (deployado em produção): SSRF em teste de
  integração Jira/Zabbix (já coberto pelo fix de SSRF acima); injeção de fórmula CSV na exportação
  de chamadas (`ReportController.esc()` — prefixa `=+-@` com apóstrofo); `writeSettings` sem
  validação de chave/valor (`SettingsService.java` — regex de identificador + strip de `\r\n`);
  `docker-helper` não validava `services` em `/compose/up` (allowlist `_ALLOWED_COMPOSE_SERVICES`);
  DSN com credencial podia vazar em mensagem de exceção (`executor.py` — regex de redação
  incondicional); ações de mutação sem tratamento de erro em `ModuloConectividade.tsx`,
  `ModuloAlertas.tsx`, `MasterData.tsx`, `Users.tsx` (try/catch + alert, padrão já usado no resto do
  frontend); 401 handler do agents-platform que nunca recarregava (corrigido junto do fix de HIGH
  do `_apiFetch`); senha AMI hardcoded como fallback removida (`StatsController`/
  `AsteriskConfigController`); faixa de porta RTP divergente entre `Dockerfile`/config
  real/`CLAUDE.md` (corrigida pra `16000-16500/udp`, que já era o valor real usado); bug garantido
  de `TypeError` em `ai-agent/src/providers/gemini.py` se a ferramenta de function-calling fosse
  ativada (schema e assinatura de `_execute_tool` divergiam do real, unificado reusando
  `gemini_service.py`); ReDoS em `SecurityController.testRegex` (timeout de 2s via thread
  interrompível); leitura síncrona de disco no hot-path de voz (`ai-agent/src/config.py` — cache do
  `.env` com TTL de 60s); acessibilidade — `aria-label` em ~40 inputs sem label associado
  (`agents-platform/frontend/index.html` + 5 componentes TSX) e `rel="noopener noreferrer"` em 2
  links `target="_blank"`; `SecurityController.java` refatorado de 881 para 541 linhas, extraindo
  `FailToBanClient`/`AsteriskAclService`/`JailConfigRepository`/`SecurityFileUtils` (comportamento
  100% preservado, validado por `security-reviewer` linha a linha contra o diff original).
- Todo o lote (HIGH + MEDIUM + LOW) passou por `security-reviewer` + `code-reviewer` em paralelo,
  duas rodadas (uma por lote), antes de commitar — achados reais das próprias revisões (bypass de
  regex sem `Pattern.DOTALL`, SSRF por redirect HTTP não revalidado, sufixo de segredo faltando,
  falha silenciosa de promise, redação de DSN por substring exata) foram corrigidos antes do commit
  final. Ver memória `asteriskia-rbac-granular-feature` para o histórico completo.

### 🟡 Importantes
- `SuporteController` cria issues reais no Jira via function calling da IA (tool `abrir_protocolo_suporte`)
- Swagger/OpenAPI foi removido do projeto (dependência springdoc retirada do pom.xml)

### 🟡 Controle de acesso por BU (2026-07-05) — cadastros/Chamadas/Conectividade cobertos; Alertas Zabbix decidido **sem** BU
Usuário ganhou BU obrigatória (`user_business_units`) e o JWT carrega a claim `bu`
(`BusinessUnitContext`, authorities `BU_<id>`) — ADMIN sempre vê tudo. Escopo aplicado em:
Cadastros (Cliente/Operação/BU — itens sem BU ficam visíveis a todos, já que a BU é opcional
nesses cadastros), Chamadas (`CallRecordService`, via `uras.business_unit_id`) e Conectividade
(`number-tests`/`test-results`, via `NumberTest.businessUnit`, já obrigatória).
- **Decisão de produto definitiva (2026-08-15): Alertas Zabbix nunca terá segmentação por BU.**
  Confirmado pelo usuário — todo alerta recebido do Zabbix é tratado como um universo único,
  sem distinção de operação/BU, para sempre (não é um gap a fechar depois, é o comportamento
  definido). `AlertCall`/`AlertService.triggerAlert` continuam sem filtrar por operação, só
  percorrendo os contatos de plantão por prioridade — isso deixa de ser um item de pendência.
  `AlertContact.operationId` (opcional, nunca conectado ao filtro) pode ser removido numa limpeza
  futura de baixa prioridade, mas não bloqueia nada.
- Usuários pré-existentes (antes da migration V26) foram migrados com `access_indeterminate=true`
  e vinculados a todas as BUs ativas, para não perder acesso retroativamente.
- ✅ **Insights do Call Center — gap de BU fechado em 2026-08-15** (commit `a859bfd`):
  `CallCenterInsightsController` (`/calls`, `/calls/{id}`, `/calls/{id}/audio` — a superfície com
  conteúdo real, transcrição/áudio) agora filtra por `BusinessUnitContext`, via novo
  `InsightsSpecifications.restrictedToBusinessUnits` (subquery Criteria até
  `cc_recordings.business_unit`, já que `call_audio_files.ccRecordingId` é um `Long` cru sem
  relação JPA). Fail-open documentado para gravação sem `ccRecordingId`/sem BU atribuída (mesmo
  padrão de `CallRecordService`); registro fora do escopo sempre 404 (nunca 403). Insights
  (Verint) permanece deliberadamente sem escopo de BU (decisão de produto já tomada, sem mudança).
  `/processing` (status/nome de arquivo) ganhou o mesmo filtro logo em seguida (commit `7ee536b`,
  extensão trivial via a mesma Specification).
- ✅ **`/dashboard` do Insights do Call Center — gap de BU fechado em 2026-08-15**: as 4 queries
  JPQL de agregado (`CallInsightRepository.countByCriticidade/countByCategoria`,
  `CallInsightFindingRepository.countByTipo`, `CallEvaluationRepository.averageNotaByAgent/
  countFailed`) e `CallAudioFileRepository.countBySourceAndBusinessUnit` (total geral) ganharam
  parâmetro `businessUnitIds` (nullable) com o mesmo fail-open embutido na própria query
  (`:businessUnitIds IS NULL OR caf.ccRecordingId IS NULL OR caf.ccRecordingId IN (subquery)`) —
  `InsightsQueryService.dashboard(source, businessUnitIds)`.
- ✅ **Relatório 9c (`/calls`, `/chats` e exportação Excel/PDF/agendamento) — gap de BU fechado em
  2026-08-15**: `CallCenterDetailReportService.searchCalls` filtra via novo
  `CcInteractionSpecifications.restrictedToBusinessUnits` (predicado direto — `cc_interactions`
  já tem `business_unit_id`, sem subquery); `searchChats` aplica o mesmo fail-open no filtro em
  memória (`cc_chat_sessions.business_unit_id`). `CallCenterReportsController` resolve o escopo
  nos 6 pontos (calls, chats, calls/export.xlsx, calls/export.pdf, chats/export.xlsx,
  chats/export.pdf). **Gap do agendamento (Telegram/e-mail) fechado em 2026-08-15, migration
  V88**: `CcReportSchedule` ganhou `businessUnitIds` (tabela associativa
  `cc_report_schedule_business_units`, mesmo padrão N:N de `user_business_units`/
  `client_business_units`) — congelado na criação do agendamento
  (`CallCenterReportScheduleController.create`, via `BusinessUnitContext.currentBusinessUnitIds()`
  no momento da requisição, já que a execução roda depois em background sem
  `SecurityContext`) e propagado por `CallCenterReportScheduleService.buildExport` na hora de
  gerar o arquivo — vazio significa "sem restrição" (agendamento criado por ADMIN), mesma
  semântica usada no resto do domínio. **Achado extra corrigido junto**: POST/PUT/DELETE em
  `/api/v1/callcenter/reports/schedules/**` não tinham matcher de RBAC próprio no
  `SecurityConfig` — caíam no `anyRequest().authenticated()` genérico do fim da cadeia, então
  **qualquer usuário autenticado** (não só quem tem `PERM_WRITE_callcenter.reports`) conseguia
  criar/ativar/desativar/excluir agendamento; corrigido com matcher explícito exigindo
  `PERM_WRITE_callcenter.reports`. Suíte completa do backend 965/966 verde (2 testes novos, 0
  regressão — a única falha é o flake conhecido de `ffmpeg` ausente no container Maven ad hoc).
  Deployado (`docker compose up -d --build backend`, migration V88 confirmada em
  `flyway_schema_history`) e validado em produção via curl: POST sem token e com JWT de usuário
  comum sem a permissão retornam 403 (antes desta correção passariam); ADMIN cria agendamento com
  sucesso (`businessUnitIds: []`, esperado — sem restrição); registro de teste removido em
  seguida. Release notes `v1.94` registrada.
- ✅ **Alertas Zabbix — decisão de produto definitiva (2026-08-15): nunca terá segmentação por
  BU.** Não é mais um gap a fechar (ver seção "Controle de acesso por BU" acima para o detalhe).

### ✅ Fase 8 do módulo Call Center — Insights (pipeline de IA) (2026-08-07) — deployada e validada em produção
Reaproveita integralmente o pipeline de Insights (Verint) — STT/diarização/análise de
sentimento/achados — aplicado às gravações de fila do Call Center (`cc_recordings`), sem
duplicar lógica de negócio. Plano completo em `.claude/plans/modulo-callcenter-omnicanal.plan.md`
(seção Fase 8).
- **Ingestão push-based, não polling de filesystem**: diferente do desenho original do plano
  (`discovery.py` varrendo `/opt/gravacoes/audio`, caminho renomeado na Fase 11 do plano —
  antigo `/opt/telecom/gravacao`), `CallCenterRecordingService.registerInsights`
  já correlaciona a gravação com `cc_interactions` (agente/fila/ANI) no momento do `ingest` e
  enfileira direto no backend — mais confiável que a correlação por XML usada no Verint, e sem
  falha nunca derruba a resposta do CURL do dialplan (só loga).
  Migration **V54**: `call_audio_files.cc_recording_id` (vínculo de volta à gravação de origem) +
  nova frente de custo "callcenter" no Financeiro (mesmo padrão da V42).
- **Mascaramento de dado sensível** (`insights/src/masking.py` — CPF, cartão, telefone) aplicado
  antes de qualquer chamada ao LLM, retroativo também aos fluxos Verint/upload já existentes (não
  só Call Center) — nunca chega ao modelo de linguagem em texto puro.
- **5 telas no menu do Call Center**, RBAC granular `callcenter.insights.*` espelhando o
  namespace `insights.*`: Chamadas, Dashboard de Tendências, Processamento (`InsightsChamadasTab`/
  `InsightsDashboardTab`/`InsightsProcessamentoTab`, `CallCenterInsightsController`); Fichas de
  Qualidade — **somente leitura** (`ScorecardsViewTab`, `callcenter.insights.scorecards` como
  autoridade alternativa no mesmo `GET /insights/scorecards` global — a configuração da ficha
  nunca foi duplicada, é intencionalmente global, o Call Center nunca escreve); Relatórios de
  performance por atendente (`ReportsTab`, `CallCenterAgentReportController` em
  `/api/v1/callcenter/insights/reports`).
- **Migration V55** — `agent_performance_reports.source` (verint|callcenter): sem essa coluna, um
  atendente com o mesmo nome em Verint e Call Center teria os dois universos de chamadas
  agregados no mesmo relatório. `source` foi propagado por todo o threading de
  `AgentReportService`/`AgentReportAggregationService` e pelas queries `*ForAgentPeriod` de
  `CallEvaluationRepository`/`CallEvaluationItemRepository`/`CallInsightFindingRepository` — a API
  `/api/v1/insights/reports` (Verint) não mudou de contrato, só passou a fixar `source="verint"`
  internamente. `AgentReportServiceTest` (6 testes) cobre especificamente o isolamento por
  origem — `getById`/`list`/`evolution` nunca vazam relatório ou identidade (404, não 403) de uma
  origem para a outra, mesmo para ADMIN.
- **Gap conhecido, aceito por ora**: `agent_evolution_snapshots` (histórico de evolução navegável)
  não tem coluna `source` — um ADMIN pode ver pontos de evolução de Verint e Call Center juntos se
  o `agentName` coincidir entre os dois universos (não-ADMIN não tem esse problema, pois só vê
  pontos dos próprios relatórios, já filtrados por origem). Mesmo padrão de gap já aceito em BU
  (Alertas Zabbix, Insights do Call Center acima).
- Suíte completa do backend validada em container Maven com cache offline (`mvn -o test`,
  sem acesso à internet nesta VPS) — 391/391 verde, nenhuma regressão. `tsc --noEmit` e
  `npm run build` do `callcenter-platform/frontend` limpos.
- Release notes `v1.54` já registrada e corrigida (a primeira redação dizia "ainda sem tela
  própria" — desatualizada pelo trabalho posterior nesta mesma sessão).
- Deployado (`docker compose up -d --build backend frontend insights`, migrations V54/V55
  aplicadas no boot, confirmadas em `flyway_schema_history`) e validado em produção via curl com
  JWT forjado: endpoints novos retornam 200 para ADMIN e 403 sem token/sem permissão granular.
  Validação visual no navegador não foi feita (sem acesso a browser nesta sessão).

### ✅ Fase 7a do módulo Call Center — base interna do canal de chat (2026-08-07) — deployada e validada em produção
Primeira fatia da Fase 7 (canal de chat) — **deliberadamente sem widget público exposto à
internet ainda** (decisão tomada com o usuário): o esquema de autenticação anônima pro cliente
final (diferente do JWT de ramal usado hoje) fica pra uma fatia 7b futura, com mais tempo de
análise de segurança. Esta fatia entrega o modelo de dados, o roteamento interno e um simulador
de cliente restrito a ADMIN pra validar o pipeline ponta a ponta.
- **Reaproveitamento deliberado, sem duplicar domínio**: `cc_chat_sessions.queue_id` aponta
  direto pra `cc_queues` (mesma fila de voz roteia chat, discriminado pelo canal da sessão — sem
  fila paralela); `disposition_id` reusa o catálogo global `cc_dispositions` (Fase 4); o agente
  autenticado é resolvido via `CallCenterAgentStateService.currentAgent()` (mesmo mecanismo do
  resto do domínio `callcenter`); `CcInteraction` (estritamente de voz, tem `channelUniqueId` do
  Asterisk) não foi reaproveitada — a timeline unificada voz+chat é trabalho da Fase 9.
- **Modelo de roteamento**: "claim" explícito (o agente puxa uma sessão da fila que está
  `Disponível` pra assumir) — não é o motor de distribuição automática (ringall/ARI) usado em
  voz. Blending (limite de chats simultâneos, precedência voz×chat) é escopo da Fase 7 completa,
  não desta fatia.
- Migration **V56**: `cc_chat_channels`, `cc_chat_sessions`, `cc_chat_messages`,
  `cc_canned_responses`. `CcChatService` (`backend/.../domain/callcenter/chat/`) nunca confia em
  `senderType`/nome do remetente vindo do chamador para mensagens de agente/sistema — só
  `CallCenterChatController` (fixa `senderType="agent"`) e `CallCenterChatTestController`
  (`ROLE_ADMIN` puro, `senderType="customer"`) podem publicar mensagem, cada um só no seu papel.
- RBAC: `callcenter.chat` (leitura/escrita granular) protege o canal real;
  `/api/v1/callcenter/chat/test/**` (simulador de cliente) é `ROLE_ADMIN` puro, sem
  `resource_key`, matcher posicionado antes do genérico (mesma ordem de `ramal-secret`) —
  **nunca exponha esse controller a clientes reais**, é ferramenta de dev/QA para validar o
  pipeline antes do widget público da Fase 7b.
- Frontend: aba "Chat" nova no `callcenter-platform` (`ChatTab.tsx`) — fila/minhas
  conversas/thread/respostas rápidas/tabulação, com painel "Simulador de cliente (dev)" visível
  só para ADMIN. Atualização em tempo real por polling nesta fatia (a SPA não tem client STOMP
  genérico ainda — fica pra quando o volume justificar).
- `CcChatServiceTest` (8 testes) cobre os gates de segurança: claim rejeita agente fora de
  Disponível ou sessão já assumida; mensagem de agente rejeita sessão inativa ou agente que não é
  o dono; encerramento rejeita quem não é o dono nem ADMIN.
- Suíte completa validada em container Maven com cache offline — 399/399 verde (8 novos, 0
  regressão). `tsc --noEmit` e `npm run build` do `callcenter-platform/frontend` limpos.
- Deployado e validado em produção via curl com JWT forjado: ADMIN 200 no simulador, USER/sem
  token 403. Validação visual no navegador não foi feita.

### ✅ Fase 7b do módulo Call Center — autenticação anônima e widget público do chat (2026-08-08) — deployada e validada em produção
Fecha a lacuna deixada em aberto pela Fase 7a: agora o widget de chat público (cliente final,
sem login) pode existir de verdade, com um esquema de autenticação **separado** do JWT de staff.
Desenho aprovado pelo usuário antes da implementação (superfície nova exposta à internet).
- **Token de sessão, não de identidade**: `JwtService.generateChatCustomerToken`/
  `validateChatCustomerToken` — mesmo padrão já usado pelo token de streaming (claim `scope`
  distinta, `chat_customer`), mas validade de 2h (dura a conversa inteira, não só abre uma
  conexão) e sem nenhuma claim `role`/`perm`/`bu` — esse token nunca ganha autoridade RBAC de
  staff, só autoriza ações na `sessionId` que carrega (comparada contra o `{id}` da URL a cada
  chamada).
- **Fila fixa por configuração**: o cliente não escolhe a fila (evita enumeração/abuso) — resolve
  de `app.callcenter.chat.public-queue-id` (`CALLCENTER_CHAT_PUBLIC_QUEUE_ID` no `.env`, vazio por
  ora — não há fila real cadastrada nesta VPS de dev). Sem essa config, o endpoint responde 503
  claro, nunca 500. Canal fixo `webchat` (migration **V57**, distinto do `internal_test` da
  Fase 7a).
- **Rate limiting em memória** (`PublicChatRateLimiter`, sem dependência nova) — 5 sessões/10min
  por IP, 30 mensagens/min por sessão, janela deslizante sincronizada por chave. Gap aceito:
  chaves nunca são removidas do mapa (memória cresce com IPs únicos ao longo do tempo de uptime)
  — aceitável na escala desta VPS de dev; não escala pra múltiplas réplicas do backend (exigiria
  Redis), mesma decisão já registrada sobre volume real ir para servidor dedicado.
- **Extração de IP real** confia em `X-Forwarded-For`/`X-Real-IP` só quando a conexão direta vem
  do container `caddy` (único reverse proxy da stack) — mesmo padrão já usado em outros pontos do
  código, replicado aqui em vez de extraído para utilitário compartilhado (evita acoplar o
  controller público a filtros de autenticação de staff).
- **CORS — 2 bugs reais encontrados e corrigidos só na validação em produção** (revisão estática
  não pegou nenhum dos dois): (1) duas entradas sobrepostas em `CorsRegistry`
  (`WebMvcConfigurer.addCorsMappings`) fazem o Spring MVC **combinar** as duas configurações pra
  qualquer request sob a rota pública — combinar `allowCredentials=true` (regra geral) com
  `allowedOrigins=*` (regra do widget) é uma combinação inválida, rejeitada com 403 "Invalid CORS
  request"; (2) a correção via `CorsFilter` avulso como `@Bean` (sem `@Order`) tem precedência
  baixa na cadeia de filtros do Spring Boot — a cadeia do Spring Security roda primeiro e barrava
  o preflight OPTIONS de qualquer rota autenticada com 403, antes do `CorsFilter` responder.
  Solução final: um único `CorsConfigurationSource` (`AppConfig`) que decide a config inteira por
  request (branch manual por path — nunca combina duas configs parciais), consumido por
  `SecurityConfig` via `http.cors(cors -> cors.configurationSource(...))` — integra o CORS DENTRO
  da cadeia de segurança, que já sabe liberar preflight antes da autorização. `/api/v1/callcenter/
  chat/public/**` usa `allowedOriginPatterns("*")`/`allowCredentials(false)` (sem cookie
  envolvido); todo o resto da API mantém `app.cors.allowed-origins`/`allowCredentials(true)`
  exatamente como antes — confirmado com curl real (preflight de origem externa permitido só na
  rota pública, bloqueado no resto; preflight da origem legítima do próprio frontend continua
  liberado; login real continua funcionando).
- **Widget** `frontend/public-widget/callcenter-chat-widget.js` — JS puro embeddável via
  `<script>`, sem build step, sem framework: botão flutuante, polling a cada 3s, `customerRef`
  via `localStorage`. Mensagens renderizadas com `textContent` (nunca `innerHTML` de conteúdo do
  usuário) — sem risco de XSS via texto de cliente/agente. Sem retomada de sessão após reload,
  sem anexos, sem indicador de digitação, sem WebSocket em tempo real pro cliente — tudo isso
  fica pra Fase 7 completa.
- Testes novos: `JwtServiceTest` (6 casos do token de chat) + `PublicChatRateLimiterTest` (7
  casos, incluindo independência entre buckets de IPs/sessões diferentes). Suíte completa
  411/411 verde (12 novos, 0 regressão) validada em container Maven com cache offline.
- **Ainda fora de escopo**: WhatsApp Cloud API/Telegram (exigem credenciais externas que o
  projeto não tem — mesmo gap já registrado para o Jira).
- Deployado (migration V57 confirmada em `flyway_schema_history`) e validado em produção via
  curl: 503 sem fila configurada, rate limit e RBAC comportando-se como esperado, CORS correto
  nos 3 cenários acima. **Pendente**: configurar `CALLCENTER_CHAT_PUBLIC_QUEUE_ID` com uma fila
  real quando existir, e validação manual do widget embutido numa página de teste — sem acesso a
  browser nesta sessão.

### ✅ Fase 9a do módulo Call Center — agregado diário e relatório de fila de voz (2026-08-08) — deployada e validada em produção
Primeira fatia da Fase 9 (Relatórios analíticos) — **deliberadamente só fila de voz**: agregados
de agente/fluxo/chat, relatório de omnicanalidade/timeline unificada, exportação Excel/PDF e
agendamento por e-mail/Telegram ficam para fatias 9b/9c futuras (mesmo padrão de fatiamento das
Fases 7a/7b/8).
- Migration **V58**: `cc_agg_queue_daily` (um registro por fila/dia, upsert via índice único,
  nunca acumulado incrementalmente) — recebido/atendido/abandonado, ASA (`avg_wait_seconds`),
  aproximação de TMA (`avg_talk_seconds` — só tempo de conversação, sem hold/ACW somados, porque
  ACW hoje é estado do agente em `cc_agent_states`, não da interação; juntar isso com precisão é
  trabalho de uma fatia futura), nível de serviço (`service_level_pct`, reusando
  `cc_queues.timeout_seconds` já existente em vez de inventar configuração de SLA nova).
- `CallCenterQueueAggregationScheduler` consolida o dia anterior toda madrugada (mesmo padrão do
  `AiModelPricingSyncScheduler`) + reprocessamento manual sob demanda de um intervalo
  (`ROLE_ADMIN` puro, limite de 400 dias contra reprocessar anos por engano).
- `CallCenterReportsQueryService` agrupa os agregados diários em semana/mês/ano **ponderando
  médias e nível de serviço pelo volume de atendidas de cada dia** — nunca a média simples das
  médias diárias, que distorceria períodos com volumes bem diferentes entre si. Comparação entre
  dois períodos calcula o delta de cada indicador.
- RBAC: `callcenter.reports` (leitura); `/callcenter/reports/reprocess` é `ROLE_ADMIN` puro, sem
  `resource_key`, matcher posicionado antes do genérico.
- Frontend: aba "Relatórios" nova no `callcenter-platform` (`ReportsQueueTab.tsx`) — tabela por
  granularidade + comparação entre períodos, sem gráfico (`recharts` não é dependência deste
  app, mesma decisão já registrada em `InsightsDashboardTab.tsx`).
- `CallCenterQueueAggregationServiceTest` (7 testes) cobre os casos de borda do cálculo (SLA
  dentro/fora, abandonada não entra em ASA/TMA, fila sem interação gera registro zerado,
  reprocessamento rejeita intervalo grande demais).
- Suíte completa validada em container Maven com cache offline — 418/418 verde (7 novos, 0
  regressão). `tsc --noEmit` e `npm run build` do `callcenter-platform/frontend` limpos.
- Deployado (migration V58 confirmada em `flyway_schema_history`) e validado em produção via
  curl: reprocessamento manual funciona de ponta a ponta, RBAC correto (ADMIN 200, USER/sem token
  403 no `/reprocess`), consulta de relatório retorna `{}` corretamente (não há filas cadastradas
  nesta VPS de dev).

### ✅ Validação visual em navegador — Fases 7a/7b/8/9a do Call Center (2026-08-08)
Workaround já documentado (Chrome DevTools MCP falha nesta VPS) — Chrome headless manual + CDP
puro via WebSocket do Node 22, token ADMIN forjado em runtime (nunca persistido em arquivo).
16 telas do `callcenter-platform` verificadas (screenshot + console/erros de página/requisições
falhas) — todas renderizam sem tela em branco e sem exceção JS.
- **1 bug real encontrado e corrigido**: `CallCenterAgentStateService.currentAgent()` (Fase 4,
  pré-existente) e `CallCenterSupervisionActionService.currentSupervisorUser()`/`findAgent()`
  lançavam `IllegalStateException`/`IllegalArgumentException` não tratadas pelo
  `GlobalExceptionHandler` — qualquer usuário sem vínculo de agente (ex: um ADMIN que administra
  o sistema mas não é atendente) recebia 500 genérico ao abrir o Desktop do Agente ou a aba de
  Chat (Fase 7a), sem mensagem útil. Como não há nenhum agente cadastrado nesta VPS de dev
  (`SELECT count(*) FROM cc_agents` = 0), **isso quebrava essas duas telas para qualquer usuário
  hoje**, não só um caso extremo. Corrigido para `ResponseStatusException` (404, mesma convenção
  já usada em `CcChatService`/`CallCenterQueueAggregationService`) — o frontend já tratava bem o
  erro (tela cai graciosamente em estado "Offline"/"Nenhuma conversa ativa", confirmado por
  screenshot antes e depois do fix). 1 teste ajustado, suíte completa 418/418 verde.
- **Achado cosmético, corrigido em seguida**: a aba "Fluxos" mostrava a descrição estática "sem
  execução real ainda" — desatualizada desde a Fase 5b (execução real via ARI/Stasis já existe).
  Ajustado e deployado.
- Confirmado que "Insights — Relatórios" (Fase 8, relatório de performance por atendente) e
  "Relatórios" (Fase 9a, agregado de fila) coexistem sem conflito de rota/label na Sidebar,
  apesar do nome parecido.

### ✅ Fase 9b do módulo Call Center — agregado diário de agente de voz (2026-08-08) — deployada e validada em produção
Segunda fatia da Fase 9, seguindo o padrão exato da 9a. **Deliberadamente fora desta fatia**:
agregado de fluxo/URA, agregado de chat, aderência à escala (não existe conceito de escala/turno
no sistema ainda), rechamada 24h/7d, top motivos de tabulação e transferências — ficam para uma
fatia 9c futura.
- Migration **V59**: `cc_agg_agent_daily` — volume/TMA de `cc_interactions` (mesma fonte da 9a);
  ocupação/disponibilidade de `cc_agent_states` (Fase 4), somando a fração de cada período de
  estado que cai dentro do dia agregado — um período pode cruzar a meia-noite ou ainda estar
  aberto (`endedAt` null = "vale até agora"), nunca é só "duração do período inteiro". `occupancy_
  pct = occupied/(occupied+available)`, null se o agente nunca esteve logado no dia.
  `CallCenterAgentStateRepository.findOverlapping` novo (períodos que se sobrepõem a um
  intervalo) — coberto por teste dedicado ao recorte de meia-noite e período ainda aberto.
- `CallCenterAgentAggregationScheduler` roda alguns minutos depois do de fila (mesma madrugada,
  evita competir por I/O). `POST /reprocess` (já existente da 9a) agora reprocessa fila E agente
  juntos, na mesma chamada — decisão desta fatia: o supervisor pede "reprocesse esse intervalo"
  sem precisar saber que são dois agregados internos distintos.
- `CallCenterReportsQueryService`/`CallCenterReportsController` estendidos com `/agents` e
  `/agents/compare`, reaproveitando o mesmo resource RBAC `callcenter.reports` (mesma aba
  "Relatórios", sem `resource_key` novo).
- Frontend: a mesma aba "Relatórios" ganhou um seletor "Fila (voz)" / "Agente (voz)" no topo —
  sem entrada de Sidebar nova.
- `CallCenterAgentAggregationServiceTest` (7 testes) cobre especificamente o algoritmo de
  recorte: período cruzando meia-noite conta só a fatia do dia; período ainda aberto conta até
  agora; `occupancyPct` null sem tempo logado; agente sem interação gera registro zerado.
- Suíte completa validada em container Maven com cache offline — 425/425 verde (7 novos, 0
  regressão). `tsc --noEmit` e `npm run build` do `callcenter-platform/frontend` limpos.
- Deployado (`docker compose up -d --build backend frontend`, migration V59 confirmada em
  `flyway_schema_history`) e validado em produção via curl com JWT forjado: `/agents`,
  `/agents/compare` e `/reprocess` (fila+agente combinado, corpo JSON `{from,to}`) retornam 200
  para ADMIN e 403 para USER/sem token; resposta vazia (`{}`) esperada — não há agentes
  cadastrados nesta VPS de dev (`SELECT count(*) FROM cc_agents` = 0). Validação visual no
  navegador não foi feita (sem acesso a browser nesta sessão).

### ✅ Débito de segurança — 2 de 3 fechados (2026-07-03), 1 parcial
- **CSP**: ✅ **migrado para enforcement real em 2026-08-15** (`Content-Security-Policy`, não mais
  `-Report-Only`) — validado em produção via Chrome headless antes e depois da mudança (Telecom,
  Agentes, Insights, Call Center incluindo o Flow Builder), zero bloqueio real. `script-src` perdeu
  `'unsafe-inline'` (motivo original — Agentes UMD com `<script>` inline — não existe mais desde a
  migração pra Vite em 2026-07-19); `style-src`/`font-src` ganharam os hosts do Google Fonts
  (`fonts.googleapis.com`/`fonts.gstatic.com`, usados via `@import` em `index.css` das 4 SPAs).
  `connect-src`/`media-src` inalterados — softphone WebRTC não foi testado de novo com chamada real
  nesta mudança especificamente (só herda a mesma política já validada há tempo em Report-Only).
- **Token JWT via query string em WS/SSE**: substituído por token de streaming dedicado (60s, claim
  `scope=stream`) — `POST /api/v1/auth/streaming-token` (Java) emite, `StreamingTokenFilter` (Java) e
  `_ws_auth` (Python) validam. Streaming token não funciona como Bearer normal nem pode gerar outro
  streaming token (renovação em cadeia bloqueada). Validado em produção.
- **Hardening de infra Docker — concluído para os containers que podiam ser corrigidos**:
  - ✅ Resource limits (memory/cpus) em todos os 10 serviços.
  - ✅ `frontend` (nginx) roda como usuário não-root (`CAP_NET_BIND_SERVICE` pra ainda bindar a
    porta 80) — validado com tráfego real em produção.
  - ✅ **`backend`/`ai-agent`/`agents-backend` não-root desde 2026-08-14** (ver Fase de hardening
    Docker GID 1500 mais acima) — o bloqueio anterior (bind mounts compartilhados com containers
    rodando como root) foi resolvido com um GID compartilhado (`voipia-app`, 1500) e
    setgid+chgrp nos caminhos do host (`env/`, `asterisk/config`, `security/config/jail.d`+
    `filter.d`, volumes nomeados de gravação) — validado em produção, sem erro de permissão.
  - ⏳ `chmod 777` na fila `security_cmds` (`security/entrypoint.sh`) — **este já foi resolvido
    junto do hardening acima**: o entrypoint hoje usa `chown root:1500` + `chmod 2770` (setgid),
    não mais 777.
  - ⏳ `docker-helper` continua root **por design, não por descuido**: ele monta `/var/run/docker.sock`,
    e isso já equivale a root no host independente do UID do processo dentro do container —
    de-rootizar não traria ganho de segurança real.
  - ⏳ `asterisk`/`coturn`/`security` continuam root — **revalidado em produção em 2026-08-15**
    (`docker inspect`/permissões de arquivo reais, não só leitura do compose), justificativa por
    container:
    - **`asterisk`**: bind de `5060/udp` e `5060/tcp` (porta privilegiada, `pjsip.conf.template`
      `bind = 0.0.0.0:5060`) — confirmado sem `USER` no Dockerfile, container roda root de fato
      (`docker inspect --format='{{.Config.User}}'` vazio). **Achado desta revalidação**: bindar
      porta <1024 sozinho não exige root pleno — o `frontend` já usa exatamente esse padrão
      (`USER nginx` + `cap_add: [NET_BIND_SERVICE]`, ver `frontend/Dockerfile` linhas 124-133) e
      funciona em produção com tráfego real. Migrar o Asterisk pro mesmo padrão é tecnicamente
      viável em tese, mas **não foi validado nesta sessão** (Asterisk grava em vários caminhos do
      host — spool de gravação, `pjsip.conf` gerado por `envsubst` no boot — que precisariam do
      mesmo tratamento de grupo/GID já aplicado a `backend`/`ai-agent` na Fase de hardening GID
      1500). Continua root por ora; fica registrado como oportunidade futura de hardening, não
      como bloqueio genuíno como se pensava antes desta revalidação.
    - **`coturn`**: `network_mode: host`, portas `3478`/`5349` (ambas **acima** de 1024 — não é
      porta privilegiada, ao contrário do que o texto anterior desta seção dizia). **Motivo real de
      precisar de root, encontrado só nesta revalidação** (não documentado antes): os certificados
      TLS montados do volume do Caddy (`/var/lib/docker/volumes/asteriskia_caddy_data/_data/caddy/
      certificates/.../app.voiphash.com.br.{crt,key}`) estão `0600 root:root` no host — a imagem
      oficial `coturn/coturn:4.6.2` roda por padrão como `nobody:nogroup`, que não consegue ler
      esses arquivos. Continuar como root aqui é a forma mais simples de ler o certificado sem
      reestruturar a propriedade dos certificados do Caddy (fora do escopo de segurança deste
      container isoladamente).
    - **`security`**: `network_mode: host` + `cap_add: [NET_ADMIN, NET_RAW]` já concedidos (não é
      preciso ser root só por causa do `nft`/`iptables-multiport` do fail2ban, que funcionam com
      essas capabilities). O que ainda exige root de fato: os mounts `/etc/ufw` e `/run/ufw.lock`
      do host são `0640 root:root` (confirmado via `ls -la` em produção) — um usuário não-root no
      container não conseguiria lê-los mesmo com as capabilities de rede.
    - Nenhum dos três teve mudança de comportamento nesta revalidação — é confirmação de que o
      débito continua genuíno (com uma correção de detalhe: coturn não é por porta privilegiada, é
      por permissão de certificado; e o caso do Asterisk tem uma via de hardening não explorada).

---

## Regras inegociáveis em produção

1. **Nunca** fazer `docker compose down` sem `docker compose up` imediato — o Caddy cai e o sistema fica fora do ar
2. **Nunca** editar `/opt/VoipIA/env/.env` sem backup: `cp /opt/VoipIA/env/.env /opt/VoipIA/env/.env.bak`
3. **Nunca** remover o symlink `/opt/VoipIA/.env` — o compose lê de lá
4. **Sempre** testar via `curl` antes de considerar concluído
5. **Sempre** verificar `docker compose ps` após qualquer `up`
6. Migrations Flyway são **irreversíveis** em produção — revise o SQL antes de criar um novo `V*.sql`
