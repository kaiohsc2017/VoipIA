#!/usr/bin/env bash
# =============================================================================
# install.sh — AsteriskIA v3.1 · Instalação Automatizada
# =============================================================================
# Compatível com:
#   • Ubuntu 22.04 LTS / 24.04 LTS
#   • Oracle Linux 9 / RHEL 9 / AlmaLinux 9 / Rocky Linux 9
#
# Uso:
#   curl -fsSL https://raw.githubusercontent.com/kaiohsc2017/AsteriskIA/main/install.sh | bash
#   -- ou --
#   bash install.sh [--update]
#
# Stack instalado:
#   Docker Engine + Compose v2 · Caddy (container standalone)
#   Asterisk 21 LTS · Spring Boot 3.3 · React 18 + TypeScript
#   Python 3.12 asyncio · PostgreSQL 16 · Flyway migrations
#   Multi-provider AI: Gemini, Anthropic, OpenAI, ElevenLabs, Grok, Perplexity, Ollama
# =============================================================================

set -euo pipefail

# ── Cores ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BLUE='\033[0;34m'; NC='\033[0m'; BOLD='\033[1m'

# ── Helpers ───────────────────────────────────────────────────────────────────
log_ok()   { echo -e "${GREEN}✔${NC} $1"; }
log_info() { echo -e "${CYAN}→${NC} $1"; }
log_warn() { echo -e "${YELLOW}⚠${NC} $1"; }
log_err()  { echo -e "${RED}✖${NC} $1"; exit 1; }
log_step() { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════════${NC}"; echo -e "${BOLD} $1${NC}"; echo -e "${BOLD}${BLUE}══════════════════════════════════════════${NC}"; }

# ── Detecção do OS ───────────────────────────────────────────────────────────
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_ID="$ID"
        OS_VER="$VERSION_ID"
        OS_NAME="$NAME"
    else
        log_err "Não foi possível detectar o sistema operacional."
    fi

    case "$OS_ID" in
        ubuntu)
            PKG_MANAGER="apt-get"
            PKG_UPDATE="apt-get update -qq"
            PKG_INSTALL="apt-get install -y -qq"
            DISTRO="ubuntu"
            ;;
        rhel|centos|almalinux|rocky|ol)
            PKG_MANAGER="dnf"
            PKG_UPDATE="dnf check-update -q || true"
            PKG_INSTALL="dnf install -y -q"
            DISTRO="rhel"
            ;;
        *)
            log_err "OS não suportado: $OS_NAME. Suportados: Ubuntu 22/24, Oracle/RHEL/Alma/Rocky Linux 9."
            ;;
    esac
    log_ok "Sistema detectado: $OS_NAME $OS_VER"
}

# ── Variáveis ─────────────────────────────────────────────────────────────────
REPO_URL="https://github.com/kaiohsc2017/AsteriskIA.git"
INSTALL_DIR="/opt/AsteriskIA"
ENV_DIR="$INSTALL_DIR/env"
ENV_FILE="$ENV_DIR/.env"
UPDATE_MODE="${1:-}"

# ── Banner ────────────────────────────────────────────────────────────────────
echo -e "${BOLD}"
cat << 'BANNER'
    ___         __           _      __    _____
   /   |  _____/ /____  _____(_)____/ /__ /  _/  ___
  / /| | / ___/ __/ _ \/ ___/ / ___/ //_/ / /   / _ \
 / ___ |(__  ) /_/  __/ /  / (__  ) ,<  _/ /   / ___/
/_/  |_/____/\__/\___/_/  /_/____/_/|_|/___/   /_/

  Plataforma VoIP + IA · v3.1
BANNER
echo -e "${NC}"

