# VoipIA Agentes

Plataforma de agentes autônomos integrada ao VoipIA Telecom.

## Acesso

```
https://app.voiphash.com.br/agents/
```

## Stack

A plataforma de agentes compartilha a infraestrutura do VoipIA Telecom —
banco de dados e frontend são unificados.

| Camada    | Tecnologia              | Container / Servidor              |
|-----------|-------------------------|-----------------------------------|
| Frontend  | React 18 (UMD)          | `voipia-frontend` (nginx, rota `/agents/`) |
| Backend   | FastAPI + Python 3.12   | `voipia-agents-api`           |
| Banco     | PostgreSQL 16           | `voipia-postgres` (banco unificado) |

O roteamento `/agents/*` é feito pelo Caddy:
- `/agents/`        → frontend (`voipia-frontend:80`)
- `/agents/api/*`   → backend (`voipia-agents-api:8000`)
- `/agents/ws/*`    → WebSocket do backend (logs em tempo real)

## Tipos de agente

- **ssh_test** — conecta via SSH em servidores externos e executa verificações configuradas
- **web_monitor** — monitora URLs HTTP/HTTPS com validação de status, body e JSON
- **log_monitor** — monitora logs de aplicações
- **database** — executa queries PostgreSQL com thresholds (expect_lt/gt/eq/zero)

## Memória

Cada agente tem memória individual persistida no PostgreSQL (banco unificado
`asteriskia`). Agentes podem consultar a memória uns dos outros antes de
acionar a IA externa (RAG via pg_trgm).

## Variáveis de ambiente

O backend de agentes lê as variáveis `AGENTS_*` do `.env` do VoipIA Telecom.
As principais:

```env
AGENTS_LLM_PROVIDER=google        # google | anthropic | openai | minimax | openai_compat
AGENTS_LLM_MODEL=gemini-2.5-flash
AGENTS_LLM_ENABLED=false          # habilite ao configurar a chave
AGENTS_LLM_GOOGLE_KEY=            # vazio herda GEMINI_API_KEY
```

O banco e o JWT são compartilhados com o Telecom (`POSTGRES_*`, `BACKEND_JWT_SECRET`).

## Deploy

A plataforma sobe junto com o stack completo:

```bash
# Sobe tudo (inclui agents-backend)
docker compose up -d --build

# Atualizar apenas o backend de agentes
docker compose up -d --build --no-deps agents-backend

# Atualizar o frontend (serve Telecom + Agentes)
docker compose up -d --build --no-deps frontend
```

## Estrutura

```
agents-platform/
├── backend/
│   ├── main.py          # FastAPI app + WebSocket + middleware JWT
│   ├── database.py      # Schema PostgreSQL + pool asyncpg
│   ├── migrate.py       # Aplica schema antes de forkar os workers
│   ├── executor.py      # Motor de execução dos agentes
│   ├── scheduler.py     # Agendador assíncrono (interval + cron)
│   ├── notifier.py      # Telegram + webhook + alertas web
│   ├── llm.py           # Abstração multi-provedor de LLM
│   ├── models.py        # Pydantic schemas
│   └── routers/
│       ├── agents.py     # CRUD + execução manual
│       ├── servers.py    # CRUD servidores SSH + teste de conexão
│       ├── executions.py # Histórico e logs de execução
│       ├── reports.py    # Dashboard e relatórios por período
│       ├── knowledge.py  # Upload e busca de PDFs
│       ├── llm_config.py # Configuração do provedor de IA
│       └── system.py     # Health check, retenção, secrets por agente
└── frontend/
    ├── index.html       # SPA React 18 (sem build step)
    ├── docs.html        # Manual da plataforma
    └── js/              # React 18 UMD local
```
