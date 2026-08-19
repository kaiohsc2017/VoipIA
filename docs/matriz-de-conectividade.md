# Matriz de Conectividade & Portas de Rede — VoipIA

> **Versão:** v3.2 Enterprise  
> **Documento:** Matriz de Liberações de Firewall, Roteamento e Conectividade de Rede  
> **Classificação:** Cybersegurança / Redes / DevOps

---

## 1. Visão Geral de Comunicação e Topologia

O VoipIA opera em um modelo de segurança **Zero Trust** onde os containers comunicam-se através de bridges isoladas (`voipia-net`, `172.16.8.0/24`), e apenas as portas estritamente necessárias são publicadas para o tráfego externo da internet ou troncos de operadoras.

```
       ┌────────────────────────┐
       │   INTERNET / CLIENTES  │
       └───────────┬────────────┘
                   │
    ┌──────────────┴──────────────┐
    │ 80/tcp, 443/tcp, 443/udp    │
    ▼                             ▼
[Caddy Ingress]          [Asterisk PBX] ◄── 5060/5061 (SIP) + 16000-16500 (RTP) ── [Operadora / Trunk]
(TLS Let's Encrypt)               │
    │                             │
    │ (Internal Bridge)           │ (AudioSocket: 9092)
    ▼                             ▼
[Frontend / Backend APIs] ◄── [ai-agent] ─── (Outbound 443) ──► [Google Gemini / LLM APIs]
```

---

## 2. Inbound — Tráfego Público e Entradas no Servidor (VPS)

As regras abaixo devem ser liberadas no firewall de borda da infraestrutura e no firewall do host (UFW / Firewalld):

| Origem | Porta / Protocolo | Destino Interno | Domínio / Host | Obrigatório? | Finalidade |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Qualquer IP (Internet)** | `80/tcp` | `caddy:80` | `voipia.voiphash.com.br` | **Sim** | Redirecionamento HTTP ➔ HTTPS e validação de certificado ACME Let's Encrypt |
| **Qualquer IP (Internet)** | `443/tcp` | `caddy:443` | `voipia.voiphash.com.br` | **Sim** | Tráfego web seguro HTTPS (Acesso ao painel, APIs REST, Call Center e WebSockets) |
| **Qualquer IP (Internet)** | `443/udp` | `caddy:443` | `voipia.voiphash.com.br` | Opcional | Suporte a HTTP/3 (QUIC) para conexões web de alta performance |
| **Operadora Telefônica** | `5060/udp` e `5061/tcp` | `asterisk:5060` | IP do Tronco SIP da Operadora | **Sim** | Sinalização de telefonia SIP (Entrada e saída de ligações reais) |
| **Operadora + Softphones** | `16000-16500/udp` | `asterisk:16000-16500` | IPs de Mídia / Tronco | **Sim** | Fluxo de mídia de áudio bidirecional (RTP) das chamadas telefônicas |
| **Clientes WebRTC Remotos** | `3478/udp` e `3478/tcp` | `coturn:3478` | IP Público da VPS | **Sim** | Servidor STUN/TURN para estabelecimento de conexão WebRTC através de NAT |
| **Clientes WebRTC Remotos** | `49152-49200/udp` | `coturn:49152-49200` | IP Público da VPS | **Sim** | Faixa de portas de relay de mídia do servidor TURN |

---

## 3. Outbound — Conexões de Saída da VPS para Serviços Externos

O servidor VoipIA precisa realizar as seguintes conexões externas para funcionamento dos modelos de inteligência artificial, integrações e segurança:

