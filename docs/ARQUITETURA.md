# 🏛️ Arquitetura de Software & Infraestrutura — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.2 Enterprise (Asterisk 21 LTS + Spring Boot 3.3 + FastAPI + React 18 + Google Gemini)  
> **Classificação de Segurança:** OWASP ASVS Nível 2 / Zero Trust / Zero Secrets  
> **Data de Atualização:** Agosto de 2026  

---

## 1. Visão Executiva da Arquitetura

O **VoipIA** é o ecossistema corporativo de missão crítica projetado para unificar telefonia IP de alta densidade, inteligência artificial generativa conversacional em tempo real, plataforma de atendimento de Call Center omnicanal, auditoria e transcrição de gravações (*Speech Analytics / Insights*) e agentes de automação autônomos.

O sistema opera em containers Docker desacoplados sob uma rede bridge isolada (`voipia-net: 172.16.8.0/24`), com proxy reverso de terminação TLS automática (Caddy 2), persistência relacional e vetorial no **PostgreSQL 16 com extensão pgvector**, e gateway de mídia WebRTC com **Coturn (STUN/TURN)**.

```mermaid
flowchart TD
    subgraph Edge ["🌐 Borda & Segurança (Host / Internet)"]
        UserBrowser["💻 Navegador Web (React SPAs + Softphone WebRTC)"]
        TelcoCarrier["📞 Operadora Telecom (Tronco SIP E1/SIP Trunk)"]
        Caddy["🔒 Caddy 2 Gateway (TLS 1.3 / Portas 80, 443, 8086, 8443)"]
        CoturnServer["📡 Coturn STUN/TURN Relay (Portas 3478, 5349, 49152-49652)"]
        Fail2banIPS["🛡️ Fail2ban + nftables (Lockdown SIP & Rate Limit)"]
    end

    subgraph DockerNet ["📦 Rede Privada Docker (voipia-net: 172.16.8.0/24)"]
        Frontend["🖥️ voipia-frontend (Nginx - React 18 SPAs)\nIP: 172.16.8.15 | Porta: 80"]
        Backend["⚙️ voipia-backend (Spring Boot 3.3 / Java 21)\nIP: 172.16.8.14 | Portas: 8080, WS STOMP"]
        AgentsAPI["🤖 voipia-agents-api (FastAPI / Python 3.12)\nIP: 172.16.8.16 | Porta: 8000, WS"]
        AIAgent["🎙️ voipia-ai-agent (Python 3.12 AudioSocket Server)\nIP: 172.16.8.13 | Porta: 9092"]
        InsightsWorker["📊 voipia-insights (Python 3.12 Speech Analytics)\nIP: 172.16.8.17 | Batch Process"]
        AsteriskPBX["☎️ voipia-asterisk (Asterisk 21 LTS)\nIP: 172.16.8.12 | Portas: 5060 SIP, 16000-16500 RTP, 8088 WS, 5038 AMI"]
        DockerHelper["🔧 voipia-docker-helper (Python Unix Socket Gateway)\nIP: 172.16.8.18 | Porta: 8001"]
        PostgresDB[("🗄️ voipia-postgres (PostgreSQL 16 + pgvector)\nIP: 172.16.8.11 | Porta: 5432")]
    end

    subgraph CloudServices ["☁️ Serviços Externos em Nuvem"]
        GeminiAI["🤖 Google Gemini 2.5 Flash / Vertex AI (STT + LLM + TTS)"]
        AlternativeAI["⚡ Provedores Alternativos (Anthropic, OpenAI, ElevenLabs, Grok)"]
        JiraCloud["🎫 Jira Cloud REST API v3"]
        ActiveDirectory["🏢 Active Directory / LDAPS (Porta 636)"]
        TelegramCloud["📱 Telegram Bot API"]
    end

    UserBrowser -->|HTTPS 443| Caddy
    UserBrowser -->|WSS 443 /asterisk-ws| Caddy
    UserBrowser <-->|STUN/TURN 3478 / 49152-49652| CoturnServer
    TelcoCarrier -->|SIP 5060 UDP/TCP| AsteriskPBX
    TelcoCarrier -->|RTP 16000-16500 UDP| AsteriskPBX
    Fail2banIPS -.->|Monitora logs & aplica nftables| AsteriskPBX

    Caddy -->|Proxy UI| Frontend
    Caddy -->|/api/* & /ws/*| Backend
    Caddy -->|/agents/api/* & /agents/ws/*| AgentsAPI
    Caddy -->|/asterisk-ws| AsteriskPBX

    AsteriskPBX <-->|AudioSocket TCP 9092| AIAgent
    AsteriskPBX <-->|AMI TCP 5038| Backend
    AIAgent -->|REST POST /calls/register| Backend
    InsightsWorker <-->|Processamento Assíncrono| PostgresDB

    Backend <-->|JPA / Dapper / SQL 5432| PostgresDB
    AgentsAPI <-->|SQLAlchemy / SQL 5432| PostgresDB
    Backend <-->|REST X-Internal-Key| DockerHelper

    AIAgent -->|HTTPS 443| GeminiAI
    AIAgent -->|HTTPS 443| AlternativeAI
    Backend -->|HTTPS 443| JiraCloud
    Backend -->|LDAPS 636| ActiveDirectory
    Backend & AgentsAPI -->|HTTPS 443| TelegramCloud
```

