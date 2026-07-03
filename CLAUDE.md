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
- **Próxima migration Flyway:** V23 — confirme sempre com `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`

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
  (`ResourceCatalog.java`, espelhado em `Sidebar.tsx` e no `NAV` do `agents-platform/frontend`) —
  os menus são fixos, só a matriz de permissões é dinâmica. Gestão pela UI: página "Grupos de
  Acesso" (`AccessGroups.tsx`, admin-only).
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
- **Frontend**: `client.ts` (`getPermissionsFromToken`/`canRead`/`canWrite`) e o equivalente
  `getPermissions()`/`canRead()`/`canWrite()` no `agents-platform/frontend/index.html` decodificam
  a claim `perm` do JWT (sem validar assinatura — é só hint de UI) para esconder nav/botões por
  recurso. ADMIN (`role` legada) sempre enxerga tudo, mesmo com token antigo sem `perm`.
  `Sidebar.tsx`/`App.tsx` e o `NAV` de Agentes usam esse par em vez do binário `adminOnly`.
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
│   ├── Dockerfile               # Multi-stage: node:22-alpine → nginx:1.27-alpine
│   ├── nginx.conf               # SPA fallback + proxy /agents/api e /agents/ws
│   └── src/
│       └── components/          # Dashboard, Settings, AISettingsPanel, Softphone…
├── agents-platform/
│   ├── backend/
│   │   ├── main.py              # FastAPI app + JWT middleware + WebSocket broadcast
│   │   ├── database.py          # Schema PostgreSQL + pool asyncpg + migrate_db()
│   │   ├── executor.py          # Motor: SSHExecutor, WebExecutor, DatabaseExecutor…
│   │   ├── scheduler.py         # Agendador cron/interval assíncrono
│   │   ├── notifier.py          # Telegram + webhook
│   │   ├── llm.py               # Multi-provider LLM
│   │   └── routers/             # agents, servers, executions, reports, knowledge, llm_config, system
│   └── frontend/
│       ├── index.html           # React 18 UMD — SPA sem build step
│       ├── docs.html            # Manual da plataforma de agentes
│       └── js/                  # React 18 UMD local
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
│   ├── asteriskia-agent.py      # CLI com memória PostgreSQL (RAG via pg_trgm)
│   └── agente-google.py         # Variante com SDK google-genai
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