# ── Modo update ───────────────────────────────────────────────────────────────
if [ "$UPDATE_MODE" = "--update" ]; then
    log_step "Modo de atualização"
    if [ ! -d "$INSTALL_DIR" ]; then
        log_err "Instalação não encontrada em $INSTALL_DIR. Execute sem --update primeiro."
    fi
    cd "$INSTALL_DIR"
    log_info "Baixando atualizações..."
    git pull origin main
    log_info "[1/4] Parando serviços..."
    docker compose down
    log_info "[2/4] Rebuild dos containers..."
    docker compose build --no-cache
    log_info "[3/4] Subindo serviços..."
    docker compose up -d
    log_info "[4/4] Recarregando Caddyfile..."
    sleep 5
    curl -sf -X POST "http://localhost:2019/load" \
        -H "Content-Type: text/caddyfile" \
        --data-binary @Caddyfile 2>/dev/null \
        && log_ok "Caddyfile recarregado" \
        || log_warn "Admin API Caddy não respondeu"
    echo ""
    log_ok "Atualização concluída!"
    echo -e "  Se atualizou configurações de IA, verifique Settings → Inteligência Artificial"
    exit 0
fi

# ── Verificações ──────────────────────────────────────────────────────────────
log_step "1. Verificações do sistema"
detect_os

[ "$(id -u)" -eq 0 ] || log_err "Execute como root: sudo bash install.sh"

PUBLIC_IP=$(curl -sf --max-time 5 https://api.ipify.org 2>/dev/null || \
            curl -sf --max-time 5 https://ifconfig.me 2>/dev/null || \
            hostname -I | awk '{print $1}')
log_ok "IP público: ${CYAN}${PUBLIC_IP}${NC}"

TOTAL_RAM=$(free -m | awk '/^Mem:/{print $2}')
[ "$TOTAL_RAM" -ge 3500 ] || log_warn "RAM disponível: ${TOTAL_RAM}MB. Recomendado: 4GB+"
log_ok "RAM: ${TOTAL_RAM}MB"

# ── Pacotes base ──────────────────────────────────────────────────────────────
log_step "2. Instalação de dependências"
log_info "Atualizando índice de pacotes..."
$PKG_UPDATE

if [ "$DISTRO" = "ubuntu" ]; then
    $PKG_INSTALL \
        curl wget git unzip jq \
        ca-certificates gnupg lsb-release \
        ufw fail2ban \
        gettext-base 2>/dev/null
elif [ "$DISTRO" = "rhel" ]; then
    dnf install -y epel-release 2>/dev/null || true
    $PKG_INSTALL \
        curl wget git unzip jq \
        ca-certificates gnupg \
        firewalld fail2ban \
        gettext 2>/dev/null
fi
log_ok "Dependências instaladas"

# ── Docker ────────────────────────────────────────────────────────────────────
log_step "3. Docker Engine"
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
    DOCKER_VER=$(docker --version | awk '{print $3}' | tr -d ',')
    log_ok "Docker já instalado: v$DOCKER_VER"
else
    log_info "Instalando Docker Engine..."
    if [ "$DISTRO" = "ubuntu" ]; then
        install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
            | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        chmod a+r /etc/apt/keyrings/docker.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
            > /etc/apt/sources.list.d/docker.list
        apt-get update -qq
        apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    elif [ "$DISTRO" = "rhel" ]; then
        dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo 2>/dev/null || \
        dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo 2>/dev/null
        $PKG_INSTALL docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    fi
    systemctl enable --now docker
    log_ok "Docker instalado"
fi

# ── Caddy ─────────────────────────────────────────────────────────────────────
log_step "4. Caddy (proxy reverso HTTPS)"
if docker ps --filter "name=caddy-proxy" --format "{{.Names}}" | grep -q caddy-proxy 2>/dev/null; then
    log_ok "Caddy já rodando"
else
    log_info "Iniciando container Caddy..."
    docker network create caddy-net 2>/dev/null || true
    docker run -d \
        --name caddy-proxy \
        --restart unless-stopped \
        --network caddy-net \
        -p 80:80 -p 443:443 -p 443:443/udp \
        -p 2019:2019 \
        -v caddy_data:/data \
        -v caddy_config:/config \
        caddy:2-alpine \
        caddy run --config /dev/stdin --adapter caddyfile <<< 'localhost { respond "Caddy OK" }'
    sleep 3
    log_ok "Caddy iniciado"
fi