---

## 2. Componentes & Camadas do Sistema

A arquitetura do **VoipIA** é dividida em subsistemas especializados e desacoplados:

### 2.1. PBX & Motor de Voz (Asterisk 21 LTS)
* **chan_pjsip:** Módulo moderno de SIP para terminação de troncos de operadoras E1/SIP e ramais IP.
* **res_pjsip_transport_websocket:** Suporte nativo a WebSockets (`wss://`) para conexão de softphones WebRTC no navegador.
* **res_rtp_asterisk:** Faixa de portas de mídia RTP estritamente parametrizada entre `16000` e `16500/udp`.
* **app_audiosocket:** Extensão de streaming bidirecional de áudio em tempo real para o container `ai-agent` através de conexão TCP (`:9092`) de ultrabaixa latência.
* **AMI (Asterisk Manager Interface):** Conexão TCP na porta `5038` consumida pelo backend Spring Boot para discagem automática, escuta de chamadas (*Spy*), sussurro (*Whisper*) e captura de eventos de fila.

### 2.2. Agente Conversacional de IA (voipia-ai-agent)
* Desenvolvido em **Python 3.12 asyncio**.
* Atua como servidor de áudio AudioSocket TCP (porta `9092`), recebendo frames PCM Linear 8kHz/16kHz 16-bit.
* Integra o **WebRTC VAD (Voice Activity Detection)** para interrupção de fala (*barge-in* natural).
* Conecta-se à API do **Google Gemini 2.5 Flash** (e provedores secundários via *Function Calling*), realizando transcrição de fala (STT), raciocínio contextual e síntese de voz (TTS) humanizada em milissegundos.

### 2.3. Backend Principal (voipia-backend — Spring Boot 3.3)
* Desenvolvido em **Java 21 LTS** seguindo princípios de **Clean Architecture e DDD**.
* Gerenciamento de persistência com **Spring Data JPA/Hibernate** e migrações automatizadas via **Flyway (V1 a V14)**.
* Notificações em tempo real com **WebSocket STOMP** para atualização dinâmica do Dashboard, status de operadores e Wallboards de fila.
* Integração nativa com Jira Cloud (criação automática de chamados a partir da URA de voz) e sincronização corporativa com Active Directory / LDAPS.

### 2.4. Plataforma de Agentes de Automação (voipia-agents-api & frontend)
* Backend em **FastAPI (Python 3.12)** com arquitetura assíncrona.
* Executores modulares de tarefas: agentes SSH, crawlers Web, consultores de Banco de Dados e analisadores de Logs.
* Agendador integrado (*Scheduler*) com persistência de execuções, histórico e cofre de segredos criptografado.

### 2.5. Frontend SPA & Softphone WebRTC (voipia-frontend)
* Desenvolvido em **React 18 + TypeScript (Strict) + Vite**.
* Estilização moderna com **Tailwind CSS**, componentes acessíveis e gráficos interativos com **Recharts**.
* Softphone WebRTC integrado via **JsSIP**, permitindo chamadas telefônicas diretamente na interface do navegador com suporte a DTMF, Hold, Transferência e Mute.

### 2.6. Speech Analytics & Insights (voipia-insights)
* Motor assíncrono em Python para transcrição diarizada de chamadas gravadas.
* Análise de sentimento, identificação de palavras de risco (jurídico, PROCON, cancelamento), cálculo de NPS e preenchimento autônomo de fichas de monitoria de qualidade (Scorecards).

