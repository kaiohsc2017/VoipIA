# AsteriskIA

Sistema de telefonia inteligente integrando **Asterisk + IA (Google Gemini)** em Docker, com três módulos funcionais e dashboard em tempo real:

| Módulo | Descrição |
|--------|-----------|
| 🎫 **Módulo 1** | URA inteligente que abre chamados no Jira Cloud via voz |
| 📞 **Módulo 2** | Testes automáticos de conectividade de números telefônicos |
| 🚨 **Módulo 3** | Alertas de infraestrutura via Zabbix → ligação + Telegram |

## Stack Tecnológica

- **PBX**: Asterisk 21 LTS (chan_pjsip + app_audiosocket + WebRTC)
- **Backend**: Spring Boot 3.x (Java 21) — WAR no Tomcat 11 + WebSocket STOMP
- **Frontend**: React 18 + TypeScript + Recharts + Softphone WebRTC (JsSIP)
- **Agente de IA**: Python 3.12 (asyncio + Function Calling Gemini Tools)
- **Banco de dados**: PostgreSQL 16
- **IA**: Google Gemini (STT + LLM + TTS + Function Calling)
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
# Gere segredos seguros com: openssl rand -hex 32

# 3. Suba o ambiente
docker compose up -d

# 4. Verifique os serviços
docker compose ps
```

## Acessos

| Serviço | URL | Credenciais |
|---------|-----|-------------|
| Frontend | http://localhost | ADMIN_USERNAME / ADMIN_PASSWORD do .env |
| Backend API | http://localhost:8080/swagger-ui.html | — |
| Grafana | http://localhost:3000 | admin / GRAFANA_ADMIN_PASSWORD do .env |
| Prometheus | http://localhost:9090 | — |

## Testes Locais com Softphone

### Softphone Desktop (Zoiper / MicroSIP)

Configure com:
- **Servidor**: `localhost`
- **Usuário**: `1001`
- **Senha**: `ramal1001pass`
- **Protocolo**: SIP/UDP

Disque `1000` para acessar a URA de abertura de chamados (Módulo 1).

### Softphone WebRTC (embutido no navegador)

- Abrir o frontend em `http://localhost`
- Clicar no botão 📞 no canto inferior direito da tela
- O ramal `9001` se registra automaticamente no Asterisk via WebSocket (porta 8088)
- Configurável via `VITE_ASTERISK_WS`, `VITE_SIP_URI` e `VITE_SIP_PASSWORD` no `.env`

## Estrutura do Projeto

```
AsteriskIA/
├── asterisk/        # Configurações e Dockerfile do Asterisk (WebRTC habilitado)
├── ai-agent/        # Agente de IA Python (Audiosocket + Gemini + Function Calling)
├── scheduler/       # Scheduler Python (testes de conectividade e polling Zabbix)
├── backend/         # API REST Spring Boot (WebSocket STOMP em tempo real)
├── frontend/        # SPA React (Recharts + Softphone JsSIP + WebSocket)
├── database/        # Migrations SQL (V1__init_schema, V2__seed_master_data)
├── monitoring/      # Prometheus + Grafana
├── nginx-prod.conf  # Config Nginx HOST para produção (HTTPS + WebSocket proxy)
└── .github/         # CI/CD GitHub Actions (Frontend, Backend, Python, Docker)
```

## Deploy em Produção

Para deploy em servidor com HTTPS:

1. Provisionar servidor Ubuntu 22.04 (mínimo 4 vCPUs / 8 GB RAM)
2. Instalar Docker: `apt install docker-ce docker-compose-plugin`
3. Clonar o repositório em `/opt/asteriskia`
4. Preencher o `.env` com as credenciais reais de produção
5. Gerar certificado SSL: `certbot certonly --standalone -d SEU_DOMINIO`
6. Personalizar e instalar o `nginx-prod.conf` com seu domínio
7. Executar: `docker compose up -d --build`

Consulte o [Plano de Implantação em Produção](docs/DEPLOY.md) para o guia completo e detalhado.

## Documentação

- [API REST](http://localhost:8080/swagger-ui.html) (disponível após subir o ambiente)
- [Nginx de Produção](nginx-prod.conf)

## Licença

Proprietário — Todos os direitos reservados.