# ── Repositório ───────────────────────────────────────────────────────────────
log_step "5. Repositório AsteriskIA"
if [ -d "$INSTALL_DIR/.git" ]; then
    log_info "Atualizando repositório existente..."
    cd "$INSTALL_DIR" && git pull origin main
    log_ok "Repositório atualizado"
else
    log_info "Clonando repositório..."
    git clone "$REPO_URL" "$INSTALL_DIR"
    log_ok "Repositório clonado em $INSTALL_DIR"
fi

# ── Diretórios ────────────────────────────────────────────────────────────────
log_step "6. Estrutura de diretórios"
mkdir -p "$ENV_DIR"
mkdir -p "$INSTALL_DIR/asterisk/sounds"
chmod 750 "$ENV_DIR"
log_ok "Diretórios criados"

# ── Arquivo .env ──────────────────────────────────────────────────────────────
log_step "7. Configuração do ambiente (.env)"

gen_secret() { openssl rand -base64 32 | tr -d '/+=' | head -c 32; }
gen_pass()   { openssl rand -base64 16 | tr -d '/+=' | head -c 16; }

if [ -f "$ENV_FILE" ]; then
    log_warn ".env já existe — preservando configurações existentes"
else
    log_info "Gerando .env com credenciais seguras..."

    ADMIN_PASS=$(gen_pass)
    POSTGRES_PASS=$(gen_pass)
    JWT_SECRET=$(gen_secret)
    AMI_PASS=$(gen_secret)
    INTERNAL_KEY=$(gen_secret)

    cat > "$ENV_FILE" << EOF
# =============================================================================
# AsteriskIA — Variáveis de Ambiente
# Gerado em: $(date '+%Y-%m-%d %H:%M:%S')
# ATENÇÃO: Nunca versione este arquivo. Já está no .gitignore.
# =============================================================================

# ── Aplicação ─────────────────────────────────────────────────────────────────
APP_URL=https://app.voiphash.com.br
ADMIN_USERNAME=admin
ADMIN_PASSWORD=${ADMIN_PASS}
ADMIN_EMAIL=admin@voiphash.com.br

# ── JWT ───────────────────────────────────────────────────────────────────────
BACKEND_JWT_SECRET=${JWT_SECRET}
JWT_EXPIRATION_MS=86400000

# ── Chave interna (ai-agent ↔ backend) ───────────────────────────────────────
INTERNAL_API_KEY=${INTERNAL_KEY}

# ── PostgreSQL ────────────────────────────────────────────────────────────────
POSTGRES_DB=asteriskia
POSTGRES_USER=asteriskia
POSTGRES_PASSWORD=${POSTGRES_PASS}

# ── Asterisk AMI ──────────────────────────────────────────────────────────────
AST_AMI_USER=asteriskia
AST_AMI_PASSWORD=${AMI_PASS}

# ── SIP ───────────────────────────────────────────────────────────────────────
# IP público do servidor — OBRIGATÓRIO para RTP/WebRTC funcionar
SIP_PUBLIC_IP=${PUBLIC_IP}
SIP_DOMAIN=app.voiphash.com.br

# Tronco SIP da operadora (deixar vazio se não tiver)
SIP_TRUNK_HOST=
SIP_TRUNK_USER=
SIP_TRUNK_PASSWORD=
SIP_TRUNK_FROM_DOMAIN=

# ── Áudio ─────────────────────────────────────────────────────────────────────
AUDIO_STORAGE_PATH=/var/spool/asterisk/monitor

# ── IA — configurar pelo painel Settings → Inteligência Artificial ────────────
# Os modelos são gerenciados no banco (tabela ai_capability_chain)
# NÃO altere estes campos via .env — use o painel
GEMINI_API_KEY=
GEMINI_MODEL_STT=gemini-2.5-flash
GEMINI_MODEL_LLM=gemini-2.5-flash
GEMINI_MODEL_TTS=gemini-2.5-flash-preview-tts

# ── Jira ──────────────────────────────────────────────────────────────────────
JIRA_BASE_URL=
JIRA_USER_EMAIL=
JIRA_API_TOKEN=
JIRA_PROJECT_KEY=

# ── Zabbix ────────────────────────────────────────────────────────────────────
ZABBIX_URL=
ZABBIX_USER=
ZABBIX_PASSWORD=