| Destino / Serviço | Domínio Externo | Porta / Protocolo | Container de Origem | Criticidade | Finalidade |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Google Gemini API** | `generativelanguage.googleapis.com` | `443/tcp` (HTTPS) | `voipia-ai-agent`, `voipia-insights`, `voipia-backend` | **Alta (Essencial)** | Motor principal de IA: Reconhecimento de fala (STT), LLM conversacional e Síntese de voz (TTS) |
| **Google Pricing** | `ai.google.dev` | `443/tcp` (HTTPS) | `voipia-backend` | Baixa | Atualização diária automatizada de tabelas de preços de tokens de IA |
| **OpenAI API** | `api.openai.com` | `443/tcp` (HTTPS) | `voipia-ai-agent`, `voipia-backend` | Média (Fallback) | Provedor alternativo de IA (GPT-4o, Whisper) |
| **Anthropic API** | `api.anthropic.com` | `443/tcp` (HTTPS) | `voipia-ai-agent`, `voipia-backend` | Média (Fallback) | Provedor alternativo de IA (Claude 3.5 Sonnet) |
| **ElevenLabs API** | `api.elevenlabs.io` | `443/tcp` (HTTPS) | `voipia-ai-agent`, `voipia-backend` | Baixa (Opcional) | Provedor de vozes neurais ultrarrealistas para URA |
| **Jira Cloud API** | `<empresa>.atlassian.net` | `443/tcp` (HTTPS) | `voipia-backend` | Média | Criação automática de chamados de suporte a partir da URA |
| **Active Directory / LDAP** | `IP ou FQDN do Domain Controller` | `636/tcp` (LDAPS) ou `389/tcp` | `voipia-backend` | Opcional | Autenticação corporativa de usuários |
| **Telegram Bot API** | `api.telegram.org` | `443/tcp` (HTTPS) | `voipia-backend` | Baixa | Envio de alertas de teto de gastos de IA e notificações operacionais |
| **STUN Google** | `stun.l.google.com` | `19302/udp` | Navegador do Cliente / Softphone | **Alta** | Descoberta de IP público para negociação ICE/WebRTC |
| **Let's Encrypt ACME** | `acme-v02.api.letsencrypt.org` | `443/tcp` (HTTPS) | `caddy` | **Alta** | Emissão e renovação automática de certificados TLS/SSL |

---

## 4. Comunicação Interna (Rede Docker `voipia-net`: `172.16.8.0/24`)

As portas abaixo trafegam **exclusivamente** dentro da rede interna Docker entre containers e nunca devem ser expostas diretamente na internet:

| Origem | Destino | Porta / Protocolo | Finalidade |
| :--- | :--- | :--- | :--- |
| `caddy` | `voipia-frontend` | `80/tcp` (HTTP) | Entrega de arquivos estáticos das SPAs |
| `caddy` | `voipia-backend` | `8080/tcp` (HTTP/WS) | Requisições REST e WebSocket STOMP |
| `caddy` | `voipia-asterisk` | `8088/tcp` (HTTP/WS) | Túnel WebSocket do softphone WebRTC |
| `voipia-asterisk` | `voipia-ai-agent` | `9092/tcp` (AudioSocket) | Streaming bidirecional de áudio PCM da ligação |
| `voipia-ai-agent` | `voipia-backend` | `8080/tcp` (HTTP REST) | Registro de chamadas finalizadas e CDRs |
| `voipia-backend` | `voipia-postgres` | `5432/tcp` (PostgreSQL) | Persistência relacional e transações |
| `voipia-backend` | `voipia-asterisk` | `5038/tcp` (AMI) | Controle de chamadas, discagem e supervisão em tempo real |
| `voipia-backend` | `voipia-docker-helper` | `8090/tcp` (HTTP REST) | Gestão controlada do ciclo de vida dos containers |

---

## 5. Regras de Firewall para Aplicação Rápida

### 5.1 No Ubuntu (UFW)
```bash
# Habilita UFW com política padrão de negação de entrada
sudo ufw default deny incoming
sudo ufw default allow outgoing

# Acesso administrativo SSH (ajuste a porta conforme seu ambiente)
sudo ufw allow 22/tcp comment "SSH Administrativo"

# Web e TLS (Caddy)
sudo ufw allow 80/tcp comment "HTTP / ACME Challenge"
sudo ufw allow 443/tcp comment "HTTPS / WSS"
sudo ufw allow 443/udp comment "HTTP3 / QUIC"

# Telefonia SIP e Áudio RTP (Asterisk)
sudo ufw allow 5060/udp comment "Sinalizacao SIP UDP"
sudo ufw allow 5061/tcp comment "Sinalizacao SIP TCP"
sudo ufw allow 16000:16500/udp comment "Audio RTP Asterisk"

# WebRTC STUN/TURN (Coturn)
sudo ufw allow 3478/tcp comment "STUN/TURN TCP"
sudo ufw allow 3478/udp comment "STUN/TURN UDP"
sudo ufw allow 49152:49200/udp comment "TURN Media Relay"

# Ativa o firewall
sudo ufw reload
```

### 5.2 No Oracle Linux 9 / RHEL (Firewalld)
```bash
# Web e TLS
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=443/udp

# Telefonia SIP e Áudio RTP
sudo firewall-cmd --permanent --add-port=5060/udp
sudo firewall-cmd --permanent --add-port=5061/tcp
sudo firewall-cmd --permanent --add-port=16000-16500/udp

# WebRTC STUN/TURN
sudo firewall-cmd --permanent --add-port=3478/tcp
sudo firewall-cmd --permanent --add-port=3478/udp
sudo firewall-cmd --permanent --add-port=49152-49200/udp

# Recarrega as regras
sudo firewall-cmd --reload
```
