#!/usr/bin/env bash
# =============================================================================
# install.sh — Instalação Automatizada do AsteriskIA
# =============================================================================
# Compatível com:
#   • Ubuntu 22.04 LTS / 24.04 LTS
#   • Oracle Linux 9 / RHEL 9 / AlmaLinux 9 / Rocky Linux 9
#
# Uso:
#   curl -fsSL https://raw.githubusercontent.com/kaiohsc2017/AsteriskIA/main/install.sh | sudo bash
#   — ou —
#   sudo bash install.sh
#
# O script é IDEMPOTENTE: pode ser executado novamente sem efeitos colaterais.
# Em caso de falha, reexecute — ele continua de onde parou.
# =============================================================================

set -euo pipefail

# ─── Cores ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ─── Funções utilitárias ──────────────────────────────────────────────────────
log_header() { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════════════════${NC}"; echo -e "${BOLD}${BLUE}  $1${NC}"; echo -e "${BOLD}${BLUE}══════════════════════════════════════════════════${NC}"; }
log_step()   { echo -e "\n${CYAN}▶ $1${NC}"; }
log_ok()     { echo -e "${GREEN}  ✔ $1${NC}"; }
log_warn()   { echo -e "${YELLOW}  ⚠ $1${NC}"; }
log_error()  { echo -e "${RED}  ✖ $1${NC}" >&2; }
log_info()   { echo -e "    $1"; }

die() { log_error "$1"; exit 1; }

check_root() {
    [[ $EUID -eq 0 ]] || die "Execute como root: sudo bash $0"
}

detect_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        OS_ID="${ID}"
        OS_VERSION="${VERSION_ID%%.*}"
        OS_NAME="${PRETTY_NAME}"
    else
        die "Sistema operacional não reconhecido (/etc/os-release ausente)"
    fi

    case "$OS_ID" in
        ubuntu)
            PKG_MANAGER="apt"
            [[ "$OS_VERSION" -ge 22 ]] || die "Requer Ubuntu 22.04 ou superior (detectado: $OS_NAME)"
            ;;
        ol|rhel|almalinux|rocky)
            PKG_MANAGER="dnf"
            [[ "$OS_VERSION" -ge 9 ]] || die "Requer Oracle/RHEL/Alma/Rocky Linux 9+ (detectado: $OS_NAME)"
            ;;
        *)
            die "Sistema não suportado: $OS_NAME. Suporte: Ubuntu 22+, Oracle/RHEL/Alma/Rocky 9+"
            ;;
    esac

    log_ok "Sistema detectado: $OS_NAME (gerenciador: $PKG_MANAGER)"
}

# ─── Configurações globais ────────────────────────────────────────────────────
INSTALL_DIR="/opt/AsteriskIA"
REPO_URL="https://github.com/kaiohsc2017/AsteriskIA.git"
ENV_FILE="$INSTALL_DIR/env/.env"
LOG_FILE="/var/log/asteriskia-install.log"
DOCKER_COMPOSE_VERSION="2.27.0"
MIN_RAM_MB=2048
MIN_DISK_GB=20

# ─── Registro de log ──────────────────────────────────────────────────────────
exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Instalação iniciada em $(date '+%Y-%m-%d %H:%M:%S') ==="

