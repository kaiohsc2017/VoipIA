# AsteriskIA — Contexto para o Claude Code

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
| Repositório no VPS | `/opt/AsteriskIA` |
| Remote Git | `github.com/kaiohsc2017/AsteriskIA` |
| Branch principal | `main` |
| `.env` real | `/opt/AsteriskIA/env/.env` |
| `.env` symlink | `/opt/AsteriskIA/.env` → aponta para o real |
| TLS | Caddy 2 — Let's Encrypt automático |
| Domínios | `app.voiphash.com.br`, `claw.voiphash.com.br` |

---

## Stack de containers

Rede Docker: `asteriskia-net` — bridge `172.16.7.0/24`

| IP | Container | Imagem / Build | Função |
|----|-----------|----------------|--------|
| `172.16.7.10` | `asteriskia-caddy` | `caddy:2-alpine` | Proxy reverso HTTPS — entrada de todo tráfego externo |
| `172.16.7.11` | `asteriskia-postgres` | `postgres:16-alpine` | Banco unificado (Telecom + Agentes) |
| `172.16.7.12` | `asteriskia-asterisk` | build `./asterisk` | PBX — Asterisk 21 LTS |
| `172.16.7.13` | `asteriskia-ai-agent` | build `./ai-agent` | Servidor AudioSocket Python — STT/LLM/TTS via Gemini |
| `172.16.7.18` | `asteriskia-insights` | build `./insights` | Serviço Python (loop de polling, sem porta própria) — transcreve/analisa via Gemini as gravações do call center corporativo Verint em `/opt/audio` (módulo apartado do domínio Asterisk, tela "Insights") |
| `172.16.7.14` | `asteriskia-backend` | build `./backend` | Spring Boot 3.3 — API REST + WebSocket STOMP |
| `172.16.7.15` | `asteriskia-frontend` | build `./frontend` | React 18 + Nginx — serve Telecom e Agentes |
| `172.16.7.16` | `asteriskia-agents-api` | build `./agents-platform/backend` | FastAPI — plataforma de agentes autônomos |
| `172.16.7.17` | `asteriskia-docker-helper` | build `./docker-helper` | Único container com acesso ao `docker.sock` (F-CRIT-10) — API interna estreita para `docker compose up`/`docker logs`/`docker exec` (asterisk), sem porta publicada no host, atrás de `X-Internal-Key` |
| host | `asteriskia-security` | build `./security` | Fail2ban + nftables — `network_mode: host` |

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

