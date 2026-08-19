# 🌐 Matriz de Conectividade de Rede & Segurança — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.2 Enterprise  
> **Ambientes Alvo:** Linux Ubuntu 22.04/24.04 LTS e Oracle Linux 9 (UEK/RHEL)  
> **Padrão de Segurança:** Zero Trust, DevSecOps, Menor Privilégio e Hardening OWASP ASVS L2  
> **Data de Atualização:** 19 de Agosto de 2026  

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

## 2. Conexões de Entrada na VPS (*Inbound / Ingress*)

Portas que o servidor VoipIA precisa escutar para receber tráfego da Internet, operadoras de telefonia e navegadores dos usuários:

| ID | Origem | Destino | Porta Destino | Protocolo | Finalidade / Serviço | Obrigatoriedade / Criptografia |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **VOIP-IN-01** | Navegadores dos Usuários / WAN | Servidor VoipIA (`voipia-caddy`) | **443 / TCP** + **443 / UDP** | HTTPS / HTTP/2 / HTTP/3 (QUIC) | Portal Web, APIs REST, WebSockets STOMP e WebRTC SIP. | **Obrigatório** — TLS 1.3 (Let's Encrypt / Certificado Corporativo) |
| **VOIP-IN-02** | Navegadores dos Usuários / WAN | Servidor VoipIA (`voipia-caddy`) | **80 / TCP** | HTTP | Redirecionamento automático permanente para HTTPS (HTTP 301). | **Obrigatório** — Redirecionamento 301 $\to$ 443 |
| **VOIP-IN-03** | Operadora Telecom / Tronco SIP | PBX Asterisk (`voipia-asterisk`) | **5060 / UDP** e **5060 / TCP** | SIP (`chan_pjsip`) | Sinalização de chamadas telefônicas de entrada e saída. | **Obrigatório** — Autenticação por IP / Digest Auth + Fail2ban |
| **VOIP-IN-04** | Operadora Telecom / Softphones | PBX Asterisk (`voipia-asterisk`) | **16000-16500 / UDP** | RTP / RTCP | Fluxo de mídia de voz em tempo real (áudio bidirecional G.711 / Opus). | **Obrigatório** — Payload RTP de mídia |
| **VOIP-IN-05** | Clientes WebRTC (Navegador) | Coturn Server (`voipia-coturn`) | **3478 / UDP+TCP** e **5349 / UDP+TCP** | STUN / TURN / TURNS | Descoberta de NAT e Relay de mídia WebRTC quando em NAT restrito. | **Obrigatório** — Credenciais temporárias HMAC-SHA1 / TLS |
| **VOIP-IN-06** | Clientes WebRTC (Navegador) | Coturn Server (`voipia-coturn`) | **49152-49652 / UDP** | Media Relay | Faixa de portas de retransmissão de mídia TURN para WebRTC. | **Obrigatório** — Relay dinâmico autenticado |
| **VOIP-IN-07** | IPs dos Administradores | Host Linux | **22 / TCP** | SSH | Acesso administrativo e sustentação de infraestrutura. | **Restrito** — Chaves SSH / Autenticação com senha forte |

> [!NOTE]
> O banco de dados **PostgreSQL** (`5432/TCP`) fica vinculado exclusivamente a `127.0.0.1` (loopback interno do host) e **NÃO deve ser exposto externamente** em nenhuma regra de firewall.

---

## 3. Conexões de Saída da VPS para a Internet (*Outbound / Egress*)

Liberações que o servidor necessita para consumir serviços em nuvem, APIs de IA, pacotes do sistema e certificados:

### 3.1. Inteligência Artificial (Google Gemini & Antigravity) — **Obrigatório**
| ID | Domínio / FQDN | Porta Destino | Protocolo | Finalidade / Serviço |
| :--- | :--- | :--- | :--- | :--- |
| **IA-01** | `generativelanguage.googleapis.com` | **443 / TCP** | HTTPS / gRPC | Motor principal da URA de voz (STT, LLM, TTS), transcrições e análise de sentimento |
| **IA-02** | `content-generativelanguage.googleapis.com` | **443 / TCP** | HTTPS | Streaming de pacotes de áudio e uploads multimodais |
| **IA-03** | `aiplatform.googleapis.com` | **443 / TCP** | HTTPS | Google Vertex AI (quando utilizado projeto GCP corporativo) |
| **IA-04** | `ai.google.dev` | **443 / TCP** | HTTPS | Sincronização e tarifação de modelos de IA |
| **IA-05** | `oauth2.googleapis.com` | **443 / TCP** | HTTPS | Renovação de credenciais e tokens de Service Accounts |
| **IA-06** | `accounts.google.com` | **443 / TCP** | HTTPS | Autenticação OAuth 2.0 |
| **IA-07** | `storage.googleapis.com` | **443 / TCP** | HTTPS | Download de assets e dependências de modelos |
| **IA-08** | `*.antigravity.google` / `antigravity.google` | **443 / TCP** | HTTPS | Plataforma e CLI do Google Antigravity (AGY) |
| **IA-09** | `cloudaicompanion.googleapis.com` | **443 / TCP** | HTTPS | Telemetria e assistência de IA no desenvolvimento |

---

### 3.2. Provedores de IA Secundários — **Opcional**
*(Necessário apenas se habilitar provedores alternativos nas configurações da URA ou Agentes)*

| ID | Domínio / FQDN | Porta Destino | Protocolo | Finalidade / Provedor |
| :--- | :--- | :--- | :--- | :--- |
| **IA-ALT-01** | `api.anthropic.com` | **443 / TCP** | HTTPS | Modelos Claude 3.5 Sonnet / Haiku |
| **IA-ALT-02** | `api.openai.com` | **443 / TCP** | HTTPS | Modelos GPT-4o / GPT-4o-mini / TTS |
| **IA-ALT-03** | `api.elevenlabs.io` | **443 / TCP** | HTTPS | Síntese de voz neural alternativa (TTS) |
| **IA-ALT-04** | `api.x.ai` | **443 / TCP** | HTTPS | Modelos Grok |
| **IA-ALT-05** | `api.perplexity.ai` | **443 / TCP** | HTTPS | Modelos Perplexity |

---

### 3.3. Integrações Corporativas, Certificados & Notificações
| ID | Destino (FQDN ou IP) | Porta Destino | Protocolo | Finalidade / Serviço | Obrigatoriedade |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **EXT-01** | `acme-v02.api.letsencrypt.org` / `*.letsencrypt.org` | **443 / TCP** | HTTPS | Emissão e renovação automática de certificado TLS (Caddy) | **Obrigatório** |
| **EXT-02** | `<sua-empresa>.atlassian.net` | **443 / TCP** | HTTPS | Abertura automática de chamados no Jira Cloud via URA | Opcional (Módulo 1) |
| **EXT-03** | `api.telegram.org` | **443 / TCP** | HTTPS | Notificações de incidentes, alertas de custos e NPS | Opcional |
| **EXT-04** | Servidor Active Directory / LDAP | **636 / TCP** (LDAPS) ou **389 / TCP** | LDAPS / LDAP | Sincronização corporativa de usuários | Opcional (se usar AD) |
| **EXT-05** | Servidor Zabbix Corporativo | **443 / TCP** ou **80 / TCP** | JSON-RPC | Coleta de alertas para disparo de chamadas telefônicas | Opcional (Módulo 3) |

---

### 3.4. Sistema Operacional, Docker e Repositórios de Pacotes
*(Necessário para instalação, atualizações de segurança e deploys)*

| ID | Domínio / FQDN | Porta Destino | Protocolo | Finalidade |
| :--- | :--- | :--- | :--- | :--- |
| **OS-01** | `archive.ubuntu.com`, `security.ubuntu.com` *(Ubuntu)* | **80 / TCP** e **443 / TCP** | HTTP / HTTPS | Atualizações de segurança do sistema operacional Ubuntu |
| **OS-02** | `yum.oracle.com` *(Oracle Linux 9)* | **80 / TCP** e **443 / TCP** | HTTP / HTTPS | Atualizações de pacotes e kernel UEK no Oracle Linux 9 |
| **OS-03** | `registry-1.docker.io`, `auth.docker.io`, `production.cloudflare.docker.com` | **443 / TCP** | HTTPS | Download de imagens de containers Docker |
| **OS-04** | `pypi.org`, `files.pythonhosted.org` | **443 / TCP** | HTTPS | Instalação de pacotes Python |
| **OS-05** | `registry.npmjs.org` | **443 / TCP** | HTTPS | Instalação de dependências do Frontend |
| **OS-06** | `repo.maven.apache.org` | **443 / TCP** | HTTPS | Download de dependências Java Maven do Backend |
| **OS-07** | `github.com`, `api.github.com` | **443 / TCP** / **22 / TCP** | HTTPS / SSH | Sincronização do repositório Git |

---

## 4. Tráfego dos Navegadores dos Usuários (*Client-Side Outbound*)

Para o Softphone WebRTC funcionar nos computadores dos operadores de atendimento:
* **STUN Google:** O navegador do usuário conecta em `stun.l.google.com` na porta `19302/UDP` para descobrir seu próprio IP público em sessões WebRTC.

---

## 5. Matriz de Conectividade Interna Docker (`voipia-net: 172.16.8.0/24`)

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

## 6. Comandos de Firewall Prontos para Execução

### 6.1. Configuração para Linux Ubuntu 22.04 / 24.04 LTS (UFW)

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

# 6. Recarregar regras e validar status
sudo ufw reload
sudo ufw status verbose
```

---

### 6.2. Configuração para Oracle Linux 9 (Firewalld)

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

# 4. Liberar SSH administrativo
sudo firewall-cmd --permanent --add-service=ssh

# 5. Recarregar regras do Firewalld e validar
sudo firewall-cmd --reload
sudo firewall-cmd --list-all
```

---

## 7. Procedimentos de Diagnóstico e Teste de Conectividade

* **Testar saída HTTPS para a API do Google Gemini:**
```bash
curl -I https://generativelanguage.googleapis.com
```
* **Testar resolução DNS dos domínios Google:**
```bash
nslookup generativelanguage.googleapis.com
```
* **Verificar escuta de portas SIP locais no Asterisk:**
```bash
sudo ss -tulpn | grep 5060
```
* **Monitorar pacotes SIP e áudio RTP em tempo real:**
```bash
sudo sngrep -d any port 5060
```