# =============================================================================
# PASSO 0 — Verificações pré-requisito
# =============================================================================
check_prerequisites() {
    log_header "PASSO 0 — Verificando pré-requisitos"

    # RAM
    local ram_mb
    ram_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
    if [[ $ram_mb -lt $MIN_RAM_MB ]]; then
        log_warn "RAM disponível: ${ram_mb}MB (recomendado: ${MIN_RAM_MB}MB+)"
    else
        log_ok "RAM: ${ram_mb}MB"
    fi

    # Disco
    local disk_gb
    disk_gb=$(df -BG / | awk 'NR==2 {print int($4)}')
    if [[ $disk_gb -lt $MIN_DISK_GB ]]; then
        die "Espaço em disco insuficiente: ${disk_gb}GB livres (mínimo: ${MIN_DISK_GB}GB)"
    fi
    log_ok "Disco livre: ${disk_gb}GB"

    # Portas necessárias
    log_step "Verificando portas necessárias..."
    local ports_conflict=()
    for port in 80 443 5060 8088; do
        if ss -tlnp 2>/dev/null | grep -q ":$port " || \
           ss -ulnp 2>/dev/null | grep -q ":$port "; then
            ports_conflict+=("$port")
        fi
    done
    if [[ ${#ports_conflict[@]} -gt 0 ]]; then
        log_warn "Portas em uso (verificar conflito): ${ports_conflict[*]}"
        log_info "O Caddy precisa das portas 80 e 443 livres para HTTPS."
    else
        log_ok "Portas 80, 443, 5060, 8088 disponíveis"
    fi

    # Conectividade
    log_step "Verificando conectividade com a internet..."
    if ! curl -fsS --max-time 10 https://github.com > /dev/null 2>&1; then
        die "Sem acesso à internet. Verifique a conectividade do servidor."
    fi
    log_ok "Conectividade OK"

    # IP público
    PUBLIC_IP=$(curl -fsS --max-time 10 https://ifconfig.me 2>/dev/null || echo "")
    if [[ -n "$PUBLIC_IP" ]]; then
        log_ok "IP público detectado: $PUBLIC_IP"
    else
        log_warn "Não foi possível detectar o IP público automaticamente"
        PUBLIC_IP=""
    fi
}

# =============================================================================
# PASSO 1 — Dependências do sistema
# =============================================================================
install_system_deps() {
    log_header "PASSO 1 — Instalando dependências do sistema"

    if [[ "$PKG_MANAGER" == "apt" ]]; then
        install_system_deps_ubuntu
    else
        install_system_deps_rhel
    fi
}

install_system_deps_ubuntu() {
    log_step "Atualizando lista de pacotes..."
    apt-get update -qq

    log_step "Instalando pacotes essenciais..."
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        curl wget git \
        ca-certificates gnupg lsb-release \
        openssl uuid-runtime \
        gettext-base \
        net-tools iproute2 \
        htop vim nano \
        unzip jq \
        iptables iptables-persistent \
        fail2ban \
        logrotate \
        python3 python3-pip \
        2>/dev/null
    log_ok "Pacotes essenciais instalados"
}

install_system_deps_rhel() {
    log_step "Configurando repositórios EPEL e habilitando módulos..."

    # EPEL
    if ! rpm -q epel-release &>/dev/null; then
        dnf install -y epel-release 2>/dev/null || \
        dnf install -y https://dl.fedoraproject.org/pub/epel/epel-release-latest-9.noarch.rpm 2>/dev/null
    fi

    # Para Oracle Linux: habilitar ol9_codeready_builder
    if [[ "$OS_ID" == "ol" ]]; then
        dnf config-manager --set-enabled ol9_codeready_builder 2>/dev/null || true
    fi

    # Para RHEL: subscription-manager
    if [[ "$OS_ID" == "rhel" ]]; then
        subscription-manager repos --enable codeready-builder-for-rhel-9-x86_64-rpms 2>/dev/null || true
    fi

    # Para AlmaLinux/Rocky: crb
    if [[ "$OS_ID" =~ ^(almalinux|rocky)$ ]]; then
        dnf config-manager --set-enabled crb 2>/dev/null || true
    fi

    log_step "Instalando pacotes essenciais..."
    dnf install -y \
        curl wget git \
        ca-certificates gnupg \
        openssl uuid \
        gettext \
        net-tools iproute \
        htop vim nano \
        unzip jq \
        iptables iptables-services \
        fail2ban \
        logrotate \
        python3 python3-pip \
        policycoreutils-python-utils \
        2>/dev/null
    log_ok "Pacotes essenciais instalados"

    # SELinux — modo permissivo para não bloquear Docker
    log_step "Configurando SELinux (modo permissivo)..."
    if sestatus 2>/dev/null | grep -q "enabled"; then
        setenforce 0 2>/dev/null || true
        sed -i 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/selinux/config 2>/dev/null || true
        log_ok "SELinux definido como permissivo"
    else
        log_info "SELinux não está ativo"
    fi

    # firewalld — abre portas necessárias
    if systemctl is-active --quiet firewalld 2>/dev/null; then
        log_step "Configurando firewalld..."
        firewall-cmd --permanent --add-port=80/tcp
        firewall-cmd --permanent --add-port=443/tcp
        firewall-cmd --permanent --add-port=443/udp
        firewall-cmd --permanent --add-port=5060/tcp
        firewall-cmd --permanent --add-port=5060/udp
        firewall-cmd --permanent --add-port=8088/tcp
        firewall-cmd --permanent --add-port=5038/tcp
        firewall-cmd --permanent --add-port=10000-10100/udp
        firewall-cmd --reload
        log_ok "Portas abertas no firewalld"
    fi
}

# =============================================================================
# PASSO 2 — Docker Engine + Docker Compose
# =============================================================================
install_docker() {
    log_header "PASSO 2 — Instalando Docker Engine"

    if command -v docker &>/dev/null && docker compose version &>/dev/null; then
        local docker_ver
        docker_ver=$(docker --version | awk '{print $3}' | tr -d ',')
        log_ok "Docker já instalado: $docker_ver — pulando"
        return
    fi

    if [[ "$PKG_MANAGER" == "apt" ]]; then
        install_docker_ubuntu
    else
        install_docker_rhel
    fi

    # Habilita e inicia Docker
    systemctl enable docker
    systemctl start docker
    log_ok "Docker iniciado e habilitado no boot"

    # Adiciona usuário atual ao grupo docker (se não for root)
    if [[ -n "${SUDO_USER:-}" ]]; then
        usermod -aG docker "$SUDO_USER"
        log_ok "Usuário $SUDO_USER adicionado ao grupo docker"
    fi

    # Verifica instalação
    docker run --rm hello-world > /dev/null 2>&1 && log_ok "Docker funcionando corretamente" \
        || log_warn "Teste hello-world falhou — verifique a instalação do Docker"
}

install_docker_ubuntu() {
    log_step "Adicionando repositório oficial Docker para Ubuntu..."

    # Remove versões antigas
    apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true

    # Chave GPG
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg

    # Repositório
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
        | tee /etc/apt/sources.list.d/docker.list > /dev/null

    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    log_ok "Docker CE instalado via repositório oficial"
}

install_docker_rhel() {
    log_step "Adicionando repositório oficial Docker para RHEL/Oracle Linux..."

    # Remove versões antigas
    dnf remove -y docker docker-client docker-client-latest docker-common \
        docker-latest docker-latest-logrotate docker-logrotate docker-engine \
        podman runc 2>/dev/null || true

    # Repositório Docker CE
    dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo 2>/dev/null || \
        curl -fsSL https://download.docker.com/linux/rhel/docker-ce.repo \
            -o /etc/yum.repos.d/docker-ce.repo

    dnf install -y \
        docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin
    log_ok "Docker CE instalado via repositório oficial"
}

# =============================================================================
# PASSO 3 — Caddy (reverse proxy HTTPS)
# =============================================================================
install_caddy() {
    log_header "PASSO 3 — Instalando Caddy (reverse proxy HTTPS)"

    if command -v caddy &>/dev/null || docker ps --format '{{.Names}}' 2>/dev/null | grep -q caddy; then
        log_ok "Caddy já instalado — verificando container..."
        _ensure_caddy_container
        return
    fi

    if [[ "$PKG_MANAGER" == "apt" ]]; then
        _install_caddy_ubuntu
    else
        _install_caddy_rhel
    fi
}

_install_caddy_ubuntu() {
    log_step "Instalando Caddy via repositório oficial (Ubuntu)..."
    apt-get install -y debian-keyring debian-archive-keyring apt-transport-https 2>/dev/null || true
    curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key \
        | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt \
        | tee /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq
    apt-get install -y caddy
    log_ok "Caddy instalado"
}

_install_caddy_rhel() {
    log_step "Instalando Caddy via repositório oficial (RHEL/OL)..."
    dnf install -y 'dnf-command(copr)' 2>/dev/null || true
    dnf copr enable @caddy/caddy -y 2>/dev/null || \
        curl -fsSL https://copr.fedorainfracloud.org/coprs/g/caddy/caddy/repo/epel-9/group_caddy-caddy-epel-9.repo \
            -o /etc/yum.repos.d/caddy.repo
    dnf install -y caddy
    log_ok "Caddy instalado"
}

_ensure_caddy_container() {
    # Se Caddy está rodando como container Docker standalone
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "caddy"; then
        log_ok "Container Caddy em execução — OK"
        return
    fi

    # Sobe Caddy como container standalone (mesma abordagem do projeto)
    log_step "Iniciando Caddy como container Docker standalone..."
    docker run -d \
        --name caddy-proxy \
        --restart unless-stopped \
        -p 80:80 -p 443:443 -p 443:443/udp -p 2019:2019 \
        -v "$INSTALL_DIR/Caddyfile:/etc/caddy/Caddyfile:ro" \
        -v caddy_data:/data \
        -v caddy_config:/config \
        caddy:2-alpine 2>/dev/null || log_warn "Caddy container já existe"

    log_ok "Caddy container iniciado"
}

# =============================================================================
# PASSO 4 — Clonar repositório
# =============================================================================
clone_repository() {
    log_header "PASSO 4 — Clonando repositório AsteriskIA"

    if [[ -d "$INSTALL_DIR/.git" ]]; then
        log_step "Repositório já existe — atualizando..."
        cd "$INSTALL_DIR"
        git pull origin main
        log_ok "Repositório atualizado para o commit $(git rev-parse --short HEAD)"
        return
    fi

    log_step "Clonando em $INSTALL_DIR..."
    git clone "$REPO_URL" "$INSTALL_DIR"
    log_ok "Repositório clonado — commit $(cd $INSTALL_DIR && git rev-parse --short HEAD)"
}

# =============================================================================
# PASSO 5 — Configuração do ambiente (.env)
# =============================================================================
configure_environment() {
    log_header "PASSO 5 — Configurando variáveis de ambiente"

    mkdir -p "$INSTALL_DIR/env"

    if [[ -f "$ENV_FILE" ]]; then
        log_warn ".env já existe em $ENV_FILE"
        log_info "Preservando configurações existentes."
        _merge_new_env_vars
        return
    fi

    log_step "Gerando senhas e chaves seguras..."

    # Gera valores aleatórios seguros
    local pg_pass jwt_secret internal_key ami_pass grafana_pass admin_pass
    pg_pass=$(openssl rand -hex 20)
    jwt_secret=$(openssl rand -hex 32)
    internal_key=$(openssl rand -hex 32)
    ami_pass=$(openssl rand -hex 12)
    grafana_pass=$(openssl rand -hex 12)
    admin_pass=$(openssl rand -hex 12)

    # Detecção do IP público (já feita no passo 0)
    [[ -z "${PUBLIC_IP:-}" ]] && PUBLIC_IP=$(curl -fsS --max-time 10 https://ifconfig.me 2>/dev/null || echo "SEU_IP_PUBLICO")

    log_step "Criando $ENV_FILE..."
    cat > "$ENV_FILE" << ENVEOF
# =============================================================================
# AsteriskIA — Variáveis de Ambiente
# Gerado automaticamente pelo install.sh em $(date '+%Y-%m-%d %H:%M:%S')
# =============================================================================

# --- PostgreSQL ---
POSTGRES_DB=asteriskia
POSTGRES_USER=asteriskia
POSTGRES_PASSWORD=${pg_pass}
POSTGRES_PORT=5432

# --- Asterisk ---
AST_AMI_HOST=asterisk
AST_AMI_PORT=5038
AST_AMI_USER=asteriskia
AST_AMI_PASSWORD=${ami_pass}
AST_OUTBOUND_TRUNK=tronco-sip
AST_OUTBOUND_CONTEXT=discagem-sainte
SIP_DOMAIN=voiphash.com.br

# --- Tronco SIP — Peer IP-based (sem usuário/senha) ---
SIP_TRUNK_HOST=186.233.141.64
SIP_TRUNK_FROM_DOMAIN=voiphash.com.br

# IP público do VPS (detectado automaticamente)
SIP_PUBLIC_IP=${PUBLIC_IP}

# --- Audiosocket ---
AUDIOSOCKET_HOST=ai-agent
AUDIOSOCKET_PORT=9092

# --- Backend Spring Boot ---
BACKEND_PORT=8080
BACKEND_JWT_SECRET=${jwt_secret}
BACKEND_ALLOWED_ORIGINS=https://app.voiphash.com.br,http://localhost
ADMIN_USERNAME=admin
ADMIN_PASSWORD=${admin_pass}
INTERNAL_API_KEY=${internal_key}
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/asteriskia
SPRING_DATASOURCE_USERNAME=asteriskia
SPRING_DATASOURCE_PASSWORD=${pg_pass}

# --- Frontend React ---
VITE_API_URL=https://app.voiphash.com.br/api/v1
VITE_ASTERISK_WS=wss://app.voiphash.com.br/asterisk-ws
VITE_SIP_URI=sip:9001@app.voiphash.com.br
VITE_SIP_PASSWORD=webrtc9001pass

# --- Google Gemini API ---
# ⚠️  PREENCHER OBRIGATORIAMENTE antes de usar o sistema
GEMINI_API_KEY=
GEMINI_MODEL_STT=gemini-2.0-flash
GEMINI_MODEL_LLM=gemini-2.0-flash
GEMINI_MODEL_TTS=gemini-2.5-flash-preview-tts

# --- Jira Cloud ---
# Preencher via painel: Settings → Jira
JIRA_BASE_URL=
JIRA_USER_EMAIL=
JIRA_API_TOKEN=
JIRA_PROJECT_KEY=

# --- Zabbix ---
# Preencher via painel: Settings → Zabbix
ZABBIX_API_URL=
ZABBIX_USER=
ZABBIX_PASSWORD=
ZABBIX_MIN_SEVERITY=4
ZABBIX_POLL_INTERVAL_MINUTES=5

# --- Telegram ---
# Preencher via painel: Settings → Telegram
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=

# --- Monitoração ---
PROMETHEUS_PORT=9090
GRAFANA_PORT=3000
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=${grafana_pass}

# --- Armazenamento de Áudio ---
AUDIO_STORAGE_PATH=/var/asteriskia/recordings
ENVEOF

    chmod 600 "$ENV_FILE"
    log_ok ".env criado com senhas geradas automaticamente"
    log_warn "IMPORTANTE: Preencha GEMINI_API_KEY em $ENV_FILE antes de iniciar"
}

_merge_new_env_vars() {
    # Adiciona variáveis novas que podem não existir no .env atual
    local -A new_vars=(
        ["SIP_PUBLIC_IP"]="${PUBLIC_IP:-SEU_IP_PUBLICO}"
        ["SIP_DOMAIN"]="voiphash.com.br"
        ["SPRING_DATASOURCE_URL"]="jdbc:postgresql://postgres:5432/asteriskia"
    )

    for key in "${!new_vars[@]}"; do
        if ! grep -q "^${key}=" "$ENV_FILE"; then
            echo "${key}=${new_vars[$key]}" >> "$ENV_FILE"
            log_ok "Variável adicionada: $key"
        fi
    done
}

# =============================================================================
# PASSO 6 — Ajustes de kernel e sistema (performance + segurança)
# =============================================================================
configure_kernel() {
    log_header "PASSO 6 — Ajustes de kernel para VoIP e Docker"

    log_step "Configurando parâmetros sysctl..."
    cat > /etc/sysctl.d/99-asteriskia.conf << 'SYSCTL'
# AsteriskIA — Otimizações de kernel para VoIP e Docker

# Buffer de rede (RTP/SIP)
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.core.rmem_default = 262144
net.core.wmem_default = 262144
net.ipv4.udp_rmem_min = 8192
net.ipv4.udp_wmem_min = 8192

# Conexões simultâneas
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535

# TIME_WAIT (evita esgotamento de portas em chamadas intensas)
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_tw_reuse = 1

# IP forwarding (necessário para Docker networking)
net.ipv4.ip_forward = 1

# Limites de arquivo (Asterisk abre muitos fds em produção)
fs.file-max = 1048576
SYSCTL

    sysctl -p /etc/sysctl.d/99-asteriskia.conf > /dev/null 2>&1
    log_ok "Parâmetros sysctl aplicados"

    # Limites de arquivo para o processo Asterisk
    log_step "Configurando limites de arquivo (ulimits)..."
    cat > /etc/security/limits.d/99-asteriskia.conf << 'LIMITS'
# AsteriskIA — Limites de recursos
asterisk  soft  nofile  65536
asterisk  hard  nofile  65536
root      soft  nofile  65536
root      hard  nofile  65536
LIMITS
    log_ok "ulimits configurados"
}

# =============================================================================
# PASSO 7 — Build e inicialização dos containers
# =============================================================================
start_containers() {
    log_header "PASSO 7 — Build e inicialização dos containers Docker"

    cd "$INSTALL_DIR"

    # Garante que a rede existe antes do Caddy tentar se conectar
    log_step "Criando rede Docker asteriskia-net..."
    docker network create asteriskia-net 2>/dev/null || log_info "Rede asteriskia-net já existe"

    # Build do Asterisk (mais demorado — ~10 min na primeira vez)
    log_step "Build da imagem Asterisk 21 LTS (pode levar 10-15 minutos)..."
    log_info "O Asterisk é compilado do código-fonte com suporte a:"
    log_info "  • SRTP (criptografia de mídia)"
    log_info "  • AudioSocket (integração com AI agent)"
    log_info "  • PJSIP (WebRTC + SIP)"
    log_info "  • ICE/STUN (WebRTC via NAT)"
    docker compose build asterisk
    log_ok "Imagem Asterisk construída"

    # Build demais serviços
    log_step "Build dos demais serviços (backend, frontend, ai-agent, scheduler)..."
    docker compose build --parallel backend frontend ai-agent scheduler
    log_ok "Todas as imagens construídas"

    # Sobe infraestrutura base primeiro (banco de dados)
    log_step "Iniciando banco de dados PostgreSQL..."
    docker compose up -d postgres
    log_info "Aguardando PostgreSQL ficar pronto..."
    local retries=0
    until docker exec asteriskia-postgres pg_isready -U asteriskia -q 2>/dev/null; do
        sleep 3
        retries=$((retries + 1))
        [[ $retries -gt 20 ]] && die "PostgreSQL não iniciou após 60s. Verifique: docker logs asteriskia-postgres"
    done
    log_ok "PostgreSQL pronto"

    # Sobe agents-postgres também
    docker compose up -d agents-postgres
    sleep 5

    # Sobe todos os demais serviços
    log_step "Iniciando todos os serviços..."
    docker compose up -d
    log_ok "Todos os containers iniciados"

    # Aguarda health checks
    log_step "Aguardando health checks (30s)..."
    sleep 30

    # Conecta Caddy à rede (se já existir como standalone)
    if docker ps --format '{{.Names}}' | grep -q "caddy"; then
        log_step "Conectando Caddy à rede asteriskia-net..."
        docker network connect asteriskia-net caddy-proxy 2>/dev/null \
            && log_ok "Caddy conectado à rede asteriskia-net" \
            || log_info "Caddy já estava conectado"
    fi
}

# =============================================================================
# PASSO 8 — Configurar Caddy
# =============================================================================
configure_caddy() {
    log_header "PASSO 8 — Configurando Caddy (HTTPS)"

    # Se Caddy está rodando como serviço systemd
    if systemctl is-active --quiet caddy 2>/dev/null; then
        log_step "Caddy rodando como serviço systemd — copiando Caddyfile..."
        cp "$INSTALL_DIR/Caddyfile" /etc/caddy/Caddyfile
        systemctl reload caddy
        log_ok "Caddyfile aplicado via systemd"
        return
    fi

    # Se Caddy está rodando como container
    if docker ps --format '{{.Names}}' | grep -q "caddy"; then
        log_step "Recarregando Caddyfile no container Caddy via Admin API..."
        local caddy_reload_ok=false
        for i in 1 2 3; do
            if curl -sf -X POST "http://localhost:2019/load" \
                -H "Content-Type: text/caddyfile" \
                --data-binary @"$INSTALL_DIR/Caddyfile" > /dev/null 2>&1; then
                caddy_reload_ok=true
                break
            fi
            sleep 5
        done

        if $caddy_reload_ok; then
            log_ok "Caddyfile recarregado com sucesso"
        else
            log_warn "Admin API do Caddy não respondeu. Reiniciando container..."
            docker restart caddy-proxy 2>/dev/null || true
        fi
        return
    fi

    # Caddy não está rodando — inicia como container
    log_step "Iniciando Caddy como container Docker..."
    _ensure_caddy_container
}

# =============================================================================
# PASSO 9 — Configurar fail2ban
# =============================================================================
configure_fail2ban() {
    log_header "PASSO 9 — Configurando fail2ban (proteção SIP)"

    if ! command -v fail2ban-server &>/dev/null; then
        log_warn "fail2ban não instalado — pulando"
        return
    fi

    # Para fail2ban gerenciado pelo container security do compose,
    # apenas garantimos que o serviço do host não conflita
    if docker ps --format '{{.Names}}' | grep -q "asteriskia-security"; then
        log_info "fail2ban gerenciado pelo container asteriskia-security"
        # Para o serviço do host para não conflitar
        systemctl stop fail2ban 2>/dev/null || true
        systemctl disable fail2ban 2>/dev/null || true
        log_ok "fail2ban do host desativado (gerenciado pelo container)"
    else
        log_step "Ativando fail2ban do host..."
        systemctl enable fail2ban
        systemctl start fail2ban
        log_ok "fail2ban ativo"
    fi
}

# =============================================================================
# PASSO 10 — Script de atualização (deploy.sh)
# =============================================================================
install_update_script() {
    log_header "PASSO 10 — Instalando script de atualização"

    cat > /usr/local/bin/asteriskia-update << 'UPDATESCRIPT'
#!/usr/bin/env bash
# asteriskia-update — Atualiza o AsteriskIA para o último commit do main
set -euo pipefail

INSTALL_DIR="/opt/AsteriskIA"
echo "=== AsteriskIA Update — $(date '+%Y-%m-%d %H:%M:%S') ==="

cd "$INSTALL_DIR"

echo "[1/4] Atualizando código..."
git pull origin main

echo "[2/4] Rebuild dos serviços atualizados..."
docker compose build --parallel

echo "[3/4] Reiniciando containers..."
docker compose up -d

echo "[4/4] Recarregando Caddyfile..."
curl -sf -X POST "http://localhost:2019/load" \
    -H "Content-Type: text/caddyfile" \
    --data-binary @Caddyfile 2>/dev/null \
    && echo "  Caddyfile recarregado" \
    || echo "  Aviso: Admin API Caddy não respondeu"

echo ""
echo "=== Atualização concluída! Acesse: https://app.voiphash.com.br ==="
UPDATESCRIPT

    chmod +x /usr/local/bin/asteriskia-update
    log_ok "Script de atualização instalado: asteriskia-update"
}

# =============================================================================
# PASSO 11 — Logrotate para logs do Asterisk
# =============================================================================
configure_logrotate() {
    log_header "PASSO 11 — Configurando rotação de logs"

    cat > /etc/logrotate.d/asteriskia << 'LOGROTATE'
/var/log/asteriskia-install.log {
    weekly
    rotate 4
    compress
    missingok
    notifempty
}
LOGROTATE

    log_ok "Logrotate configurado"
}

# =============================================================================
# PASSO 12 — Verificação final do sistema
# =============================================================================
verify_installation() {
    log_header "PASSO 12 — Verificação final"

    local errors=0

    log_step "Verificando containers..."
    local expected_containers=(
        "asteriskia-postgres"
        "asteriskia-asterisk"
        "asteriskia-ai-agent"
        "asteriskia-scheduler"
        "asteriskia-backend"
        "asteriskia-frontend"
        "asteriskia-prometheus"
        "asteriskia-security"
        "asteriskia-grafana"
        "asteriskia-agents-db"
        "asteriskia-agents-api"
        "asteriskia-agents-ui"
    )

    for container in "${expected_containers[@]}"; do
        local status
        status=$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null || echo "não encontrado")
        if [[ "$status" == "running" ]]; then
            log_ok "$container: running"
        else
            log_warn "$container: $status"
            errors=$((errors + 1))
        fi
    done

    log_step "Verificando módulos do Asterisk..."
    local asterisk_modules=(
        "app_audiosocket"
        "res_audiosocket"
        "res_srtp"
        "res_pjsip"
        "chan_pjsip"
    )
    for mod in "${asterisk_modules[@]}"; do
        if docker exec asteriskia-asterisk asterisk -rx "module show like $mod" 2>/dev/null \
            | grep -q "Running"; then
            log_ok "Asterisk: $mod Running"
        else
            log_warn "Asterisk: $mod não está Running"
            errors=$((errors + 1))
        fi
    done

    log_step "Verificando endpoints HTTP..."
    # Backend health
    if curl -sf --max-time 10 "http://localhost:8080/api/health" > /dev/null 2>&1; then
        log_ok "Backend API: respondendo"
    else
        log_warn "Backend API: não respondeu em localhost:8080"
        errors=$((errors + 1))
    fi

    # Frontend
    if curl -sf --max-time 10 "http://localhost" > /dev/null 2>&1; then
        log_ok "Frontend: respondendo"
    else
        log_info "Frontend não respondeu em localhost (Caddy pode ser o ponto de entrada)"
    fi

    echo ""
    if [[ $errors -eq 0 ]]; then
        log_ok "Todos os componentes verificados com sucesso!"
    else
        log_warn "$errors componente(s) com atenção — veja o log: $LOG_FILE"
    fi

    return $errors
}

# =============================================================================
# RESUMO FINAL
# =============================================================================
print_summary() {
    log_header "INSTALAÇÃO CONCLUÍDA"

    # Recupera credenciais geradas
    local admin_pass grafana_pass
    admin_pass=$(grep "^ADMIN_PASSWORD=" "$ENV_FILE" | cut -d= -f2)
    grafana_pass=$(grep "^GRAFANA_ADMIN_PASSWORD=" "$ENV_FILE" | cut -d= -f2)

    echo -e "\n${BOLD}${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}${GREEN}║         AsteriskIA instalado com sucesso!             ║${NC}"
    echo -e "${BOLD}${GREEN}╚══════════════════════════════════════════════════════╝${NC}\n"

    echo -e "${BOLD}🌐 URLs de acesso:${NC}"
    echo -e "   Painel principal : ${CYAN}https://app.voiphash.com.br${NC}"
    echo -e "   Grafana           : ${CYAN}https://app.voiphash.com.br:3000${NC}"
    echo -e "   Prometheus        : ${CYAN}https://app.voiphash.com.br:9090${NC}"
    echo -e "   Agentes           : ${CYAN}https://app.voiphash.com.br/agents/${NC}"

    echo -e "\n${BOLD}🔑 Credenciais geradas:${NC}"
    echo -e "   Admin (painel)    : admin / ${YELLOW}${admin_pass}${NC}"
    echo -e "   Grafana           : admin / ${YELLOW}${grafana_pass}${NC}"
    echo -e "   Arquivo completo  : ${CYAN}${ENV_FILE}${NC}"

    echo -e "\n${BOLD}⚠️  Ações obrigatórias pós-instalação:${NC}"
    echo -e "   ${RED}1.${NC} Preencha ${CYAN}GEMINI_API_KEY${NC} em ${ENV_FILE}"
    echo -e "      (obtenha em: https://aistudio.google.com/app/apikey)"
    echo -e "      Após preencher: ${CYAN}docker compose up -d ai-agent${NC}"
    echo -e ""
    echo -e "   ${RED}2.${NC} Configure Jira via painel:"
    echo -e "      Painel → Settings → Jira"
    echo -e ""
    echo -e "   ${RED}3.${NC} Solicite à operadora SIP liberar o IP ${CYAN}${PUBLIC_IP:-SEU_IP}${NC}"
    echo -e "      no tronco peer ${CYAN}186.233.141.64${NC}"
    echo -e ""
    echo -e "   ${RED}4.${NC} Configure Zabbix e Telegram via painel (Módulo 3)"

    echo -e "\n${BOLD}🛠  Comandos úteis:${NC}"
    echo -e "   Atualizar sistema  : ${CYAN}asteriskia-update${NC}"
    echo -e "   Ver containers     : ${CYAN}docker compose -f $INSTALL_DIR/docker-compose.yml ps${NC}"
    echo -e "   Logs do ai-agent   : ${CYAN}docker logs -f asteriskia-ai-agent${NC}"
    echo -e "   Logs do Asterisk   : ${CYAN}docker logs -f asteriskia-asterisk${NC}"
    echo -e "   Console Asterisk   : ${CYAN}docker exec -it asteriskia-asterisk asterisk -rvvv${NC}"
    echo -e "   Log de instalação  : ${CYAN}$LOG_FILE${NC}"

    echo -e "\n${BOLD}📞 Softphone WebRTC:${NC}"
    echo -e "   Ramal registrado   : ${CYAN}9001${NC} (senha: webrtc9001pass)"
    echo -e "   Disque ${CYAN}1000${NC} para testar a URA Jira (Módulo 1)"
    echo -e "   Disque ${CYAN}1001${NC} para testar alertas Zabbix (Módulo 3)\n"
}

# =============================================================================
# MAIN
# =============================================================================
main() {
    clear
    echo -e "${BOLD}${BLUE}"
    cat << 'BANNER'
    ___         __           _     __   ______  __
   /   |  _____/ /____  _____(_)___/ /__/  _/ |/ /
  / /| | / ___/ __/ _ \/ ___/ / __  // / /|   /
 / ___ |(__  ) /_/  __/ /  / / /_/ // /_/   |
/_/  |_/____/\__/\___/_/  /_/\__,_/___/_/|_/

    Instalação Automatizada — AsteriskIA Telecom
BANNER
    echo -e "${NC}"

    check_root
    detect_os

    echo -e "\n${BOLD}Sistema: ${NC}$OS_NAME"
    echo -e "${BOLD}Destino: ${NC}$INSTALL_DIR"
    echo -e "${BOLD}Log    : ${NC}$LOG_FILE"
    echo ""

    # Confirmação do usuário
    echo -e "${YELLOW}Esta instalação irá:${NC}"
    echo "  • Instalar Docker, Caddy, fail2ban e dependências"
    echo "  • Clonar o repositório AsteriskIA em $INSTALL_DIR"
    echo "  • Compilar o Asterisk 21 LTS do código-fonte (~10-15 min)"
    echo "  • Iniciar 12 containers Docker"
    echo "  • Configurar HTTPS automático via Caddy"
    echo ""
    read -rp "Continuar? [S/n] " confirm
    confirm="${confirm:-S}"
    [[ "$confirm" =~ ^[Ss]$ ]] || { echo "Instalação cancelada."; exit 0; }

    # Execução dos passos
    check_prerequisites
    install_system_deps
    install_docker
    install_caddy
    clone_repository
    configure_environment
    configure_kernel
    start_containers
    configure_caddy
    configure_fail2ban
    install_update_script
    configure_logrotate
    verify_installation || true   # não interrompe no erro de verificação
    print_summary
}

main "$@"