# ── Telegram ──────────────────────────────────────────────────────────────────
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=

# ── Grafana ───────────────────────────────────────────────────────────────────
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=$(gen_pass)

# ── Frontend ─────────────────────────────────────────────────────────────────
VITE_STUN_URL=stun:stun.l.google.com:19302
EOF

    chmod 600 "$ENV_FILE"
    log_ok ".env gerado com credenciais seguras"
    echo ""
    echo -e "  ${BOLD}Credenciais geradas:${NC}"
    echo -e "  Admin:    ${CYAN}admin${NC} / ${CYAN}${ADMIN_PASS}${NC}"
    echo -e "  Postgres: ${CYAN}${POSTGRES_PASS}${NC}"
    echo -e "  Guarde essas credenciais em local seguro!"
fi

# ── Firewall ──────────────────────────────────────────────────────────────────
log_step "8. Configuração do Firewall"
if [ "$DISTRO" = "ubuntu" ]; then
    ufw --force reset > /dev/null 2>&1
    ufw default deny incoming > /dev/null 2>&1
    ufw default allow outgoing > /dev/null 2>&1
    ufw allow ssh > /dev/null 2>&1
    ufw allow 80/tcp > /dev/null 2>&1       # HTTP (redirect)
    ufw allow 443/tcp > /dev/null 2>&1      # HTTPS
    ufw allow 443/udp > /dev/null 2>&1      # QUIC
    ufw allow 5060/udp > /dev/null 2>&1     # SIP UDP
    ufw allow 5060/tcp > /dev/null 2>&1     # SIP TCP
    ufw allow 8088/tcp > /dev/null 2>&1     # WebRTC WS
    ufw allow 10000:10100/udp > /dev/null 2>&1  # RTP media
    ufw --force enable > /dev/null 2>&1
    log_ok "UFW configurado"
elif [ "$DISTRO" = "rhel" ]; then
    systemctl enable --now firewalld 2>/dev/null || true
    firewall-cmd --permanent --add-service=ssh > /dev/null 2>&1
    firewall-cmd --permanent --add-service=http > /dev/null 2>&1
    firewall-cmd --permanent --add-service=https > /dev/null 2>&1
    firewall-cmd --permanent --add-port=5060/udp > /dev/null 2>&1
    firewall-cmd --permanent --add-port=5060/tcp > /dev/null 2>&1
    firewall-cmd --permanent --add-port=8088/tcp > /dev/null 2>&1
    firewall-cmd --permanent --add-port=10000-10100/udp > /dev/null 2>&1
    firewall-cmd --reload > /dev/null 2>&1
    log_ok "Firewalld configurado"
fi

# ── Build e subida ────────────────────────────────────────────────────────────
log_step "9. Build e inicialização dos containers"
cd "$INSTALL_DIR"

# Conecta containers à rede do Caddy
docker network connect caddy-net asteriskia-asterisk 2>/dev/null || true

log_info "Construindo imagens (pode demorar 15-20 min na primeira vez)..."
log_info "Asterisk 21 com G.729+G.711 será compilado do fonte..."
docker compose build --no-cache 2>&1 | tail -5

log_info "Iniciando containers..."
docker compose up -d

log_info "Aguardando serviços inicializarem..."
sleep 20

# ── Caddy config ──────────────────────────────────────────────────────────────
log_step "10. Configuração do Caddy (HTTPS)"
log_info "Carregando Caddyfile..."
docker network connect caddy-net asteriskia-frontend 2>/dev/null || true
docker network connect caddy-net asteriskia-backend 2>/dev/null || true
docker network connect caddy-net asteriskia-asterisk 2>/dev/null || true
docker network connect caddy-net asteriskia-agents-api 2>/dev/null || true
docker network connect caddy-net asteriskia-agents-ui 2>/dev/null || true

sleep 5
curl -sf -X POST "http://localhost:2019/load" \
    -H "Content-Type: text/caddyfile" \
    --data-binary @"$INSTALL_DIR/Caddyfile" 2>/dev/null \
    && log_ok "Caddyfile carregado — HTTPS ativo" \
    || log_warn "Admin API Caddy não respondeu. Execute manualmente:"

