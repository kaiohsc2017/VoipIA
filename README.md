# AsteriskIA

Sistema de telefonia inteligente integrando **Asterisk + IA (Google Gemini)** em Docker, com três módulos funcionais:

| Módulo | Descrição |
|--------|-----------|
| 🎫 **Módulo 1** | URA inteligente que abre chamados no Jira Cloud via voz |
| 📞 **Módulo 2** | Testes automáticos de conectividade de números telefônicos |
| 🚨 **Módulo 3** | Alertas de infraestrutura via Zabbix → ligação + Telegram |

## Stack Tecnológica

- **PBX**: Asterisk 21 LTS (chan_pjsip + app_audiosocket)
- **Backend**: Spring Boot 3.x (Java 21) — WAR no Tomcat 11
- **Frontend**: React 18 + TypeScript
- **Agente de IA**: Python 3.12 (asyncio)
- **Banco de dados**: PostgreSQL 16
- **IA**: Google Gemini (STT + LLM + TTS)
- **Monitoração**: Prometheus + Grafana
- **Infra**: Docker + Docker Compose

## Pré-requisitos

- Docker 24+
- Docker Compose v2
- Git

## Configuração Inicial

```bash
# 1. Clone o repositório
git clone https://github.com/kaiohsc2017/AsteriskIA.git
cd AsteriskIA

# 2. Configure as variáveis de ambiente
cp .env.example .env
# Edite o .env com suas credenciais

# 3. Suba o ambiente
docker-compose up -d

# 4. Verifique os serviços
docker-compose ps
```

## Acessos

| Serviço | URL | Credenciais |
|---------|-----|-------------|
| Frontend | http://localhost | — |
| Backend API | http://localhost:8080/swagger-ui.html | — |
| Grafana | http://localhost:3000 | admin / (ver .env) |
| Prometheus | http://localhost:9090 | — |

## Testes Locais com Softphone

Configure o Zoiper ou MicroSIP com:
- **Servidor**: `localhost`
- **Usuário**: `1001`
- **Senha**: `ramal1001pass`
- **Protocolo**: SIP/UDP

Disque `1000` para acessar a URA de abertura de chamados (Módulo 1).

## Estrutura do Projeto

```
AsteriskIA/
├── asterisk/       # Configurações e Dockerfile do Asterisk
├── ai-agent/       # Agente de IA Python (Audiosocket + Gemini)
├── scheduler/      # Scheduler Python (testes e polling Zabbix)
├── backend/        # API REST Spring Boot
├── frontend/       # SPA React
├── database/       # Migrations SQL
├── monitoring/     # Prometheus + Grafana
└── docs/           # Documentação adicional
```

## Documentação

- [Plano de Implementação](docs/IMPLEMENTATION_PLAN.md)
- [API REST](http://localhost:8080/swagger-ui.html) (após subir o ambiente)
- [Setup Telegram Bot](docs/TELEGRAM_SETUP.md)
- [Deploy em Cloud](docs/DEPLOY.md)

## Licença

Proprietário — Todos os direitos reservados.