### 2.7. Banco de Dados Unificado (PostgreSQL 16 + pgvector)
* Banco de dados relacional unificado para toda a plataforma.
* Extensão **pgvector** habilitada para busca semântica por similaridade de vetores (*Embeddings* de base de conhecimento e transcrições de chamadas).

---

## 3. Fluxos Principais de Dados

### 3.1. Atendimento da URA Conversacional com IA (Tempo Real)

```mermaid
sequenceDiagram
    autonumber
    actor Cliente as Cliente (Telefone)
    participant Op as Operadora SIP
    participant Ast as Asterisk 21 LTS
    participant AI as voipia-ai-agent (Python)
    participant LLM as Google Gemini 2.5 Flash
    participant Back as voipia-backend (Java)
    participant Jira as Jira Cloud REST API

    Cliente->>Op: Disca para número corporativo (ex: 0800)
    Op->>Ast: Convite SIP INVITE (G.711 / Opus)
    Ast->>Ast: Executa Dialplan (Identifica URA Inteligente: 2000)
    Ast->>AI: Abre conexão AudioSocket TCP (Porta 9092)
    AI->>LLM: Inicia sessão conversacional com Prompt da URA
    AI-->>Ast: Envia áudio de saudação em PCM Linear
    Ast-->>Cliente: Cliente ouve a saudação humanizada da IA
    loop Diálogo Humanizado com VAD (Barge-In)
        Cliente->>Ast: Fala o problema ou solicitação
        Ast->>AI: Stream de frames de áudio PCM
        AI->>AI: WebRTC VAD detecta pausa de voz
        AI->>LLM: Envia transcrição (STT) e solicita resposta
        LLM-->>AI: Retorna texto da resposta + dados extraídos
        AI-->>Ast: Sintetiza áudio (TTS) e envia ao Asterisk
        Ast-->>Cliente: Cliente ouve a resposta em tempo real
    end
    Cliente->>Ast: Desliga a ligação (Hangup)
    Ast->>AI: Notifica encerramento da chamada
    AI->>Back: POST /api/v1/calls/register (Metadados + Resumo + JSON)
    Back->>Back: Persiste CDR e calcula custos de IA
    Back->>Jira: Cria chamado de suporte via REST API
    Back-->>Jira: Anexa transcrição e dados estruturados
```

### 3.2. Atendimento Humano no Call Center com Softphone WebRTC

```mermaid
sequenceDiagram
    autonumber
    actor Cliente as Cliente
    actor Agente as Operador (Desktop WebRTC)
    participant Ast as Asterisk 21 LTS
    participant Back as voipia-backend
    participant DB as PostgreSQL 16

    Cliente->>Ast: Chamada entra na Fila de Suporte
    Ast->>Back: Evento AMI: Caller Joined Queue
    Back->>Agente: WebSocket STOMP: Alerta de Chamada Entrante
    Agente->>Ast: WebRTC INVITE via wss://app.voiphash.com.br/asterisk-ws
    Ast-->>Agente: Áudio bidirecional estabelecido (RTP WebRTC)
    Ast->>Ast: Inicia gravação de áudio no volume /var/spool/asterisk/monitor
    Agente->>Agente: Atende cliente, consulta Base de Conhecimento (KB)
    Agente->>Back: POST /api/v1/callcenter/disposition (Tabulação da chamada)
    Agente->>Ast: Encerra chamada (Hangup)
    Ast->>Back: Evento AMI: Call Ended + WAV gerado
    Back->>DB: Atualiza CDR do Call Center e dispara fila do Insights
```

---

## 4. Regras Críticas de Negócio & Governança

### 4.1. Diretriz de Não-Mascaramento de CDR (CDR Privacy Compliance)
* Por exigência mandatória de governança corporativa, auditoria jurídica e controle de tráfego de telecomunicações, **os números de telefone completos (ANI e B-Number) NUNCA são mascarados** em relatórios, tabelas ou APIs para usuários autenticados e autorizados.

### 4.2. Isolamento Multitenant por Unidade de Negócio (BU Scoping)
* Cada usuário, fila, operador e registro de URA é associado a uma ou mais **Unidades de Negócio (BU)** cadastradas em `user_business_units`.
* As consultas ao histórico de chamadas, transcrições e cadastros filtram estritamente pelo escopo da BU do usuário logado.
* Usuários com perfil `ADMIN` possuem visão global de todas as BUs.