# ── Verificação ───────────────────────────────────────────────────────────────
log_step "11. Verificação da instalação"
sleep 10

check_container() {
    local name="$1"
    local status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || \
                   docker inspect --format='{{.State.Status}}' "$name" 2>/dev/null || echo "not found")
    if echo "$status" | grep -qE "healthy|running"; then
        log_ok "$name: ${GREEN}$status${NC}"
    else
        log_warn "$name: ${YELLOW}$status${NC}"
    fi
}

check_container "asteriskia-postgres"
check_container "asteriskia-backend"
check_container "asteriskia-frontend"
check_container "asteriskia-asterisk"
check_container "asteriskia-ai-agent"

if curl -sf --max-time 10 "http://localhost:8081/api/health" > /dev/null 2>&1; then
    log_ok "Backend API respondendo em :8081"
else
    log_warn "Backend API: não respondeu (pode estar inicializando)"
fi

# ── Ramal 9002 ────────────────────────────────────────────────────────────────
log_step "12. Configurando ramal SIP físico (9002)"
log_info "Aguardando Asterisk carregar..."
sleep 5
docker exec asteriskia-asterisk asterisk -rx "module reload res_pjsip.so" 2>/dev/null && \
    log_ok "PJSIP recarregado" || log_warn "PJSIP reload falhou (normal se ainda inicializando)"

# ── Resumo ────────────────────────────────────────────────────────────────────
log_step "✅ Instalação concluída!"

ADMIN_PASS_SHOW=$(grep "^ADMIN_PASSWORD=" "$ENV_FILE" | cut -d= -f2)

echo ""
echo -e "${BOLD}Acesso:${NC}"
echo -e "  🌐 Painel:      ${CYAN}https://app.voiphash.com.br${NC}"
echo -e "  🌐 Agentes:     ${CYAN}https://app.voiphash.com.br/agents/${NC}"
echo -e "  👤 Usuário:     ${CYAN}admin${NC}"
echo -e "  🔑 Senha:       ${CYAN}${ADMIN_PASS_SHOW}${NC}"
echo ""
echo -e "${BOLD}Softphone WebRTC (ramal 9001):${NC}"
echo -e "  Integrado ao painel — sem configuração adicional"
echo ""
echo -e "${BOLD}Softphone físico (ramal 9002):${NC}"
echo -e "  Servidor: ${CYAN}${PUBLIC_IP}${NC}  Porta: ${CYAN}5060 UDP${NC}"
echo -e "  Usuário:  ${CYAN}9002${NC}  Senha: ${CYAN}sip9002pass2025${NC}"
echo -e "  Codecs:   ${CYAN}G.729 / G.711a / G.711u${NC}"
echo ""
echo -e "${BOLD}${YELLOW}Próximos passos obrigatórios:${NC}"
echo -e "   ${RED}1.${NC} Configure a IA:"
echo -e "      Painel → Settings → Inteligência Artificial"
echo -e "      • Cole a API Key do Google Gemini (aistudio.google.com)"
echo -e "      • STT/LLM: ${CYAN}gemini-2.5-flash${NC}"
echo -e "      • TTS: ${CYAN}gemini-2.5-flash-preview-tts${NC}"
echo -e "      ⚠️  gemini-2.0-flash foi descontinuado — não usar"
echo ""
echo -e "   ${RED}2.${NC} Configure Jira:"
echo -e "      Painel → Settings → Jira"
echo ""
echo -e "   ${RED}3.${NC} Verifique SIP_PUBLIC_IP no .env:"
echo -e "      ${CYAN}grep SIP_PUBLIC_IP $ENV_FILE${NC}"
echo -e "      Deve ser: ${CYAN}${PUBLIC_IP}${NC}"
echo ""
echo -e "   ${RED}4.${NC} Teste a chamada interna:"
echo -e "      No softphone: disque ${CYAN}1000${NC} → deve ouvir boas-vindas da URA"
echo ""
echo -e "${BOLD}Suporte:${NC} github.com/kaiohsc2017/AsteriskIA"
