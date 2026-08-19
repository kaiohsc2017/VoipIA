# 🔬 Referência Técnica de Engenharia — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.2 Enterprise  
> **Stack Principal:** Asterisk 21 LTS + Spring Boot 3.3 (Java 21) + FastAPI (Python 3.12) + React 18 (TypeScript Strict) + PostgreSQL 16 (pgvector) + Caddy 2  
> **Classificação:** Engenharia de Software / Telecomunicações / Inteligência Artificial  
> **Data de Atualização:** Agosto de 2026  

---

## 1. Visão Geral da Engenharia

O **VoipIA** é projetado sob os mais rigorosos padrões de engenharia de software para ambientes de alta densidade e missão crítica. A plataforma integra três ecossistemas fundamentais:
1. **Telecomunicações de Alta Densidade:** Baseado no **Asterisk 21 LTS** com suporte nativo a SIP (`chan_pjsip`), WebSockets seguros (`wss://`), streaming via `app_audiosocket` e NAT Traversal via **Coturn STUN/TURN**.
2. **Backend Corporativo & Regras de Negócio:** Arquitetura desacoplada com **Spring Boot 3.3** em Java 21 LTS, utilizando **Clean Architecture**, DDD, JPA/Hibernate e migrações versionadas via **Flyway (V1 a V14)**.
3. **Inteligência Artificial & Speech Analytics:** Pipeline assíncrono em Python 3.12 integrando **Google Gemini 2.5 Flash**, algoritmos de detecção de atividade de voz (**WebRTC VAD**), busca vetorial com **pgvector** e automação com **FastAPI**.

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
* **Lógica de Barge-In:** Ao detectar 3 frames consecutivos de fala humana enquanto a IA está emitindo áudio, o streamer interrompe o envio de áudio imediatamente para ouvir o usuário.

---

## 4. Backend Corporativo — Spring Boot 3.3 (Java 21 LTS)

### 4.1. Clean Architecture & Estrutura de Pacotes
```
com.asteriskia
├── config/              # Configurações de Segurança, CORS, WebSockets e Beans
├── domain/              # Modelos de Domínio, Repositórios e Serviços de Negócio
│   ├── accessgroup/     # Perfis e Matriz RBAC Granular
│   ├── ai/              # Precificação e Provedores de IA
│   ├── alert/           # Motor de Alertas Operacionais
│   ├── audit/           # Trilha de Auditoria e Logs LGPD
│   ├── cadastro/        # Linhas E1/DDR, Operadoras e Números 0800
│   ├── call/            # CDRs de Telefonia, Tags e Histórico
│   ├── callcenter/      # Filas, Agentes, Skills, Gravações, Chat e Flow Builder
│   └── integration/     # Integrações com Jira Cloud, Zabbix, AD e Telegram
```

### 4.2. Segurança e Criptografia
* **Hash de Senha:** `Argon2PasswordEncoder` (Memory: 65536 KB, Iterations: 3, Parallelism: 1).
* **Assinatura de Tokens:** HMAC-SHA256 / SHA512 com chaves simétricas de alta entropia.
* **Rate Limiting:** `RateLimitFilter` baseado em Bucket4j / Sliding Window por IP de origem.

### 4.3. Migrações de Banco de Dados (Flyway)
* Migrações versionadas em `src/main/resources/db/migration/V1__*.sql` até `V14__*.sql`.
* Controle transacional e idempotente de alterações de schema.

---

## 5. Plataforma de Agentes de Automação — FastAPI (Python 3.12)

### 5.1. Motores de Execução (Task Runners)
* **SSH Runner:** Conexões seguras via `asyncssh` para servidores Linux corporativos.
* **HTTP Runner:** Execução de testes de API e crawlers web com `httpx`.
* **DB Runner:** Validações de integridade em bancos relacionais com `SQLAlchemy / asyncpg`.
* **Log Analyzer:** Parser em tempo real de logs de sistema para identificação de padrões de falha.

### 5.2. Agendador (Scheduler)
* Baseado em `APScheduler` com persistência em banco PostgreSQL.
* Execução paralela sem bloqueio da thread principal.

---

## 6. Frontend SPA & Softphone WebRTC (React 18 + Vite)

### 6.1. Stack do Frontend
* **Core:** React 18, TypeScript (Strict Mode), Vite.
* **Roteamento:** React Router v6.
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
* Armazena vetores de embeddings (dimensão 768 / 1536) gerados pelo Google Gemini para a base de conhecimento de atendimento e busca semântica de chamadas.
* Índices do tipo **HNSW** e **IVFFlat** para buscas de vizinhos mais próximos (Cosine Similarity).

### 7.2. Principais Tabelas Relacionais
* `call_records` — Registros completos de bilhetagem (CDR).
* `ura_instances` / `ura_questions` — Parametrização da URA inteligente.
* `users` / `access_groups` / `resource_permissions` — Matriz RBAC.
* `scorecards` / `evaluations` / `coaching_plans` — Módulo de Qualidade (QM).
* `audit_logs` — Trilha de auditoria imutável LGPD.
* `agents` / `agent_runs` / `secrets` — Plataforma de Agentes de Automação.

---

## 8. Proxy Reverso & Hardening (Caddy 2)

* **TLS Automático:** Gerenciamento de certificados TLS 1.3 via Let's Encrypt / ZeroSSL.
* **Content Security Policy (CSP):** Política restritiva de segurança bloqueando injeção de scripts maliciosos.
* **Admin Socket:** Gerenciamento interno via socket Unix (`/run/caddy-admin/admin.sock`), eliminando portas TCP administrativas expostas.
