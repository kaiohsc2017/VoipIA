# 🔬 Referência Técnica de Engenharia — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.5 Enterprise  
> **Stack Principal:** Asterisk 21 LTS + Spring Boot 3.3 (Java 21) + Python 3.12 + React (TypeScript Strict) + PostgreSQL 16 (pgvector) + Caddy 2  
> **Classificação:** Engenharia de Software / Telecomunicações / Inteligência Artificial  
> **Data de Atualização:** 20 de Agosto de 2026  

---

## 1. Visão Geral da Engenharia

O **VoipIA** é projetado sob os mais rigorosos padrões de engenharia de software para ambientes corporativos de alta densidade e missão crítica. A plataforma integra quatro subsistemas fundamentais:
1. **Telecomunicações de Alta Densidade:** Baseado no **Asterisk 21 LTS** com suporte nativo a SIP (`chan_pjsip`), WebSockets seguros (`wss://`), streaming bidirecional via `app_audiosocket` e NAT Traversal via **Coturn STUN/TURN**.
2. **Backend Corporativo & Regras de Negócio:** Arquitetura desacoplada com **Spring Boot 3.3** em Java 21 LTS, utilizando **Clean Architecture**, DDD, JPA/Hibernate e migrações versionadas via **Flyway (V1 a V96)**.
3. **Inteligência Artificial & Speech Analytics:** Pipeline assíncrono em Python 3.12 integrando **Google Gemini 2.5 Flash**, algoritmos de detecção de atividade de voz (**WebRTC VAD**), transcrição com diarização e busca vetorial com **pgvector**.
4. **Frontends Modernos & WebRTC:** Aplicações Single-Page (SPA) em React com TypeScript em modo estrito, Vite e biblioteca `JsSIP` conectada via WebSockets seguros.

---

## 2. PBX & Motor de Voz — Asterisk 21 LTS

### 2.1. Configuração PJSIP (`chan_pjsip`)
O Asterisk opera exclusivamente com a stack moderna `chan_pjsip`, eliminando o legado `chan_sip`.
* **Transportes de Sinalização:**
  * `transport-udp` / `transport-tcp`: Escuta na porta `5060` para troncos de operadoras.
  * `transport-wss`: Escuta na porta `8088` para conexões WebRTC seguras vindas do Caddy.
* **Codecs Habilitados:** `g711a` (alaw), `g711u` (ulaw), `opus`, `gsm`.
* **Configuração RTP (`res_rtp_asterisk`):**
  * Faixa de portas estritamente vinculada em `rtp.conf`:
    ```ini
    [general]
    rtpstart=16000
    rtpend=16500
    strictrtp=yes
    icesupport=yes
    ```

### 2.2. Módulo AudioSocket (`app_audiosocket`)
O `app_audiosocket` permite o envio bidirecional de fluxos de áudio de chamadas em tempo real via socket TCP para o container `voipia-ai-agent`:
* **Dialplan (`extensions.conf`):**
  ```asterisk
  [ura-ia]
  exten => 2000,1,NoOp(Chamada URA Inteligente - AudioSocket)
   same => n,Answer()
   same => n,AudioSocket(172.16.8.13:9092,${UNIQUEID})
   same => n,Hangup()
  ```

### 2.3. Asterisk Manager Interface (AMI)
* **Porta:** TCP `5038` (vinculada apenas à rede privada Docker).
* **Consumidor:** `voipia-backend` (Spring Boot) via biblioteca AMI Java.
* **Ações Principais:**
  * `Originate`: Discagem automática e callbacks.
  * `Hangup`: Encerramento forçado de chamadas.
  * `ChanSpy`: Escuta silenciosa de ramais e sussurro (*Whisper*) para supervisores.
  * `QueueStatus`: Telemetria em tempo real das filas do Call Center.

---

## 3. Agente Conversacional de IA (`voipia-ai-agent`)

### 3.1. Arquitetura do Servidor de Áudio
O container `voipia-ai-agent` é construído em **Python 3.12 asyncio** e implementa um servidor TCP na porta `9092`.

```mermaid
flowchart LR
    Ast["Asterisk (app_audiosocket)"] <-->|TCP :9092 (PCM 16-bit)| VAD["WebRTC VAD (Filtro de Ruído & Silêncio)"]
    VAD <-->|Frames de Áudio| Pipeline["Pipeline Assíncrono (Python)"]
    Pipeline <-->|gRPC / HTTPS 443| Gemini["Google Gemini 2.5 Flash SDK"]
    Pipeline -->|POST /calls/register| Backend["voipia-backend (Spring Boot)"]
```

### 3.2. Formato do Protocolo AudioSocket
1. **Cabeçalho:** 3 bytes (`Type: 1 byte`, `Length: 2 bytes Big-Endian`).
2. **Tipos de Mensagens:**
   * `0x01` — Identificador UUID da chamada (16 bytes UUIDv4).
   * `0x10` — Payload de Áudio PCM Linear (Mono, 16-bit signed, 8000Hz ou 16000Hz).
   * `0x00` — Evento de Desconexão / Hangup.

