# VoipIA

Sistema de telefonia inteligente integrando **Asterisk 21 LTS + IA (Google Gemini)** em Docker, com três módulos funcionais, plataforma de agentes de automação e dashboard em tempo real.

| Módulo | Descrição |
|--------|-----------|
| 🎫 **Módulo 1** | URA inteligente que abre chamados no Jira Cloud via voz |
| 📞 **Módulo 2** | Testes automáticos de conectividade de números telefônicos |
| 🚨 **Módulo 3** | Alertas de infraestrutura via Zabbix → ligação + Telegram |
| 🤖 **Agentes** | Plataforma de automação com agentes IA (SSH, Web, DB, Logs) |

---

## Stack

| Camada | Tecnologia |
|--------|-----------|
| PBX | Asterisk 21 LTS — chan_pjsip + app_audiosocket + WebRTC |
| Backend | Spring Boot 3.3 (Java 21) — WAR no Tomcat 11 + WebSocket STOMP |
| Frontend | React 18 + TypeScript + Recharts + Softphone WebRTC (JsSIP) |
| Agentes | FastAPI + Python 3.12 asyncio |
| IA | Google Gemini 2.5 Flash — STT + LLM + TTS + Function Calling |
| Banco | PostgreSQL 16 — Flyway V1–V14 (Telecom) + migrate.py (Agentes) |
| Proxy | Caddy 2 — TLS automático (Let's Encrypt) |
| Segurança | Fail2ban + nftables (lockdown SIP) |
| Infra | Docker Compose — 8 containers em rede `172.16.7.0/24` |

---

## Requisitos

- Ubuntu 22.04 LTS ou 24.04 LTS
- Docker 24+ com Compose v2
- Git
- Domínio com DNS apontando para o VPS (para TLS automático do Caddy)

---

## Instalação rápida

```bash
curl -fsSL https://app.voiphash.com.br/install.sh | bash
```

Ou manualmente:

```bash
git clone https://github.com/kaiohsc2017/VoipIA.git /opt/VoipIA
cd /opt/VoipIA
cp .env.example .env
# Edite o .env com suas credenciais
docker compose up -d --build
```

---

## Acessos

| Serviço | URL |
|---------|-----|
| Painel Telecom | https://app.voiphash.com.br |
| Plataforma Agentes | https://app.voiphash.com.br/agents/ |
| Documentação | https://app.voiphash.com.br/docs/ |

---

## Containers e IPs

| IP | Container | Serviço |
|----|-----------|---------|
| `172.16.7.10` | `voipia-caddy` | Proxy reverso HTTPS |
| `172.16.7.11` | `voipia-postgres` | PostgreSQL 16 |
| `172.16.7.12` | `voipia-asterisk` | Asterisk 21 LTS |
| `172.16.7.13` | `voipia-ai-agent` | Agente IA (AudioSocket) |
| `172.16.7.14` | `voipia-backend` | Spring Boot API |
| `172.16.7.15` | `voipia-frontend` | React + Nginx |
| `172.16.7.16` | `voipia-agents-api` | FastAPI Agentes |
| host | `voipia-security` | Fail2ban + nftables |

---

## Estrutura do Repositório

```
VoipIA/
├── asterisk/           # Asterisk 21 — Dockerfile + configs (pjsip, extensions, rtp)
├── ai-agent/           # Agente IA Python — AudioSocket server (STT → LLM → TTS)
├── backend/            # Spring Boot — API REST + WebSocket + Flyway migrations
├── frontend/           # React 18 — SPA + Softphone WebRTC + Nginx
├── agents-platform/    # Plataforma de Agentes
│   ├── backend/        # FastAPI — agentes, execuções, scheduler, secrets
│   └── frontend/       # React 18 UMD — interface dos agentes (servida em /agents/)
├── security/           # Fail2ban + nftables — lockdown SIP
├── database/migrations # Flyway SQL (V1–V14)
├── docs/               # Documentação HTML (deploy-ubuntu, deploy-oracle-linux)
├── tools/              # Ferramentas CLI (ver abaixo)
├── docker-compose.yml  # Orquestração completa
├── Caddyfile           # Configuração do proxy reverso
└── .env.example        # Template de variáveis de ambiente
```

---

## Ferramentas CLI (`tools/`)

### `asteriskia-agent.py` — Agente CLI com memória PostgreSQL

Agente conversacional especialista no projeto VoipIA, com memória persistente via RAG (PostgreSQL + pg_trgm). Útil para diagnóstico, troubleshooting e desenvolvimento direto no VPS.

**Pré-requisitos:**
```bash
pip install google-genai psycopg2-binary
```

**Uso:**
```bash
python3 tools/asteriskia-agent.py
```

O agente lê automaticamente `GEMINI_API_KEY` e `DATABASE_URL` do `.env` do projeto.

**Capacidades:**
- Memória persistente entre sessões (5 tabelas PostgreSQL via pg_trgm)
  - `agent_fixes` — correções aplicadas e resultado
  - `agent_error_patterns` — padrões de erro e causas-raiz conhecidas
  - `agent_preferences` — preferências do usuário
  - `agent_project_state` — estado atual do projeto
  - `agent_sessions` — resumo de sessões anteriores
- Function calling Gemini para persistir/recuperar memória automaticamente
- Papéis: Desenvolvedor Sênior · Arquiteto DevOps · Engenheiro Linux
- Sumarização automática de sessão ao encerrar

### `agente-google.py` — Agente Google Gemini com memória PostgreSQL

Variante do `asteriskia-agent.py` que usa a SDK `google-genai` diretamente.
Possui o mesmo sistema de memória RAG via PostgreSQL + pg_trgm.

**Pré-requisitos:**
```bash
pip install google-genai psycopg2-binary python-dotenv
```

**Uso:**
```bash
python3 tools/agente-google.py
```

Lê `GEMINI_API_KEY` e `DATABASE_URL` automaticamente do `.env` do projeto.

---

## Variáveis de Ambiente principais

| Variável | Descrição |
|----------|-----------|
| `SIP_PUBLIC_IP` | IP público do VPS — **obrigatório** para RTP/WebRTC funcionar |
| `GEMINI_API_KEY` | Chave Google AI Studio |
| `BACKEND_JWT_SECRET` | Secret JWT (32+ chars) — compartilhado com agents-backend |
| `POSTGRES_PASSWORD` | Senha do banco unificado |
| `SIP_TRUNK_HOST` | IP do tronco SIP (`186.233.141.64`) |
| `JIRA_ISSUE_TYPE` | Tipo de issue criado pela URA (ex: `Task`, `Support`) |
| `VITE_STUN_URL` | Servidor STUN para ICE do softphone WebRTC |

> ⚠️ Variáveis `VITE_*` são resolvidas em **build time**. Ao alterar, rebuilde o frontend:
> ```bash
> docker compose build frontend && docker compose up -d frontend
> ```

---

## Comandos úteis

```bash
# Status de todos os containers
docker compose ps

# Logs em tempo real
docker compose logs -f <serviço>

# Verificar SIP_PUBLIC_IP injetado no Asterisk
docker exec voipia-asterisk grep "external_media_address" /etc/asterisk/pjsip.conf

# Recarregar PJSIP sem reiniciar
docker exec voipia-asterisk asterisk -rx "module reload res_pjsip.so"

# Status do lockdown SIP
systemctl status voipia-lockdown

# Checar regras nftables
nft list chain ip filter DOCKER-USER 2>/dev/null

# Recarregar Caddyfile (sem downtime)
curl -X POST "http://localhost:2019/load" \
  -H "Content-Type: text/caddyfile" \
  --data-binary @/opt/VoipIA/Caddyfile
```

---

## Documentação completa

- [Deploy Ubuntu 22/24](https://app.voiphash.com.br/docs/deploy-ubuntu.html)
- [Plataforma de Agentes](https://app.voiphash.com.br/agents/docs.html)
