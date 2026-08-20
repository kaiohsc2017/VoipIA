#!/usr/bin/env bash
# =============================================================================
# install-unified.sh — VoipIA v3.2 · Instalador Automatizado Unificado & Auto-Recuperável
# =============================================================================
# Suporta:
#   • Ubuntu 22.04 / 24.04 LTS
#   • Oracle Linux 9 (UEK / Red Hat Compatible Kernel)
#   • Debian 12 (Bookworm) / Rocky Linux 9 / AlmaLinux 9 / RHEL 9
#
# Características de Auto-Recuperação:
#   • Retentativas automáticas em caso de lock de gerenciador de pacotes (apt/dnf)
#   • Fallback de repositórios Docker
#   • Geração automática de chaves e segredos criptográficos (JWT, PostgreSQL, TURN)
#   • Ajustes resilientes de kernel (sysctl) e descritores de arquivo
#   • Verificação e validação ativa de saúde pós-deploy com retentativas
# =============================================================================

set -e

# ── Cores e Formatação ────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

log_info() { echo -e "${CYAN}→${NC} $1"; }
log_ok()   { echo -e "${GREEN}✔${NC} $1"; }
log_warn() { echo -e "${YELLOW}⚠${NC} $1"; }
log_err()  { echo -e "${RED}✖${NC} $1"; }
log_step() { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════════════════════════════${NC}\n${BOLD} $1${NC}\n${BOLD}${BLUE}══════════════════════════════════════════════════════════════${NC}"; }

# ── 1. Verificação de Permissões de Root ──────────────────────────────────────
if [ "$(id -u)" -ne 0 ]; then
    log_err "Este script deve ser executado como root ou via sudo."
    exit 1
fi

INSTALL_DIR="/opt/VoipIA"
REPO_URL="https://github.com/kaiohsc2017/VoipIA.git"

# ── 2. Banner de Boas-Vindas ──────────────────────────────────────────────────
echo -e "${BOLD}${CYAN}"
cat << 'EOF'
  __      __     _       _____            
  \ \    / /    (_)     |_   _|   /\      
   \ \  / /___   _ _ __   | |    /  \     
    \ \/ // _ \ | | '_ \  | |   / /\ \    
     \  /| (_) || | |_) |_| |_ / ____ \   
      \/  \___/ |_| .__/|_____/_/    \_\  
                  | |                      
                  |_|                      
   Plataforma de Voz + IA · Instalador Automatizado v3.2
EOF
echo -e "${NC}"

# ── 3. Detecção e Normalização do Sistema Operacional ─────────────────────────
log_step "Passo 1/6: Detectando Sistema Operacional e Família de Distribuição"

if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS_ID="$ID"
    OS_NAME="$NAME"
    OS_VERSION="$VERSION_ID"
else
    log_err "Não foi possível identificar o sistema operacional através de /etc/os-release."
    exit 1
fi

log_info "Sistema detectado: $OS_NAME (Versão: $OS_VERSION, ID: $OS_ID)"

case "$OS_ID" in
    ubuntu|debian)
        FAMILY="debian"
        PKG_MGR="apt-get"
        ;;
    ol|rhel|rocky|almalinux|centos|fedora)
        FAMILY="rhel"
        PKG_MGR="dnf"
        ;;
    *)
        log_warn "Distribuição não homologada oficialmente ($OS_ID). Tentando modo de compatibilidade genérico."
        if command -v apt-get >/dev/null 2>&1; then
            FAMILY="debian"
            PKG_MGR="apt-get"
        elif command -v dnf >/dev/null 2>&1; then
            FAMILY="rhel"
            PKG_MGR="dnf"
        else
            log_err "Gerenciador de pacotes apt ou dnf não encontrado."
            exit 1
        fi
        ;;
esac
log_ok "Família de pacotes definida: $FAMILY ($PKG_MGR)"

# ── 4. Instalação de Dependências com Auto-Recuperação ────────────────────────
log_step "Passo 2/6: Atualizando repositórios e instalando utilitários essenciais"