### 3.3. WebRTC VAD (Voice Activity Detection)
* **Biblioteca:** `webrtcvad`.
* **Modo de Agressividade:** 2 (Equilíbrio entre sensibilidade e supressão de ruído de fundo).
* **Tamanho do Frame:** 20ms a 30ms (320 a 480 amostras em 16kHz).
* **Lógica de Barge-In:** Ao detectar 3 frames consecutivos de fala humana enquanto a IA está emitindo áudio, o streamer interrompe o envio de áudio imediatamente para ouvir o interlocutor.

---

## 4. Backend Corporativo — Spring Boot 3.3 (Java 21 LTS)

### 4.1. Clean Architecture & Estrutura de Pacotes
```
com.asteriskia (artefato: voipia-backend)
├── config/              # Configurações de Segurança, CORS, JWT, WebSockets STOMP e Beans
├── domain/              # Modelos de Domínio, Repositórios e Serviços de Negócio
│   ├── accessgroup/     # Perfis e Matriz RBAC Granular (>40 permissões)
│   ├── ai/              # Precificação e Provedores de IA
│   ├── alert/           # Motor de Alertas Operacionais
│   ├── audit/           # Trilha de Auditoria e Logs LGPD
│   ├── cadastro/        # Linhas E1/DDR, Operadoras e Números 0800
│   ├── call/            # CDRs de Telefonia, Tags e Histórico
│   ├── callcenter/      # Filas, Agentes, Skills, Gravações, Chat, Co-Browsing, RAG e Flow Builder
│   ├── insights/        # Speech Analytics, Scorecards, Contestações e Coaching
│   ├── masterdata/      # Unidades de Negócio, Clientes e Operações
│   ├── settings/        # Configurações de Sistema e Histórico
│   └── user/            # Gestão de Usuários, MFA TOTP e Vínculo com AD/LDAP
└── integration/         # Integrações com Jira Cloud, AD/LDAP e Telegram
```

### 4.2. Segurança e Criptografia
* **Hash de Senha:** `Argon2PasswordEncoder` / BCrypt com salt de alta complexidade.
* **Assinatura de Tokens:** HMAC-SHA256 / SHA512 com chaves simétricas de alta entropia.
* **MFA:** Time-Based One-Time Password (TOTP — RFC 6238).
* **Rate Limiting:** `RateLimitFilter` baseado em Bucket4j / Sliding Window por IP de origem.

### 4.3. Migrações de Banco de Dados (Flyway)
* Migrações versionadas em `src/main/resources/db/migration/V1__*.sql` até `V96__*.sql`.
* Controle transacional e idempotente de alterações de schema.

---

## 5. Speech Analytics & Insights (`voipia-insights`)

### 5.1. Processamento e Diarização
* Pipeline assíncrono para transcrição de áudios em lote ou em tempo real pós-chamada.
* Separação de canais de áudio estéreo (Canal 1: Atendente / Canal 2: Cliente) para diarização perfeita.
* Identificação de palavras de risco e classificação de sentimento (*Positivo*, *Neutro*, *Negativo*).

### 5.2. Fichas de Monitoria Automática (Scorecards)
* Avaliação por IA com pesos configuráveis e preenchimento de justificativas objetivas.
* Suporte ao workflow de contestação de notas pelo operador e planos de coaching (PDI).

---

## 6. Frontend SPAs & Softphone WebRTC (React + Vite)

### 6.1. Stack do Frontend
* **Core:** React, TypeScript (Strict Mode), Vite.
* **Roteamento:** React Router v6 com lazy loading.
* **Estilização:** Tailwind CSS + Radix UI / Shadcn.
* **Gráficos:** Recharts.
* **Softphone WebRTC:** Biblioteca `JsSIP` conectada via WebSocket seguro (`wss://app.voiphash.com.br/asterisk-ws`).

### 6.2. NAT Traversal com Coturn (STUN/TURN)
* **STUN Server:** Descoberta de IP público para conexão direta P2P.
* **TURN Server:** Relay de mídia criptografado quando o cliente está sob NAT simétrico ou firewall corporativo restritivo.
* **Portas Coturn:** `3478/udp+tcp`, `5349/udp+tcp` e faixa `49152-49652/udp`.

---

## 7. Banco de Dados PostgreSQL 16 + pgvector

### 7.1. Extensão pgvector
* Habilitada via `CREATE EXTENSION IF NOT EXISTS vector;`.
* Armazena vetores de embeddings (dimensão 768 / 1536) gerados pelo Google Gemini para a base de conhecimento de atendimento (`cc_kb_chunks`) e busca semântica em tempo real via índice vetorial HNSW/IVFFlat.
