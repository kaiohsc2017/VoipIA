# Guia de Implantação e Provisionamento — VoipIA

> **Versão:** v3.2 Enterprise  
> **Sistemas Operacionais Alvo:** Ubuntu 22.04/24.04 LTS e Oracle Linux 9 (UEK / RHEL 9)  
> **Classificação:** Infraestrutura / DevOps / SysAdmin

---

## 1. Requisitos de Hardware & Dimensionamento

| Cenário de Uso | Agentes Simultâneos | Chamadas Simultâneas (URA/Tronco) | vCPU | Memória RAM | Armazenamento (SSD NVMe) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Pequeno Porte** | Até 15 agentes | Até 30 chamadas | 4 vCPUs | 8 GB | 60 GB |
| **Médio Porte** | Até 50 agentes | Até 100 chamadas | 8 vCPUs | 16 GB | 120 GB |
| **Enterprise / Alta Carga** | Até 250 agentes | Até 500 chamadas | 16 vCPUs | 32 GB | 250 GB+ |

---

## 2. Preparação do Sistema Operacional & Ajustes de Kernel (Tuning)

Tanto no Ubuntu quanto no Oracle Linux 9, configure os parâmetros de rede e limites de arquivos para alta performance de pacotes de voz (UDP/RTP):

```bash
# 1. Configuração de parâmetros de kernel no /etc/sysctl.d/99-voipia.conf
sudo tee /etc/sysctl.d/99-voipia.conf << 'EOF'
# Aumento dos buffers de recepção e envio UDP para tráfego RTP
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.core.rmem_default = 262144
net.core.wmem_default = 262144
net.core.netdev_max_backlog = 10000

# Conexões TCP e portas efêmeras
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_max_syn_backlog = 8192
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_tw_reuse = 1

# Limites de memória virtual para Elasticsearch / PostgreSQL
vm.max_map_count = 262144
vm.swappiness = 10
EOF

# Aplica as configurações imediatamente
sudo sysctl --system

# 2. Ajuste de descritores de arquivos no /etc/security/limits.d/99-voipia.conf
sudo tee /etc/security/limits.d/99-voipia.conf << 'EOF'
* soft nofile 65536
* hard nofile 65536
* soft nproc 65536
* hard nproc 65536
root soft nofile 65536
root hard nofile 65536
EOF
```

---

## 3. Passo a Passo de Instalação no **Ubuntu 22.04 / 24.04 LTS**

### 3.1 Atualização de Pacotes & Dependências Base
```bash
sudo apt-get update -y && sudo apt-get upgrade -y
sudo apt-get install -y \
    ca-certificates curl gnupg lsb-release git ufw \
    fail2ban nftables jq openssl htop tar unzip wget
```

### 3.2 Instalação Oficial do Docker & Docker Compose v2
```bash
# Instala chave GPG do repositório Docker
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Adiciona o repositório
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Instala Docker Engine e Compose
sudo apt-get update -y
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Habilita o serviço no boot
sudo systemctl enable --now docker
```

### 3.3 Configuração do Firewall (UFW)
```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp comment "SSH"
sudo ufw allow 80/tcp comment "HTTP Caddy"
sudo ufw allow 443/tcp comment "HTTPS Caddy"
sudo ufw allow 443/udp comment "HTTP/3 QUIC"
sudo ufw allow 5060/udp comment "SIP Trunk UDP"
sudo ufw allow 5061/tcp comment "SIP Trunk TCP"
sudo ufw allow 16000:16500/udp comment "Asterisk RTP"
sudo ufw allow 3478/tcp comment "Coturn STUN/TURN"
sudo ufw allow 3478/udp comment "Coturn STUN/TURN"
sudo ufw allow 49152:49200/udp comment "Coturn Media Relay"
sudo ufw --force enable
```

---

## 4. Passo a Passo de Instalação no **Oracle Linux 9 (UEK / RHEL 9)**

### 4.1 Atualização de Pacotes & Repositórios
```bash
sudo dnf update -y
sudo dnf install -y \
    curl git tar unzip wget jq openssl \
    nftables firewalld dnf-utils util-linux policycoreutils-python-utils
```

### 4.2 Instalação do Docker CE & Compose v2
```bash
# Adiciona repositório oficial Docker CE para RHEL/CentOS
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

# Instala Docker Engine e containerd
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Habilita e inicia o daemon Docker
sudo systemctl enable --now docker
```

### 4.3 Ajuste do SELinux
```bash
# Configura SELinux para permitir que containers bindem portas de rede e acessem volumes
sudo setsebool -P container_manage_cgroup 1
sudo setsebool -P container_use_devices 1

# Caso utilize SELinux Enforcing, certifique-se de marcar os volumes com rótulo de container (:z ou :Z)
```

### 4.4 Configuração do Firewall (Firewalld)
```bash
sudo systemctl enable --now firewalld
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=443/udp
sudo firewall-cmd --permanent --add-port=5060/udp
sudo firewall-cmd --permanent --add-port=5061/tcp
sudo firewall-cmd --permanent --add-port=16000-16500/udp
sudo firewall-cmd --permanent --add-port=3478/tcp
sudo firewall-cmd --permanent --add-port=3478/udp
sudo firewall-cmd --permanent --add-port=49152-49200/udp
sudo firewall-cmd --reload
```

---

## 5. Clonagem do Repositório & Configuração do Ambiente

```bash
# 1. Cria diretório padrão da aplicação
sudo mkdir -p /opt/VoipIA
sudo git clone https://github.com/kaiohsc2017/VoipIA.git /opt/VoipIA
cd /opt/VoipIA

# 2. Configura permissões de diretórios
sudo mkdir -p env backups /srv/docs /run/caddy-admin
sudo chmod 750 env backups

# 3. Criação do arquivo de ambiente .env a partir do template
if [ ! -f env/.env ]; then
    cp .env.example env/.env
    chmod 600 env/.env
    echo "Gere segredos aleatórios para BACKEND_JWT_SECRET, POSTGRES_PASSWORD e TURN_CREDENTIAL"
    sed -i "s/CHANGE_ME_JWT_SECRET/$(openssl rand -hex 32)/g" env/.env
    sed -i "s/CHANGE_ME_DB_PASSWORD/$(openssl rand -hex 16)/g" env/.env
    sed -i "s/CHANGE_ME_TURN_SECRET/$(openssl rand -hex 16)/g" env/.env
fi

# Cria symlink para compatibilidade com ferramentas locais
ln -sf /opt/VoipIA/env/.env /opt/VoipIA/.env
```

---

## 6. Inicialização do Stack & Verificação de Saúde

```bash
cd /opt/VoipIA

# 1. Build e Inicialização dos Containers com Docker Compose
docker compose up -d --build

# 2. Acompanhamento dos Logs de Inicialização do Backend
docker compose logs -f backend

# 3. Verificação de Saúde de Todos os Serviços
docker compose ps
```

### Critérios de Aceitação de Instalação:
- Todos os containers listados com estado `Up (healthy)`.
- Requisição local `curl -k https://127.0.0.1/` respondendo com código HTTP `200`.
- Endpoint de login `POST /api/v1/auth/login` respondendo com JSON de autenticação.