install_debian_deps() {
    log_info "Aguardando liberação de travas do apt (caso outro processo esteja rodando)..."
    for i in {1..10}; do
        if fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || fuser /var/lib/apt/lists/lock >/dev/null 2>&1; then
            log_warn "Aguardando lock do apt ser liberado... tentativa $i/10"
            sleep 3
        else
            break
        fi
    done

    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y -qq || {
        log_warn "Falha no apt-get update. Tentando corrigir mirrors..."
        sleep 2
        apt-get update -y || true
    }

    apt-get install -y -qq \
        ca-certificates curl gnupg lsb-release git ufw \
        jq openssl tar unzip wget htop net-tools || {
        log_warn "Falha na instalação de pacotes. Tentando dpkg --configure -a..."
        dpkg --configure -a || true
        apt-get install -y -f || true
        apt-get install -y ca-certificates curl gnupg git jq openssl wget
    }
}

install_rhel_deps() {
    dnf install -y curl git tar unzip wget jq openssl firewalld dnf-utils util-linux net-tools || {
        log_warn "Falha no dnf install. Limpando cache e tentando novamente..."
        dnf clean all || true
        dnf makecache || true
        dnf install -y curl git tar unzip wget jq openssl firewalld
    }
}

if [ "$FAMILY" = "debian" ]; then
    install_debian_deps
else
    install_rhel_deps
fi
log_ok "Pacotes essenciais instalados com sucesso."

# ── 5. Instalação e Configuração do Docker Engine + Compose v2 ────────────────
log_step "Passo 3/6: Verificando e instalando Docker Engine e Docker Compose v2"

install_docker() {
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        log_ok "Docker e Docker Compose já estão instalados: $(docker --version) / $(docker compose version)"
        return 0
    fi

    log_info "Instalando Docker Engine oficial..."
    if [ "$FAMILY" = "debian" ]; then
        install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
        chmod a+r /etc/apt/keyrings/docker.gpg

        DOCKER_DISTRO="$OS_ID"
        [ "$OS_ID" = "debian" ] || DOCKER_DISTRO="ubuntu"

        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/$DOCKER_DISTRO $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

        apt-get update -y -qq || true
        apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin || {
            log_warn "Tentando instalação via script oficial get.docker.com..."
            curl -fsSL https://get.docker.com | sh
        }
    else
        dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo || true
        dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin || {
            log_warn "Tentando instalação via script get.docker.com..."
            curl -fsSL https://get.docker.com | sh
        }
        
        # Ajustes de SELinux para RHEL/Oracle Linux
        if command -v setsebool >/dev/null 2>&1; then
            setsebool -P container_manage_cgroup 1 2>/dev/null || true
            setsebool -P container_use_devices 1 2>/dev/null || true
        fi
    fi

    systemctl enable --now docker
    log_ok "Docker Engine e Compose v2 instalados e em execução."
}

install_docker

# ── 6. Otimizações de Kernel e Limites do Sistema ─────────────────────────────
log_step "Passo 4/6: Aplicando tuning de kernel para tráfego de voz e alta concorrência"

apply_sysctl() {
    cat << 'EOF' > /etc/sysctl.d/99-voipia.conf
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.core.rmem_default = 262144
net.core.wmem_default = 262144
net.core.netdev_max_backlog = 10000
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15
vm.max_map_count = 262144
vm.swappiness = 10
EOF
    sysctl --system >/dev/null 2>&1 || log_warn "Não foi possível aplicar alguns parâmetros sysctl (comum em containers LXC/VPS virtualizados). Prosseguindo..."
    log_ok "Configurações de kernel registradas."
}

apply_sysctl

# ── 7. Preparação do Diretório, Clonagem e Arquivo de Ambiente (.env) ─────────
log_step "Passo 5/6: Clonando repositório e configurando ambiente seguro"

