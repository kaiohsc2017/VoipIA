# 🌐 Matriz de Conectividade de Rede & Segurança — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.2 Enterprise  
> **Ambientes Alvo:** Linux Ubuntu 22.04/24.04 LTS e Oracle Linux 9 (UEK/RHEL)  
> **Padrão de Segurança:** Zero Trust, DevSecOps, Menor Privilégio e Hardening OWASP ASVS L2  
> **Data de Atualização:** Agosto de 2026  

---

## 1. Visão Geral da Arquitetura de Rede

A topologia de rede do **VoipIA** é segmentada em 4 zonas lógicas de segurança para garantir o isolamento estrito de processos e proteger os fluxos de telecomunicações, dados de bilhetagem e chaves de IA:

```mermaid
flowchart TB
    subgraph Internet ["Zona 1: Internet Pública / Redes Externas"]
        UserBrowser["🌐 Navegadores dos Usuários (Portal Web / Softphone WebRTC)"]
        TelcoCarrier["📞 Operadoras Telecom (Troncos E1 / SIP Trunk)"]
        GoogleAI["☁️ Google Gemini API / Vertex AI\n(generativelanguage.googleapis.com)"]
        CloudServices["☁️ Serviços Cloud (Jira Cloud, Telegram Bot)"]
        GitRemotes["📦 Repositórios Git (GitHub)"]
    end

    subgraph DMZ ["Zona 2: Borda & Proxy Reverso (DMZ / Host)"]
        Caddy["🔒 Caddy 2 Reverse Proxy (TLS 1.3 / OWASP Headers)\nPortas: 80/TCP, 443/TCP+UDP (HTTP/3)"]
        CoturnRelay["📡 Coturn STUN/TURN Server\nPortas: 3478/UDP+TCP, 5349/UDP+TCP, 49152-49652/UDP"]
        Fail2ban["🛡️ Fail2ban + nftables IPS (Lockdown SIP)"]
    end

    subgraph AppNetwork ["Zona 3: Rede Interna Docker (voipia-net: 172.16.8.0/24)"]
        FrontendContainer["🖥️ Web SPA Nginx (voipia-frontend)\nPorta: 80 | IP: 172.16.8.15"]
        BackendContainer["⚙️ Backend API Spring Boot (voipia-backend)\nPortas: 8080, STOMP | IP: 172.16.8.14"]
        AgentsContainer["🤖 FastAPI Agentes (voipia-agents-api)\nPorta: 8000 | IP: 172.16.8.16"]
        AiAgentContainer["🎙️ AudioSocket AI Server (voipia-ai-agent)\nPorta: 9092 | IP: 172.16.8.13"]
        InsightsContainer["📊 Speech Analytics (voipia-insights)\nIP: 172.16.8.17"]
        AsteriskContainer["☎️ PBX Asterisk 21 (voipia-asterisk)\nPortas: 5060 SIP, 16000-16500 RTP, 8088 WS, 5038 AMI | IP: 172.16.8.12"]
        DockerHelperContainer["🔧 Gateway Docker Helper (voipia-docker-helper)\nPorta: 8001 | IP: 172.16.8.18"]
        DbContainer["🗄️ PostgreSQL 16 + pgvector (voipia-postgres)\nPorta: 5432 | IP: 172.16.8.11"]
    end

    subgraph CorpLAN ["Zona 4: Rede Local Corporativa / Telecom LAN (On-Premises)"]
        ActiveDirectory["🏢 Active Directory / LDAPS\nPorta: 636/TCP"]
        ZabbixServer["📊 Zabbix Monitoring Server\nPorta: 80/443/TCP"]
    end

    UserBrowser -->|HTTPS :443 / WSS :443| Caddy
    UserBrowser <-->|STUN/TURN :3478 / :49152-49652| CoturnRelay
    TelcoCarrier -->|SIP :5060 UDP/TCP| AsteriskContainer
    TelcoCarrier -->|RTP :16000-16500 UDP| AsteriskContainer

    Caddy -->|HTTP :80| FrontendContainer
    Caddy -->|HTTP/WS :8080| BackendContainer
    Caddy -->|HTTP/WS :8000| AgentsContainer
    Caddy -->|WSS :8088| AsteriskContainer

    AsteriskContainer <-->|TCP :9092 AudioSocket| AiAgentContainer
    AsteriskContainer <-->|TCP :5038 AMI| BackendContainer
    BackendContainer -->|TCP :5432| DbContainer
    AgentsContainer -->|TCP :5432| DbContainer
    AiAgentContainer -->|HTTPS :443 Egress| GoogleAI
    BackendContainer -->|HTTPS :443 Egress| CloudServices
    BackendContainer -->|LDAPS :636 Egress| ActiveDirectory
    BackendContainer -->|HTTP/HTTPS Egress| ZabbixServer
    GitRemotes <-.->|HTTPS :443 (CI/CD Egress)| BackendContainer
```

