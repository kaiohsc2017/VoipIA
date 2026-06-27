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
- **Migrations Telecom:** Flyway — classpath `backend/src/main/resources/db/migration/` — V1 a V14 aplicadas
- **Migrations Agentes:** `agents-platform/backend/migrate.py` — `CREATE TABLE IF NOT EXISTS` (idempotente)
- **Próxima migration Flyway:** V15

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
| 1 — URA Jira | `1000` (interno) / `s` (tronco) | `ramais-internos` / `recepcao-tronco` | Coleta dados via voz e abre issue no Jira Cloud |
| 2 — Teste conectividade | Agendado via `ConnectivityScheduler.java` | — | Discagem automática para verificar números |
| 3 — Alertas Zabbix | `1001` | `ramais-internos` | Liga para responsável ao detectar alerta crítico |

**Ramais SIP registrados:**
- `9001` — softphone WebRTC (frontend React, senha: `webrtc9001pass`)
- `9002` — softphone físico/Zoiper (senha: `sip9002pass2025`)
- `1001`, `1002` — ramais internos de teste

**Tronco SIP:** peer IP-based com `186.233.141.64` — sem usuário/senha, fechado por IP

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
│       ├── rtp.conf            # Porta RTP: 10000-10100
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

# Recarregar Caddyfile sem downtime
curl -X POST http://localhost:2019/load \
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
```

---

## Pendências conhecidas (prioridade)

### 🔴 Crítica
**Áudio WebRTC nunca funcionou em produção**

A cadeia completa nunca foi validada:
`softphone 9001 → ramal 1000 → AudioSocket → ai-agent:9092 → STT/LLM/TTS → RTP de volta`

Para diagnosticar, sempre verificar primeiro:
1. `SIP_PUBLIC_IP` injetado corretamente no `pjsip.conf`
2. `external_media_address` e `external_signaling_address` não vazios
3. Portas RTP abertas no firewall (`10000-10100/udp`)
4. ai-agent healthy e escutando na porta 9092
5. Logs do ai-agent durante uma chamada de teste

### 🟡 Importantes
- `SuporteController` cria issues reais no Jira via function calling da IA (tool `abrir_protocolo_suporte`)
- Swagger/OpenAPI foi removido do projeto (dependência springdoc retirada do pom.xml)

---

## Regras inegociáveis em produção

1. **Nunca** fazer `docker compose down` sem `docker compose up` imediato — o Caddy cai e o sistema fica fora do ar
2. **Nunca** editar `/opt/AsteriskIA/env/.env` sem backup: `cp /opt/AsteriskIA/env/.env /opt/AsteriskIA/env/.env.bak`
3. **Nunca** remover o symlink `/opt/AsteriskIA/.env` — o compose lê de lá
4. **Sempre** testar via `curl` antes de considerar concluído
5. **Sempre** verificar `docker compose ps` após qualquer `up`
6. Migrations Flyway são **irreversíveis** em produção — revise o SQL antes de criar um novo `V*.sql`