- **Instância:** PostgreSQL 16 em `asteriskia-postgres:5432`
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
        ├── /agents/api/*   → strip /agents → asteriskia-agents-api:8000
        ├── /agents/ws/*    → strip /agents → asteriskia-agents-api:8000 (WS)
        ├── /agents*        → asteriskia-frontend:80 (NÃO strip — nginx tem location /agents/)
        ├── /insights*      → asteriskia-frontend:80 (NÃO strip — nginx tem location /insights/)
        ├── /docs/*         → /srv/docs (file_server direto no Caddy)
        ├── /api/*          → asteriskia-backend:8080
        ├── /ws/*           → asteriskia-backend:8080 (STOMP)
        ├── /asterisk-ws*   → rewrite /ws → asteriskia-asterisk:8088 (WebRTC)
        └── /*              → asteriskia-frontend:80 (catch-all Telecom)
```

O Nginx interno serve:
- `/` → `/usr/share/nginx/html/` (build React Telecom)
- `/agents/` → `/usr/share/nginx/html/agents/` (React UMD Agentes)
- `/agents/api/` → proxy para `asteriskia-agents-api:8000` (fallback interno)
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
Consulte o valor atual com: `grep '^RAMAL_9001_PASSWORD=' /opt/AsteriskIA/env/.env`

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
- **Pendência conhecida**: `Users.tsx` ainda atribui `role` ADMIN|USER na criação/edição (o
  `UserController` resolve pro grupo "Administradores"/"Usuários" internamente) — atribuir um
  grupo customizado a um usuário pela UI ainda não existe, é a próxima iteração natural.

---

## Estrutura do repositório

```
AsteriskIA/
├── asterisk/
│   ├── Dockerfile              # Build Asterisk 21 com app_audiosocket
│   ├── docker-entrypoint.sh    # Injeta SIP_PUBLIC_IP no pjsip.conf no boot
│   └── config/
│       ├── pjsip.conf.template # Template com ${SIP_PUBLIC_IP} substituído no boot
│       ├── extensions.conf     # Dialplan: contextos recepcao-tronco, ramais-internos
│       ├── rtp.conf            # Porta RTP: 15000-15500
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
│   ├── asteriskia-lockdown.service  # Unit systemd instalado pelo install.sh
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
docker exec asteriskia-asterisk grep "external_media_address" /etc/asterisk/pjsip.conf

# Corrigir SIP_PUBLIC_IP manualmente (se vazio após restart)
docker exec asteriskia-asterisk sed -i \
  's/external_media_address = $/external_media_address = 129.121.51.29/' \
  /etc/asterisk/pjsip.conf
docker exec asteriskia-asterisk asterisk -rx "module reload res_pjsip.so"

# Recarregar dialplan sem restart
docker exec asteriskia-asterisk asterisk -rx "dialplan reload"

# Status dos endpoints SIP
docker exec asteriskia-asterisk asterisk -rx "pjsip show endpoints"

# Recarregar Caddyfile sem downtime (admin API via socket Unix — não é mais TCP:2019)
curl --unix-socket /opt/AsteriskIA/caddy-admin/admin.sock http://localhost/load \
  -H "Content-Type: text/caddyfile" \
  --data-binary @/opt/AsteriskIA/Caddyfile

# Status do lockdown SIP no host
systemctl status asteriskia-lockdown
nft list chain ip filter DOCKER-USER 2>/dev/null

# Verificar healthcheck de um container
docker inspect --format='{{.State.Health.Status}}' asteriskia-agents-api

# Rede: verificar IPs atribuídos
docker network inspect asteriskia_asteriskia-net \
  --format '{{range .Containers}}{{.Name}}: {{.IPv4Address}}{{"\n"}}{{end}}'

# Acessar banco diretamente
docker exec -it asteriskia-postgres psql -U asteriskia -d asteriskia

# Verificar o docker-helper (único container com docker.sock — F-CRIT-10)
docker inspect --format='{{.State.Health.Status}}' asteriskia-docker-helper
curl -sf --unix-socket /opt/AsteriskIA/caddy-admin/admin.sock http://localhost/config/ >/dev/null && echo ok

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
> `external_media_address`/`external_signaling_address` não vazios, portas RTP `15000-15500/udp`
> abertas, ai-agent healthy na porta 9092, logs do ai-agent durante a chamada de teste.

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
  real/`CLAUDE.md` (corrigida pra `15000-15500/udp`, que já era o valor real usado); bug garantido
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

### 🟡 Controle de acesso por BU (2026-07-05) — cadastros/Chamadas/Conectividade cobertos, Alertas Zabbix não
Usuário ganhou BU obrigatória (`user_business_units`) e o JWT carrega a claim `bu`
(`BusinessUnitContext`, authorities `BU_<id>`) — ADMIN sempre vê tudo. Escopo aplicado em:
Cadastros (Cliente/Operação/BU — itens sem BU ficam visíveis a todos, já que a BU é opcional
nesses cadastros), Chamadas (`CallRecordService`, via `uras.business_unit_id`) e Conectividade
(`number-tests`/`test-results`, via `NumberTest.businessUnit`, já obrigatória).
- **Gap conhecido, não coberto**: Alertas Zabbix (`AlertCall`) não tem nenhum caminho de derivar a
  BU de um incidente/host monitorado — o disparo de ligação (`AlertService.triggerAlert`) nem
  filtra por operação hoje, só percorre os contatos de plantão por prioridade. `AlertContact` tem
  `operationId` opcional, mas não foi conectado ao filtro de BU nesta entrega. Resolver direito
  exigiria decisão de produto sobre como um host Zabbix se relaciona a uma BU/Operação — fora do
  escopo desta entrega.
- Usuários pré-existentes (antes da migration V26) foram migrados com `access_indeterminate=true`
  e vinculados a todas as BUs ativas, para não perder acesso retroativamente.

### ✅ Débito de segurança — 2 de 3 fechados (2026-07-03), 1 parcial
- **CSP**: `Content-Security-Policy-Report-Only` ativo no Caddyfile (não bloqueia nada, só reporta
  violações no console do browser) — validado em produção. Migrar pra enforcement real exige
  observar violações reais primeiro (softphone, WebRTC) antes de trocar pra `Content-Security-Policy`.
- **Token JWT via query string em WS/SSE**: substituído por token de streaming dedicado (60s, claim
  `scope=stream`) — `POST /api/v1/auth/streaming-token` (Java) emite, `StreamingTokenFilter` (Java) e
  `_ws_auth` (Python) validam. Streaming token não funciona como Bearer normal nem pode gerar outro
  streaming token (renovação em cadeia bloqueada). Validado em produção.
- **Hardening de infra Docker — parcial**:
  - ✅ Resource limits (memory/cpus) em todos os 10 serviços.
  - ✅ `frontend` (nginx) roda como usuário não-root (`CAP_NET_BIND_SERVICE` pra ainda bindar a
    porta 80) — validado com tráfego real em produção.
  - ⏳ **Continua root, por bloqueio real confirmado em produção** (não é preguiça — tentei
    `agents-backend` e quebrou): `agents-backend`, `ai-agent` e o `backend` Java montam
    bind mounts/volumes compartilhados com outro container rodando como root
    (`/opt/AsteriskIA/env` é `700 root:root` no host; `asterisk_recordings` é escrito pelo
    `asterisk`; `security_cmds`/`fail2ban_socket` são escritos pelo `security`) — um UID não-root
    não consegue ler/escrever nesses caminhos compartilhados. Corrigir direito exige um esquema de
    GID compartilhado entre os containers ou mudar permissão de diretório no host — mudança maior,
    ainda não feita.
  - ⏳ `docker-helper` continua root **por design, não por descuido**: ele monta `/var/run/docker.sock`,
    e isso já equivale a root no host independente do UID do processo dentro do container —
    de-rootizar não traria ganho de segurança real.
  - ⏳ `asterisk`/`coturn`/`security` continuam root: portas privilegiadas (5060, 3478) e
    `NET_ADMIN`/`network_mode: host` são requisitos genuínos da função de cada um.
  - ⏳ `chmod 777` na fila `security_cmds` (`security/entrypoint.sh`) segue como está — depende do
    mesmo fix de GID compartilhado do `backend` acima.

---

## Regras inegociáveis em produção

1. **Nunca** fazer `docker compose down` sem `docker compose up` imediato — o Caddy cai e o sistema fica fora do ar
2. **Nunca** editar `/opt/AsteriskIA/env/.env` sem backup: `cp /opt/AsteriskIA/env/.env /opt/AsteriskIA/env/.env.bak`
3. **Nunca** remover o symlink `/opt/AsteriskIA/.env` — o compose lê de lá
4. **Sempre** testar via `curl` antes de considerar concluído
5. **Sempre** verificar `docker compose ps` após qualquer `up`
6. Migrations Flyway são **irreversíveis** em produção — revise o SQL antes de criar um novo `V*.sql`