### 4.3. Matriz RBAC Granular (Mais de 40 Recursos)
* Controle de acesso baseado em permissões granulares por chave de recurso (`resource_key`), permitindo operações de Leitura (`r`) ou Leitura e Escrita (`rw`) em módulos de Telecom, Call Center, Insights, Financeiro e Administração.

---

## 5. Segurança, Threat Modeling & DevSecOps (OWASP ASVS Nível 2)

```mermaid
flowchart LR
    A["OWASP Top 10"] --> B["1. Argon2id + 2FA TOTP (Anti-Brute Force)"]
    A --> C["2. Sem Mascaramento de CDR (Diretriz de Auditoria)"]
    A --> D["3. JPA/Dapper Parametrizado (Zero SQL Injection)"]
    A --> E["4. Trilha de Auditoria LGPD (Audit Logs)"]
    A --> F["5. Zero Secrets (Credenciais protegidas em env/.env)"]
    A --> G["6. Fail2ban + nftables (Lockdown de portas SIP/RTP)"]
```

### 5.1. Diretrizes de Segurança Invioláveis:
1. **Autenticação Segura:** Senhas locais protegidas com algoritmo **Argon2id** de alta entropia e suporte a **Segundo Fator de Autenticação (2FA / TOTP)** via QR Code.
2. **Tokens de Sessão:** Tokens **JWT (JSON Web Tokens)** assinados com chaves simétricas HMAC-SHA256 de 512 bits e expiração controlada.
3. **Proteção Anti-Força Bruta & Lockdown SIP:**
   * **Camada Web:** `RateLimitFilter` bloqueia requisições excessivas por IP.
   * **Camada SIP:** `Fail2ban` monitora logs de autenticação do Asterisk e bloqueia IPs maliciosos diretamente no `nftables` do kernel Linux.
4. **Zero Secrets em Código:** Nenhuma credencial de banco, API Key ou segredo de integração é versionada no Git. Todas residem no arquivo `/opt/VoipIA/env/.env` com permissões `chmod 600`.
5. **Trilha de Auditoria LGPD:** Todas as consultas, alterações de cadastros, escutas de chamadas e exportações são registradas com carimbo UTC, ID do usuário, IP de origem e detalhes da operação na tabela `audit_logs`.

---

## 6. Benchmark Comparativo de Mercado

| Dimensão / Funcionalidade | Genesys Cloud CX | Twilio Flex | Asterisk Puro (Open Source) | **VoipIA (v3.2 Enterprise)** |
|---|---|---|---|---|
| **Arquitetura de Deploy** | Cloud SaaS Exclusivo | Cloud PaaS | On-Premise Manual | **Docker Container On-Premise / Cloud Híbrida (HA)** |
| **URA Conversacional de IA** | Cobrança extra por minuto | Requer desenvolvimento | Ausente (apenas DTMF) | **Nativa via AudioSocket + Google Gemini 2.5 Flash** |
| **Softphone WebRTC** | Sim (WebRTC) | Sim (WebRTC) | Requer configuração complexa | **Nativo com JsSIP + Coturn STUN/TURN integrado** |
| **Speech Analytics & Insights** | Módulo caro adicional | Integração de terceiros | Ausente | **Nativo (Diarização, Sentimento, Scorecards e NPS)** |
| **Plataforma de Agentes Autônomos** | Não possui | Não possui | Não possui | **Nativa (FastAPI + Agentes SSH, Web, DB e Logs)** |
| **Custo Total de Propriedade (TCO)** | Altíssimo (por assento/mês) | Alto (pago por minuto/evento) | Baixo (alto custo de sustentação) | **Baixíssimo (Uso de hardware próprio + IA sob demanda)** |
| **Privacidade & Controle de Dados** | Nuvem de Terceiros | Nuvem de Terceiros | Controle Total | **Controle Total (Zero Trust / On-Premise)** |

---

## 7. Resiliência, Alta Disponibilidade & Degradação Graciosa

1. **Degradação Graciosa da IA:** Caso a API do Google Gemini sofra instabilidade ou latência excessiva, a URA executa uma mensagem pré-gravada de contingência e transfere a ligação imediatamente para a fila humana do Call Center sem derrubar a chamada.
2. **Sondas de Saúde (Healthchecks):** Todos os containers Docker contam com verificações ativas de integridade (`pg_isready`, sondas HTTP `/health`, verificação de porta TCP).
3. **Isolamento de Falhas:** O travamento eventual do módulo de relatórios ou de agentes de automação não afeta a recepção de chamadas do Asterisk nem a API de telefonia.
