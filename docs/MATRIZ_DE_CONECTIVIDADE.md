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
        AiAgentContainer["🎙️ AudioSocket AI Server (voipia-ai-agent)\nPorta: 9092 | IP: 172.16.8.13"]
        InsightsContainer["📊 Speech Analytics (voipia-insights)\nIP: 172.16.8.17"]
        AsteriskContainer["☎️ PBX Asterisk 21 (voipia-asterisk)\nPortas: 5060 SIP, 16000-16500 RTP, 8088 WS, 5038 AMI | IP: 172.16.8.12"]
        DockerHelperContainer["🔧 Gateway Docker Helper (voipia-docker-helper)\nPorta: 8090 | IP: 172.16.8.18"]
        DbContainer["🗄️ PostgreSQL 16 + pgvector (voipia-postgres)\nPorta: 5432 | IP: 172.16.8.11"]
    end

    subgraph CorpLAN ["Zona 4: Rede Local Corporativa / Telecom LAN (On-Premises)"]
        ActiveDirectory["🏢 Active Directory / LDAPS\nPorta: 636/TCP"]
    end

    UserBrowser -->|HTTPS :443 / WSS :443| Caddy
    UserBrowser <-->|STUN/TURN :3478 / :49152-49652| CoturnRelay
    TelcoCarrier -->|SIP :5060 UDP/TCP| AsteriskContainer
    TelcoCarrier -->|RTP :16000-16500 UDP| AsteriskContainer

    Caddy -->|HTTP :80| FrontendContainer
    Caddy -->|HTTP/WS :8080| BackendContainer
    Caddy -->|WSS :8088| AsteriskContainer

    AsteriskContainer <-->|TCP :9092 AudioSocket| AiAgentContainer
    AsteriskContainer <-->|TCP :5038 AMI| BackendContainer
    BackendContainer -->|TCP :5432| DbContainer
    BackendContainer -->|REST :8090| DockerHelperContainer
    AiAgentContainer -->|HTTPS :443 Egress| GoogleAI
    BackendContainer -->|HTTPS :443 Egress| CloudServices
    BackendContainer -->|LDAPS :636 Egress| ActiveDirectory
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

### 3.2. Provedores de IA Alternativos e Síntese Vocal (Opcional / Conforme Configuração)
| ID | Domínio / FQDN | Porta Destino | Protocolo | Finalidade / Serviço |
| :--- | :--- | :--- | :--- | :--- |
| **IA-ALT-01** | `api.anthropic.com` | **443 / TCP** | HTTPS | LLM Claude (Anthropic) para URA e auditoria |
| **IA-ALT-02** | `api.openai.com` | **443 / TCP** | HTTPS | Modelos GPT-4o / Whisper (OpenAI) |
| **IA-ALT-03** | `api.elevenlabs.io` | **443 / TCP** | HTTPS | Síntese de Voz Ultra-Realista (ElevenLabs TTS) |
| **IA-ALT-04** | `api.x.ai` | **443 / TCP** | HTTPS | Modelos Grok (xAI) |

### 3.3. Serviços Corporativos, Mensageria & Integrações
| ID | Domínio / FQDN | Porta Destino | Protocolo | Finalidade / Serviço |
| :--- | :--- | :--- | :--- | :--- |
| **CORP-01** | `*.atlassian.net` | **443 / TCP** | HTTPS | Criação automatizada de chamados no Jira Cloud |
| **CORP-02** | `api.telegram.org` | **443 / TCP** | HTTPS | Canal de atendimento chat e envio de alertas via Telegram |
| **CORP-03** | `IP_DO_ACTIVE_DIRECTORY` | **636 / TCP** | LDAPS | Autenticação unificada e sincronização de usuários AD |

### 3.4. Infraestrutura, Certificados & Sistema Operacional
| ID | Domínio / FQDN | Porta Destino | Protocolo | Finalidade / Serviço |
| :--- | :--- | :--- | :--- | :--- |
| **INFRA-01** | `acme-v02.api.letsencrypt.org` | **443 / TCP** | HTTPS | Emissão e renovação automática de certificados TLS (Let's Encrypt) |
| **INFRA-02** | `archive.ubuntu.com` / `security.ubuntu.com` | **80, 443 / TCP** | HTTP/HTTPS | Atualizações de pacotes do sistema (Ubuntu) |
| **INFRA-03** | `yum.oracle.com` | **80, 443 / TCP** | HTTP/HTTPS | Atualizações de pacotes e erratas de segurança (Oracle Linux 9) |
| **INFRA-04** | `download.docker.com` / `registry-1.docker.io` | **443 / TCP** | HTTPS | Download de imagens oficiais Docker |
| **INFRA-05** | `github.com` | **443 / TCP** | HTTPS / Git | Sincronização de código-fonte e esteira de deploy |
| **INFRA-06** | `pool.ntp.org` / `a.st1.ntp.br` | **123 / UDP** | NTP | Sincronização rigorosa de relógio para CDRs e bilhetagem |

---

## 4. Regras Prontas de Firewall (Host)

### 4.1. Configuração para Linux Ubuntu (UFW)
```bash
# 1. Definir políticas padrão
sudo ufw default deny incoming
sudo ufw default allow outgoing

# 2. Liberar SSH, Web e Terminação TLS
sudo ufw allow 22/tcp comment 'SSH Administrativo'
sudo ufw allow 80/tcp comment 'HTTP Redirecionamento Caddy'
sudo ufw allow 443/tcp comment 'HTTPS Caddy TLS'
sudo ufw allow 443/udp comment 'HTTP3 QUIC Caddy'

# 3. Liberar Telefonia SIP e Áudio RTP
sudo ufw allow 5060/udp comment 'Asterisk SIP UDP'
sudo ufw allow 5060/tcp comment 'Asterisk SIP TCP'
sudo ufw allow 16000:16500/udp comment 'Asterisk RTP Midia de Voz'

# 4. Liberar Servidor Coturn WebRTC STUN/TURN
sudo ufw allow 3478/tcp comment 'Coturn STUN/TURN TCP'
sudo ufw allow 3478/udp comment 'Coturn STUN/TURN UDP'
sudo ufw allow 5349/tcp comment 'Coturn TURNS TLS TCP'
sudo ufw allow 5349/udp comment 'Coturn TURNS TLS UDP'
sudo ufw allow 49152:49652/udp comment 'Coturn Relay WebRTC'

# 5. Ativar Firewall
sudo ufw enable
sudo ufw status verbose
```

### 4.2. Configuração para Oracle Linux 9 (Firewalld)
```bash
# 1. Liberar serviços e portas permanentes
sudo firewall-cmd --permanent --add-service=ssh
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=443/udp

# 2. Liberar Telefonia SIP e Mídia RTP
sudo firewall-cmd --permanent --add-port=5060/udp
sudo firewall-cmd --permanent --add-port=5060/tcp
sudo firewall-cmd --permanent --add-port=16000-16500/udp

# 3. Liberar Coturn STUN/TURN WebRTC
sudo firewall-cmd --permanent --add-port=3478/tcp
sudo firewall-cmd --permanent --add-port=3478/udp
sudo firewall-cmd --permanent --add-port=5349/tcp
sudo firewall-cmd --permanent --add-port=5349/udp
sudo firewall-cmd --permanent --add-port=49152-49652/udp

# 4. Recarregar regras e validar
sudo firewall-cmd --reload
sudo firewall-cmd --list-all
```
