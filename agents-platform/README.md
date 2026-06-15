# AsteriskIA Agentes

Plataforma de agentes autônomos integrada ao AsteriskIA Telecom.

## Acesso

```
https://app.voiphash.com.br/agents/
```

## Stack

| Camada    | Tecnologia              | Container               |
|-----------|-------------------------|-------------------------|
| Frontend  | HTML + React 18 (UMD)   | `asteriskia-agents-ui`  |
| Backend   | FastAPI + Python 3.12   | `asteriskia-agents-api` |
| Banco     | PostgreSQL 16           | `asteriskia-agents-db`  |

## Tipos de agente

- **ssh_test** — conecta via SSH em servidores externos e executa verificações configuradas
- **web_monitor** — monitora URLs HTTP/HTTPS com validação de status, body e JSON
- **log_monitor** — monitora logs de aplicações (em desenvolvimento)

## Memória

Cada agente tem memória individual persistida no PostgreSQL (`agentsdb`).
Agentes podem consultar a memória uns dos outros antes de acionar a IA externa.

## Variáveis de ambiente necessárias no `.env`

```env
AGENTS_DB_PASS=agents_secret      # senha do PostgreSQL dos agentes
TELEGRAM_BOT_TOKEN=               # opcional — alertas via Telegram
```

As demais variáveis (`GEMINI_API_KEY`, etc.) já existem no `.env` do AsteriskIA Telecom.

## Deploy

```bash
# Primeira vez
docker compose up -d --build agents-postgres agents-backend agents-frontend

# Atualizar após mudanças
docker compose up -d --build --no-deps agents-backend agents-frontend
```

## Estrutura

```
agents-platform/
├── backend/
│   ├── main.py          # FastAPI app + WebSocket
│   ├── database.py      # Schema PostgreSQL + pool asyncpg
│   ├── executor.py      # Motor de execução dos agentes
│   ├── scheduler.py     # Agendador assíncrono
│   ├── notifier.py      # Telegram + alertas web
│   ├── models.py        # Pydantic schemas
│   └── routers/
│       ├── agents.py    # CRUD + execução manual
│       ├── servers.py   # CRUD servidores SSH + teste de conexão
│       ├── executions.py# Histórico e logs de execução
│       ├── reports.py   # Dashboard e relatórios por período
│       └── knowledge.py # Upload e busca de PDFs
└── frontend/
    └── index.html       # SPA React 18 (sem build step)
```
