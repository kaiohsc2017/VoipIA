# VoipIA Enterprise

Sistema corporativo de telefonia inteligente, URA conversacional com inteligência artificial generativa em tempo real (**Google Gemini 2.5 Flash**), Call Center omnicanal, Speech Analytics (*Insights*), Construtor Visual de Fluxos (*Flow Builder*) e Softphone WebRTC em Docker sobre **Asterisk 21 LTS + Spring Boot 3.3 + Python 3.12 + React + PostgreSQL 16 (pgvector)**.

---

## 📚 Documentação Oficial

O repositório segue o padrão corporativo unificado de documentação:

1. **[`docs/MANUAL_DO_USUARIO.md`](docs/MANUAL_DO_USUARIO.md)** — Manual do usuário completo com explicação detalhada de cada tela, menus operacionais, softphone, URA, Call Center, Insights e exemplos práticos.
2. **[`docs/REFERENCIA_TECNICA.md`](docs/REFERENCIA_TECNICA.md)** — Manual de engenharia de software, Asterisk 21 LTS, protocolo AudioSocket, Clean Architecture Spring Boot 3.3, React SPAs, WebRTC e pgvector.
3. **[`docs/MATRIZ_DE_CONECTIVIDADE.md`](docs/MATRIZ_DE_CONECTIVIDADE.md)** — Matriz completa de rede, portas internas/externas, regras de firewall para Ubuntu (UFW) e Oracle Linux 9 (Firewalld), e domínios Google Gemini / Antigravity AGY.
4. **[`docs/DOCUMENTACAO_DAS_APIS.md`](docs/DOCUMENTACAO_DAS_APIS.md)** — Catálogo completo das APIs REST, WebSockets STOMP, sinalização SIP WebRTC, AudioSocket TCP e Asterisk AMI/ARI.
5. **[`docs/ARQUITETURA.md`](docs/ARQUITETURA.md)** — Arquitetura de software e infraestrutura, diagramas Mermaid, DevSecOps, Threat Model e benchmark comparativo de mercado.
6. **[`docs/IMPLANTACAO.md`](docs/IMPLANTACAO.md)** — Guia passo a passo de implantação para Ubuntu e Oracle Linux 9, runbook de validação, backup e rollback.

### Governança & Estado da Arte:
- **[`docs/STATUS_DO_SISTEMA.md`](docs/STATUS_DO_SISTEMA.md)** — Status em tempo real dos serviços, containers Docker, portas e checklist operacional para Go-Live.
- **[`docs/ROADMAP.md`](docs/ROADMAP.md)** — Histórico de versões concluídas e marcos estratégicos de evolução.
- **[`docs/PLANO_CLUSTERING_ASTERISK_HA.md`](docs/PLANO_CLUSTERING_ASTERISK_HA.md)** — Plano arquitetural completo de clustering Asterisk HA Ativo-Ativo com Kamailio / OpenSIPS.
- **[`docs/ROTEIRO_TREINAMENTO_E_APRESENTACAO.md`](docs/ROTEIRO_TREINAMENTO_E_APRESENTACAO.md)** — Roteiro executivo e operacional para apresentações, treinamentos e demonstrações.

---

## 🚀 Instalação Rápida (Ubuntu / Oracle Linux 9)

```bash
# Ubuntu 22.04 / 24.04 LTS:
sudo ./install.sh

# Oracle Linux 9 (UEK / RHEL 9):
sudo ./install-oracle9.sh
```

---

## 💻 Desenvolvimento e Operação Local

```bash
# 1. Clonar e configurar ambiente
git clone https://github.com/kaiohsc2017/VoipIA.git /opt/VoipIA
cd /opt/VoipIA
cp .env.example env/.env

# 2. Subir todos os containers via Docker Compose
docker compose up -d --build

# 3. Acompanhar logs dos serviços centrais
docker compose logs -f voipia-backend voipia-asterisk voipia-ai-agent
```

---

## 🏛️ Estrutura de Diretórios

```
/opt/VoipIA/
├── asterisk/             # Asterisk 21 LTS — Dockerfile + configs (PJSIP, RTP, Dialplan, AMI)
├── ai-agent/             # Agente de IA Python 3.12 — Servidor AudioSocket TCP + Google Gemini
├── backend/              # Backend Spring Boot 3.3 (Java 21) — Clean Architecture + Flyway V1-V96
├── frontend/             # Frontend React SPA + Softphone WebRTC (JsSIP) + Nginx
├── callcenter-platform/  # Módulo Call Center — Desktop do Agente, Filas, Supervisão e Flow Builder
├── insights-platform/    # Módulo Insights — Speech Analytics, Scorecards de Qualidade e Transcrição
├── coturn/               # Servidor Coturn STUN/TURN — NAT Traversal para WebRTC
├── security/             # Fail2ban + nftables — Lockdown SIP e proteção anti-força bruta
├── docs/                 # Suíte unificada de documentação técnica corporativa
├── tools/                # Utilitários de CLI e agentes RAG locais
├── install.sh            # Script de instalação para Ubuntu
├── install-oracle9.sh    # Script de instalação para Oracle Linux 9
└── docker-compose.yml    # Orquestração completa dos 10 containers da stack
```

---

## ✅ Estado do Projeto

* **Software:** 100% Concluído, Integrado, Saneado e Operacional (v3.5 Enterprise).
* **Testes Automatizados:** 993 testes unitários e de integração aprovados com 100% de sucesso.
* **Docker:** 10 containers em execução e saudáveis (`voipia-caddy`, `voipia-backend`, `voipia-frontend`, `voipia-asterisk`, `voipia-ai-agent`, `voipia-insights`, `voipia-docker-helper`, `voipia-coturn`, `voipia-postgres`, `voipia-security`).
* **Segurança:** Padrão OWASP ASVS Nível 2 / Zero Trust / Zero Secrets em conformidade estrita.
