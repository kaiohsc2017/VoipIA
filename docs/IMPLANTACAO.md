# 🚀 Guia de Implantação & Runbook Operacional — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.2 Enterprise  
> **Sistemas Homologados:** Linux Ubuntu 22.04 / 24.04 LTS e Oracle Linux 9 (UEK / Red Hat Compatible Kernel)  
> **Data de Atualização:** 20 de Agosto de 2026  

---

## 1. Requisitos Mínimos de Infraestrutura & Hardware

| Recurso | Requisito Mínimo (Homologação / Piloto) | Requisito Recomendado (Produção HA) |
|---|---|---|
| **Processador (vCPU)** | 4 vCPUs (x86_64) | 8+ vCPUs (Intel Xeon / AMD EPYC) |
| **Memória RAM** | 8 GB RAM | 16 GB a 32 GB RAM |
| **Armazenamento (SSD/NVMe)** | 60 GB SSD | 200+ GB NVMe (para retenção de áudios WAV) |
| **Sistema Operacional** | Ubuntu 22.04/24.04 LTS ou Oracle Linux 9 | Ubuntu 24.04 LTS ou Oracle Linux 9 (UEK7) |
| **Largura de Banda de Rede** | 100 Mbps Full Duplex | 1 Gbps Dedicado (Baixa latência / Jitter < 20ms) |
| **Endereço IP & DNS** | IP Público Fixo com DNS FQDN apontando para o host | IP Público Fixo com DNS FQDN (`app.voiphash.com.br`) |

---

## 2. Instalação Rápida Automatizada (Universal)

O instalador universal detecta automaticamente o sistema operacional (Ubuntu / Debian / Oracle Linux / RHEL), provisiona todas as dependências, configura os módulos de kernel, gera segredos criptográficos aleatórios e inicia a stack com auto-recuperação.

```bash
# 1. Clonar o repositório ou acessar a pasta de instalação
git clone https://github.com/kaiohsc2017/VoipIA.git /opt/VoipIA
cd /opt/VoipIA

# 2. Executar o instalador automatizado com privilégios de root
sudo ./install.sh
```

---

## 3. Passo a Passo de Implantação Manual

Caso sua organização exija implantação manual passo a passo:

### 3.1. Preparação do Sistema Operacional

#### No Ubuntu 22.04 / 24.04 LTS:
```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git jq sngrep net-tools fail2ban ca-certificates gnupg lsb-release
```

#### No Oracle Linux 9:
```bash
sudo dnf update -y
sudo dnf install -y curl git jq sngrep net-tools fail2ban ca-certificates gnupg2 tar
```

### 3.2. Instalação do Docker CE & Docker Compose Plugin
```bash
# Instalar Docker Engine oficial
curl -fsSL https://get.docker.com | sudo bash

# Habilitar e iniciar o serviço Docker
sudo systemctl enable --now docker
sudo systemctl is-active docker
```

### 3.3. Configuração de Parâmetros de Kernel (`sysctl`)
Para suportar alto volume de conexões de áudio e sockets de rede:
```bash
sudo tee /etc/sysctl.d/99-voipia.conf << 'EOF'
net.core.somaxconn = 4096
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
fs.file-max = 2097152
vm.max_map_count = 262144
EOF

sudo sysctl --system
```

### 3.4. Configuração das Variáveis de Ambiente (`.env`)
```bash
cd /opt/VoipIA
cp .env.example env/.env
chmod 600 env/.env
```

Edite o arquivo `env/.env` preenchendo as variáveis obrigatórias:
* `POSTGRES_PASSWORD`: Senha forte para o banco de dados.
* `JWT_SECRET`: Chave secreta de alta entropia (mínimo 64 caracteres).
* `GEMINI_API_KEY`: Chave da API do Google Gemini.
* `INTERNAL_API_KEY`: Chave de autenticação entre microservices.
* `COTURN_SECRET`: Chave secreta compartilhada para o servidor TURN.

---

## 4. Inicialização dos Containers

```bash
cd /opt/VoipIA

# 1. Realizar o build e subir todos os containers em segundo plano
docker compose up -d --build

# 2. Verificar o status de integridade de cada serviço
docker compose ps

# 3. Acompanhar os logs de inicialização
docker compose logs -f voipia-backend voipia-asterisk voipia-ai-agent
```

---

## 5. Runbook de Testes de Fumaça (Smoke Tests) Pós-Implantação

Após a inicialização dos containers, execute o checklist de validação:

### 5.1. Teste de Acesso Web & Certificado TLS
* Acesse `https://app.voiphash.com.br` no navegador.
* Verifique se o cadeado de segurança TLS 1.3 está ativo e válido.
* Realize o login com as credenciais padrão de administrador e altere a senha imediatamente.

### 5.2. Teste do Softphone WebRTC
* No canto inferior direito da tela, verifique se o indicador do softphone exibe `Registrado (Ramal 9001)`.
* Realize uma chamada para o ramal `2000` (URA de Teste).

### 5.3. Teste da URA com Google Gemini
* Fale ao microfone durante a chamada no ramal `2000`.
* A URA deve responder com voz humanizada neural e entender a intenção.
* Ao desligar, verifique se o CDR foi gerado na tela do Dashboard em `https://app.voiphash.com.br`.

### 5.4. Teste de Áudio RTP & Pacotes SIP
No terminal do servidor, execute:
```bash
sudo sngrep port 5060
```
Realize uma chamada externa e certifique-se de que os pacotes `INVITE`, `200 OK`, `ACK` e fluxos RTP fluem sem perda de pacotes.

---

## 6. Procedimentos de Backup & Restauração

### 6.1. Backup Completo da Base de Dados
```bash
# Executar dump do banco PostgreSQL
docker compose exec -T voipia-postgres pg_dump -U ${POSTGRES_USER} ${POSTGRES_DB} | gzip > /opt/VoipIA/backups/voipia_db_$(date +%Y%m%d_%H%M%S).sql.gz
```

### 6.2. Backup dos Arquivos de Configuração e Áudios
```bash
tar -czf /opt/VoipIA/backups/voipia_media_$(date +%Y%m%d_%H%M%S).tar.gz /opt/VoipIA/media /opt/VoipIA/env/.env
```

### 6.3. Restauração da Base de Dados
```bash
# Descompactar e restaurar
gunzip -c /opt/VoipIA/backups/voipia_db_XXXXXX.sql.gz | docker compose exec -T voipia-postgres psql -U ${POSTGRES_USER} -d ${POSTGRES_DB}
```

---

## 7. Procedimentos de Rollback & Atualização

```bash
# 1. Fazer backup prévio do banco
./scripts/backup.sh

# 2. Atualizar código do repositório
git pull origin main

# 3. Rebuild e reinício gracioso
docker compose up -d --build

# 4. Em caso de falha (Rollback imediato):
git checkout <commit_anterior>
docker compose up -d --build
```
