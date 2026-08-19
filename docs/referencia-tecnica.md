# Referência Técnica — VoipIA

> **Versão:** v3.2 Enterprise  
> **Documento:** Especificação Técnica de Arquitetura, Engenharia de Software e Metodologia  
> **Classificação:** Técnico / Desenvolvimento / Engenharia de Infraestrutura

---

## 1. Stack Tecnológico & Frameworks

O **VoipIA** foi concebido sob princípios de Clean Architecture, isolamento de processos por container, alta concorrência assíncrona e tolerância a falhas.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CAMADA DE BORDA (EDGE)                            │
│           Caddy 2 (TLS Automático Let's Encrypt / HTTP/2 / WebSockets)       │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
      ┌────────────────────────────────┼────────────────────────────────┐
      ▼                                ▼                                ▼
┌──────────────┐             ┌───────────────────┐             ┌────────────────┐
│   FRONTEND   │             │   CORE BACKEND    │             │ TELEFONIA/VOZ  │
│ React + Vite │◄───REST/WS──┤  Spring Boot 3.3  ├──AudioSocket┤  Asterisk 21   │
│  TypeScript  │             │    (Java 21 LTS)  │     /AMI    │     (PJSIP)    │
└──────────────┘             └─────────┬─────────┘             └───────┬────────┘
                                       │                               │
                                       ▼                               ▼
                             ┌───────────────────┐             ┌────────────────┐
                             │  BANCO DE DADOS   │             │  IA CONVERSAS  │
                             │   PostgreSQL 16   │             │  Python 3.12   │
                             │   (+ pgvector)    │             │ (Google GenAI) │
                             └───────────────────┘             └────────────────┘
```

### 1.1 Camadas do Sistema

| Camada | Tecnologia | Versão / Padrão | Responsabilidade |
| :--- | :--- | :--- | :--- |
| **Borda / Ingress** | Caddy Server | 2-alpine | Terminação TLS (HTTPS/WSS), HSTS, CSP, mitigação de scanning e proxy reverso |
| **Frontend Web** | React + TypeScript | React 18/19, Vite 8, Tailwind CSS | Interfaces SPA reativas (Painel Principal, Call Center e Insights) |
| **Núcleo de Negócio** | Spring Boot | 3.3.5 / Java 21 LTS | Clean Architecture, Spring Security, JPA/Hibernate, Dapper-like repositories, WebSocket STOMP |
| **Telefonia & SIP** | Asterisk PBX | 21 LTS | PJSIP stack, WebSockets (WSS), AudioSocket (porta 9092), ARI, AMI |
| **Agente de Voz IA** | Python / Asyncio | Python 3.12, Google GenAI SDK | Processamento em tempo real de frames de áudio PCM, detecção de voz (VAD), LLM e TTS |
| **Processador Analítico** | Python / Asyncio | Python 3.12, Librosa, WebRTC | Transcrição de áudio em batch, diarização, extração de métricas de qualidade |
| **Persistência** | PostgreSQL | 16-alpine com `pgvector` | Dados relacionais, CDRs, auditoria, configurações e embeddings vetoriais |
| **Transposição WebRTC** | Coturn | 4.6.2 (RFC 5766) | Servidor STUN e TURN para transposição de NAT em chamadas remotas |
| **Segurança Host/SIP** | Fail2ban + nftables | Alpine / Host Network | Detecção de ataques de força bruta SIP e bloqueios dinâmicos no firewall |

---

## 2. Metodologia de Desenvolvimento & Boas Práticas

1. **Clean Architecture & DDD (Domain-Driven Design):**
   - Separação estrita entre Entidades de Domínio, Repositórios, Serviços de Aplicação e Controladores HTTP/WebSocket.
   - DTOs desacoplados e imutáveis (`records` no Java 21 e `interfaces` estritas no TypeScript).
2. **Segurança por Design (OWASP Top 10 & Zero Trust):**
   - Hashing de senhas com **Argon2id** (resistente a ataques de GPU/ASIC).
   - Sessões baseadas em JWT com claim `perm` (RBAC dinâmico r/w) e refresh token em cookie `httpOnly`, `Secure` e `SameSite=Strict`.
   - Streaming Tokens temporários (TTL 60s) para WebSocket/SSE (evitando vazamento de JWT na query string).
   - Sanitização de inputs e mitigação rigorosa de injeção SQL via prepared statements parametrizados.
3. **Resiliência e Alta Disponibilidade:**
   - *Graceful degradation:* Se a API de IA externa falhar, a chamada telefônica cai de forma elegante para mensagem de contingência ou ramal de transbordo humano sem derrubar a ligação.
   - Pools de conexão resilientes com reconexão automática (HikariCP, Asterisk AMI reconnect, WebSocket auto-reconnect com backoff exponencial).
4. **Verificação Empírica Obrigatória (SDLC Gates):**
   - Compilação estrita (`tsc --noEmit`, `mvn compile`, `py_compile`).
   - Testes unitários e de integração executados antes de cada entrega.

---

## 3. Estrutura do Backend (Java 21 / Spring Boot)

O código-fonte do backend localiza-se em `/opt/VoipIA/backend/src/main/java/com/asteriskia/`:

```
com.asteriskia/
├── AsteriskIaApplication.java      # Ponto de entrada Spring Boot
├── config/                         # Segurança, JWT, CORS, Rate Limit, Filtros
│   ├── SecurityConfig.java         # Spring Security 6 chain, stateless sessions
│   ├── JwtService.java             # Assinatura e validação de tokens JWT (HMAC-SHA256)
│   ├── AuthController.java         # Endpoints de Login, Refresh e MFA
│   └── RateLimitFilter.java        # Prevenção de DoS e brute force por IP
├── domain/                         # Módulos de Domínio (DDD)
│   ├── accessgroup/                # RBAC granular (grupos e matriz de permissões)
│   ├── ai/                         # Provedores de IA, modelos e precificação
│   ├── audit/                      # Logs de auditoria e TOTP / 2FA
│   ├── call/                       # Registro de CDRs, estatísticas e custos
│   ├── callcenter/                 # Filas, agentes, skills, scorecards e coaching
│   ├── insights/                   # Transcrições, diarização e relatórios
│   ├── masterdata/                 # Clientes, Unidades de Negócio (BU) e Operações
│   ├── settings/                   # Parâmetros de sistema e senhas SIP
│   ├── ura/                        # Instâncias de URA e fluxo de perguntas
│   └── user/                       # Usuários locais, espelho Active Directory
└── integration/                    # Clientes de integração externa
    ├── ad/                         # Cliente LDAP / Active Directory
    ├── ami/                        # Cliente Asterisk AMI assíncrono
    ├── jira/                       # Cliente Jira Cloud REST API v3
    ├── telegram/                   # Notificações via Telegram Bot API
    └── zabbix/                     # Polling de incidentes Zabbix JSON-RPC
```

---

## 4. Agente de Voz em Tempo Real (`ai-agent`)

O serviço `ai-agent` (`/opt/VoipIA/ai-agent`) é responsável pelo processamento de voz em tempo real:

- **Comunicação com Asterisk:** Conexão bidirecional via protocolo **AudioSocket** (TCP porta `9092`). O Asterisk envia e recebe frames de áudio PCM linear a 8kHz ou 16kHz.
- **Detecção de Atividade de Voz (VAD):** Utiliza o WebRTC VAD para identificar com latência inferior a 30ms quando o usuário começou ou terminou de falar.
- **Loop de Conversação Assíncrono:**
  1. *Captura:* Áudio recebido do AudioSocket é enviado para o modelo de fala da IA (STT).
  2. *Processamento:* O modelo Gemini 2.5 Flash analisa o contexto da conversa, histórico do chamado e regras da URA.
  3. *Síntese & Retorno:* A resposta é sintetizada em PCM e enviada de volta ao AudioSocket do Asterisk em tempo real.
  4. *Integração:* Concluído o diálogo, o ai-agent dispara uma requisição autenticada para `POST /api/v1/calls/register` no backend Java com o áudio gravado e os dados coletados para abertura de ticket no Jira.

---

## 5. Mapeamento Técnico de Telas & Menus

### 5.1 Dashboard
- **Componente:** `frontend/src/components/Dashboard.tsx`
- **Tabelas / Entidades:** `call_records`, `system_configs`, `business_units`.
- **Endpoints:** `GET /api/v1/calls`, `GET /api/v1/stats/trunk-status`, `GET /api/v1/calls/costs/summary`.
- **WebSocket:** Assina `/topic/calls` para inserção em tempo real de novas chamadas.

### 5.2 URA (Módulo 1)
- **Componentes:** `frontend/src/components/ModuloURA.tsx`, `FluxoURATab.tsx`, `UraManagementTab.tsx`.
- **Tabelas:** `uras`, `ura_questions`, `call_records`.
- **Endpoints:** `GET /api/v1/ura`, `POST /api/v1/ura`, `PUT /api/v1/ura/{id}`, `GET /api/v1/ura/{id}/questions`.

### 5.3 Insights
- **Componentes:** `frontend/src/components/InsightsPage.tsx` e SPA `/opt/VoipIA/insights-platform/frontend/`.
- **Tabelas:** `insight_calls`, `insight_transcriptions`, `insight_scorecards`, `insight_evaluations`.
- **Endpoints:** `GET /api/v1/insights/calls`, `GET /api/v1/insights/calls/{id}/audio`, `GET /api/v1/insights/dashboard`, `POST /api/v1/insights/scorecards`.

### 5.4 Call Center
- **Componentes:** `frontend/src/components/CallCenterPage.tsx` e SPA `/opt/VoipIA/callcenter-platform/frontend/`.
- **Submódulos:**
  - *Desktop do Agente:* `DesktopAgenteTab.tsx`, `OperationSidebar.tsx`, `OverviewTab.tsx`.
  - *Filas & Agentes:* `AgentesTab.tsx`, `FilasTab.tsx`, `SkillsTab.tsx`.
  - *Supervisão:* `SupervisaoTab.tsx` (escuta e monitoria via Asterisk AMI).
  - *Flow Builder:* `FluxosTab.tsx` (desenho gráfico de grafos de decisão).
- **Endpoints:** `/api/v1/callcenter/**`.

### 5.5 Financeiro
- **Componente:** `frontend/src/components/Financeiro.tsx`.
- **Tabelas:** `ai_costs`, `ai_model_pricing`.
- **Endpoints:** `/api/v1/calls/costs/summary`, `/api/v1/insights/costs/summary`.

### 5.6 Usuários & Grupos de Acesso (RBAC)
- **Componentes:** `frontend/src/components/Users.tsx`, `AccessGroups.tsx`.
- **Tabelas:** `app_users`, `user_business_units`, `access_groups`, `access_group_permissions`.
- **Endpoints:** `GET /api/v1/users`, `POST /api/v1/users`, `GET /api/v1/access-groups`, `PUT /api/v1/access-groups/{id}`.

### 5.7 Auditoria
- **Componente:** `frontend/src/components/Auditoria.tsx`.
- **Tabela:** `audit_logs`.
- **Endpoints:** `GET /api/v1/audit/logs` (paginado, filtrável por usuário, ação, período e IP).