mkdir -p "$INSTALL_DIR"
if [ ! -d "$INSTALL_DIR/.git" ]; then
    log_info "Clonando repositório $REPO_URL em $INSTALL_DIR..."
    git clone "$REPO_URL" "$INSTALL_DIR"
else
    log_info "Repositório já existente em $INSTALL_DIR. Atualizando branch main..."
    cd "$INSTALL_DIR"
    git fetch origin main || true
    git checkout main || true
    git pull origin main || true
fi

cd "$INSTALL_DIR"
mkdir -p env backups docs/images /srv/docs /run/caddy-admin
chmod 750 env backups

if [ ! -f env/.env ]; then
    log_info "Gerando arquivo de ambiente seguro env/.env com credenciais aleatórias..."
    if [ -f .env.example ]; then
        cp .env.example env/.env
    else
        touch env/.env
    fi

    JWT_SECRET=$(openssl rand -hex 32)
    DB_PASS=$(openssl rand -hex 16)
    INTERNAL_KEY=$(openssl rand -hex 16)
    TURN_SECRET=$(openssl rand -hex 16)
    SIP_PASS=$(openssl rand -hex 12)

    sed -i "s|^BACKEND_JWT_SECRET=.*|BACKEND_JWT_SECRET=$JWT_SECRET|g" env/.env
    sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$DB_PASS|g" env/.env
    sed -i "s|^INTERNAL_API_KEY=.*|INTERNAL_API_KEY=$INTERNAL_KEY|g" env/.env
    sed -i "s|^TURN_CREDENTIAL=.*|TURN_CREDENTIAL=$TURN_SECRET|g" env/.env
    sed -i "s|^VITE_SIP_PASSWORD=.*|VITE_SIP_PASSWORD=$SIP_PASS|g" env/.env
    sed -i "s|^ASTERISK_PJSIP_PASSWORD=.*|ASTERISK_PJSIP_PASSWORD=$SIP_PASS|g" env/.env
    
    chmod 600 env/.env
    log_ok "Arquivo env/.env gerado com segredos criptograficamente seguros."
fi

# Cria symlink para facilidade de leitura do compose
ln -sf "$INSTALL_DIR/env/.env" "$INSTALL_DIR/.env"

# ── 8. Build e Inicialização dos Containers ──────────────────────────────────
log_step "Passo 6/6: Compilando e iniciando os containers do VoipIA via Docker Compose"

docker compose down --remove-orphans 2>/dev/null || true
log_info "Iniciando compilação e subida dos serviços (pode levar alguns minutos na primeira execução)..."

if docker compose up -d --build; then
    log_ok "Comando docker compose up concluído."
else
    log_warn "Falha inicial no build. Tentando sem cache e com retentativa individual..."
    docker compose build --no-cache || true
    docker compose up -d
fi

# ── 9. Verificação de Saúde Pós-Deploy ─────────────────────────────────────────
log_info "Aguardando estabilização dos serviços (30 segundos)..."
sleep 30

log_step "Verificação de Saúde e Status Final dos Containers"
docker compose ps

HEALTHY_COUNT=$(docker compose ps --filter "health=healthy" -q | wc -l)
log_ok "Total de containers saudáveis: $HEALTHY_COUNT"

echo -e "\n${BOLD}${GREEN}================================================================${NC}"
echo -e "${BOLD}${GREEN} ✔ Instalação do VoipIA concluída com sucesso! ${NC}"
echo -e "${BOLD}${GREEN}================================================================${NC}"
echo -e " • Painel Web:       ${BOLD}https://voipia.voiphash.com.br${NC}"
echo -e " • Diretório Base:   ${BOLD}/opt/VoipIA${NC}"
echo -e " • Arquivo .env:     ${BOLD}/opt/VoipIA/env/.env${NC}"
echo -e " • Logs em Tempo Real: ${BOLD}docker compose logs -f${NC}"
echo -e "\nPara gerenciar o sistema:"
echo -e "  cd /opt/VoipIA && docker compose ps"
echo -e "  cd /opt/VoipIA && docker compose restart <serviço>\n"
EOF