---

## 2. Matriz de Conectividade Externa (Ingress & Egress)

### 2.1. Conexões de Entrada (Ingress — Internet / WAN $\to$ Host VoipIA)

| ID | Origem | Destino | Porta Destino | Protocolo | Finalidade / Serviço | Autenticação / Criptografia |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **VOIP-IN-01** | Navegadores dos Usuários / WAN | Servidor VoipIA (`voipia-caddy`) | **443 / TCP** + **443 / UDP** | HTTPS / HTTP/2 / HTTP/3 | Portal Web, APIs REST, WebSockets STOMP e WebRTC SIP. | TLS 1.3 (Let's Encrypt / Certificado Corporativo) |
| **VOIP-IN-02** | Navegadores dos Usuários / WAN | Servidor VoipIA (`voipia-caddy`) | **80 / TCP** | HTTP | Redirecionamento automático permanente para HTTPS (HTTP 301). | Nenhuma (Apenas Redirecionamento 301 $\to$ 443) |
| **VOIP-IN-03** | Operadora Telecom / Tronco SIP | PBX Asterisk (`voipia-asterisk`) | **5060 / UDP** e **5060 / TCP** | SIP (chan_pjsip) | Sinalização de chamadas telefônicas de entrada e saída. | Autenticação por IP / Digest Auth + Fail2ban |
| **VOIP-IN-04** | Operadora Telecom / Softphones | PBX Asterisk (`voipia-asterisk`) | **16000-16500 / UDP** | RTP / RTCP | Fluxo de mídia de voz (áudio bidirecional G.711 / Opus). | Payload RTP direto |
| **VOIP-IN-05** | Clientes WebRTC (Navegador) | Coturn Server (`voipia-coturn`) | **3478 / UDP+TCP** e **5349 / UDP+TCP** | STUN / TURN / TURNS | Descoberta de NAT e Relay de mídia WebRTC quando em NAT restrito. | Credenciais temporárias HMAC-SHA1 / TLS |
| **VOIP-IN-06** | Clientes WebRTC (Navegador) | Coturn Server (`voipia-coturn`) | **49152-49652 / UDP** | Media Relay | Faixa de portas de retransmissão de mídia TURN para WebRTC. | Relay dinâmico autenticado |

---

### 2.2. Conexões de Saída (Egress — Host VoipIA $\to$ Internet / Nuvem)

| ID | Origem | Destino | Porta Destino | Protocolo | Finalidade / Serviço | Autenticação / Criptografia |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **VOIP-OUT-01** | `ai-agent`, `backend`, `insights` | Google Gemini API & Vertex AI | **443 / TCP** | HTTPS / gRPC | Transcrição de voz (STT), raciocínio conversacional da URA, síntese (TTS) e Speech Analytics. | API Key / Service Account + TLS 1.3 |
| **VOIP-OUT-02** | `ai-agent` & `agents-api` | Provedores Alternativos (Anthropic, OpenAI, ElevenLabs) | **443 / TCP** | HTTPS | Modelos alternativos de linguagem, síntese de voz neural e agentes. | API Keys + TLS 1.3 |
| **VOIP-OUT-03** | `voipia-backend` | Jira Cloud (`*.atlassian.net`) | **443 / TCP** | HTTPS / REST | Criação automática de chamados de suporte a partir da URA de voz. | Basic Auth (E-mail + API Token) + TLS 1.3 |
| **VOIP-OUT-04** | `voipia-backend` & `agents-api` | Telegram Bot API (`api.telegram.org`) | **443 / TCP** | HTTPS / REST | Alertas de custos de IA, incidentes de infraestrutura e relatórios de NPS. | Bot Token + TLS 1.3 |
| **VOIP-OUT-05** | `voipia-backend` | Active Directory Corporativo / LDAP | **636 / TCP** (ou 389) | LDAPS / LDAP | Sincronização de usuários e autenticação corporativa centralizada. | Conta de serviço AD + TLS |
| **VOIP-OUT-06** | `voipia-backend` | Servidor Zabbix Corporativo | **443 / TCP** ou **80 / TCP** | JSON-RPC | Polling de incidentes de infraestrutura para disparo de chamadas automatizadas. | Zabbix Auth Token + TLS |
| **VOIP-OUT-07** | `voipia-caddy` | Let's Encrypt (`acme-v02.api.letsencrypt.org`) | **443 / TCP** | HTTPS / ACME | Emissão e renovação automatizada de certificados TLS. | ACME Challenge HTTP-01 + TLS |
| **VOIP-OUT-08** | Servidor Host (`apt`/`dnf`/`docker`) | Repositórios Linux & Docker Registry | **443 / TCP** | HTTPS | Atualização de pacotes de segurança do SO e download de imagens de containers. | GPG Verification + TLS 1.3 |
| **VOIP-OUT-09** | Servidor Host (`git`) | GitHub (`github.com`) | **443 / TCP** / **22 / TCP** | HTTPS / SSH | Sincronização de código-fonte e esteira de CI/CD. | Personal Access Token (PAT) / SSH Key |

---

### 2.3. Matriz de Domínios & FQDNs para IA (Google Gemini) e Antigravity (AGY)

Para ambientes corporativos com Next-Gen Firewalls (NGFW), Proxies Transparentes ou filtragem por SNI/DNS:

| Domínio / FQDN | Finalidade Operacional | Aplicação | Portas & Protocolos |
| :--- | :--- | :--- | :--- |
| **`generativelanguage.googleapis.com`** | API principal do Google Gemini (STT, URA conversacional, Sentimento, Scorecards e Embeddings) | **VoipIA & AGY** | `443/TCP (HTTPS / gRPC)` |
| **`content-generativelanguage.googleapis.com`** | Streaming de áudio, upload de lotes e multimodais | **VoipIA** | `443/TCP (HTTPS)` |
| **`aiplatform.googleapis.com`** | Google Vertex AI (quando utilizado projeto GCP corporativo) | **VoipIA & AGY** | `443/TCP (HTTPS)` |
| **`ai.google.dev`** | Sincronização de preços e catálogo de modelos de IA | **VoipIA** | `443/TCP (HTTPS)` |
| **`cloudaicompanion.googleapis.com`** | Assistência e telemetria de desenvolvimento do Gemini | **AGY CLI** | `443/TCP (HTTPS)` |
| **`antigravity.google`** | Portal oficial, sidecars e documentação do AGY | **AGY CLI** | `443/TCP (HTTPS)` |
| **`*.antigravity.google`** | Subdomínios de autenticação e ecossistema Antigravity | **AGY CLI** | `443/TCP (HTTPS)` |
| **`accounts.google.com`** | Autenticação OAuth 2.0 e contas Google | **AGY CLI** | `443/TCP (HTTPS)` |
| **`oauth2.googleapis.com`** | Renovação de tokens OAuth 2.0 / Service Accounts | **VoipIA & AGY** | `443/TCP (HTTPS)` |
| **`storage.googleapis.com`** | Download de dependências e assets de modelos | **AGY CLI** | `443/TCP (HTTPS)` |

---

## 3. Matriz de Conectividade Interna Docker (voipia-net: 172.16.8.0/24)

| Serviço / Container | IP Fixo | Porta Interna | Porta no Host | Acesso Externo Direto? |
|---|---|---|---|---|
| **`voipia-caddy`** | `172.16.8.10` | 80, 443 | 80, 443, 8086, 8443 | **Sim** (Ponto de entrada público) |
| **`voipia-postgres`** | `172.16.8.11` | 5432 | `127.0.0.1:5432` | **Não** (Apenas localhost / containers) |
| **`voipia-asterisk`** | `172.16.8.12` | 5060, 16000-16500, 8088, 5038 | 5060/udp+tcp, 16000-16500/udp | **Sim** (Apenas portas SIP e RTP) |
| **`voipia-ai-agent`** | `172.16.8.13` | 9092 (AudioSocket) | — | **Não** (Apenas rede Docker interna) |
| **`voipia-backend`** | `172.16.8.14` | 8080 (REST + STOMP) | — | **Não** (Exclusivo via Caddy) |
| **`voipia-frontend`** | `172.16.8.15` | 80 (Nginx SPA) | — | **Não** (Exclusivo via Caddy) |
| **`voipia-agents-api`**| `172.16.8.16` | 8000 (FastAPI) | — | **Não** (Exclusivo via Caddy) |
| **`voipia-insights`**  | `172.16.8.17` | — | — | **Não** (Processamento em batch no banco) |
| **`voipia-docker-helper`**| `172.16.8.18`| 8001 | — | **Não** (Consumido apenas pelo backend) |
| **`voipia-coturn`**   | `host` | 3478, 5349, 49152-49652 | 3478, 5349, 49152-49652 | **Sim** (NAT Traversal WebRTC) |

---

## 4. Comandos de Firewall Prontos para Execução

### 4.1. Configuração para Linux Ubuntu 22.04/24.04 LTS (UFW)

```bash
# 1. Habilitar o UFW e definir políticas padrão restritivas
sudo ufw default deny incoming
sudo ufw default allow outgoing

# 2. Liberar portas Web (Caddy)
sudo ufw allow 80/tcp comment "VoipIA HTTP"
sudo ufw allow 443/tcp comment "VoipIA HTTPS"
sudo ufw allow 443/udp comment "VoipIA HTTP3/QUIC"

# 3. Liberar telefonia SIP e Mídia RTP (Asterisk)
sudo ufw allow 5060/udp comment "VoipIA SIP UDP"
sudo ufw allow 5060/tcp comment "VoipIA SIP TCP"
sudo ufw allow 16000:16500/udp comment "VoipIA Asterisk RTP Media"

# 4. Liberar STUN/TURN para Softphone WebRTC (Coturn)
sudo ufw allow 3478/tcp comment "VoipIA Coturn STUN/TURN TCP"
sudo ufw allow 3478/udp comment "VoipIA Coturn STUN/TURN UDP"
sudo ufw allow 5349/tcp comment "VoipIA Coturn TURNS TLS TCP"
sudo ufw allow 5349/udp comment "VoipIA Coturn TURNS TLS UDP"
sudo ufw allow 49152:49652/udp comment "VoipIA Coturn Media Relay"

# 5. Liberar SSH administrativo
sudo ufw allow 22/tcp comment "SSH Administrativo"

# 6. Recarregar regras
sudo ufw reload
sudo ufw status verbose
```

---

### 4.2. Configuração para Oracle Linux 9 (Firewalld)

```bash
# 1. Liberar portas Web no zone padrão (public)
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=443/udp

# 2. Liberar telefonia SIP e Faixa RTP (Asterisk)
sudo firewall-cmd --permanent --add-port=5060/udp
sudo firewall-cmd --permanent --add-port=5060/tcp
sudo firewall-cmd --permanent --add-port=16000-16500/udp

# 3. Liberar STUN/TURN para WebRTC (Coturn)
sudo firewall-cmd --permanent --add-port=3478/tcp
sudo firewall-cmd --permanent --add-port=3478/udp
sudo firewall-cmd --permanent --add-port=5349/tcp
sudo firewall-cmd --permanent --add-port=5349/udp
sudo firewall-cmd --permanent --add-port=49152-49652/udp

# 4. Liberar SSH
sudo firewall-cmd --permanent --add-service=ssh

# 5. Recarregar regras do Firewalld
sudo firewall-cmd --reload
sudo firewall-cmd --list-all
```

---

## 5. Validação de Conectividade & Ferramentas de Diagnóstico

* **Testar conectividade com a API do Google Gemini:**
```bash
curl -I https://generativelanguage.googleapis.com
```
* **Testar escuta de portas SIP locais:**
```bash
sudo ss -tulpn | grep 5060
```
* **Monitorar pacotes SIP em tempo real (SNGREP):**
```bash
sudo sngrep -d any port 5060
```
* **Verificar portas RTP abertas:**
```bash
sudo ss -u -a | grep -E "160[0-9]{2}|164[0-9]{2}|16500"
```
