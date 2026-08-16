#!/usr/bin/env bash
# =============================================================================
# install-oracle9.sh — VoipIA v3.2 · Instalação Automatizada (Oracle Linux 9)
# =============================================================================
# Compatível com:
#   • Oracle Linux 9 (ID=ol, VERSION_ID=9.x)
#   • Aceita também RHEL/Rocky/AlmaLinux 9 (mesma base ABI), com aviso.
#
# Este script é o equivalente ao install.sh (Ubuntu), adaptado para a família
# dnf/firewalld/SELinux do RHEL 9. Não é uma versão "genérica multi-OS": as
# diferenças de gerenciador de pacotes, firewall e MAC (SELinux) são grandes
# o suficiente para que manter os dois scripts separados seja mais simples e
# mais seguro do que um único script cheio de `if [ "$DISTRO" = ... ]`.
#
# Diferenças relevantes de execução em relação ao install.sh:
#   • dnf em vez de apt-get; docker-ce vem do repositório oficial da Docker
#     para a família centos/rhel (compatível com Oracle Linux 9).
#   • firewalld em vez de ufw (Oracle Linux 9 não tem ufw disponível).
#   • SELinux: por padrão o OL9 vem "Enforcing". Bind mounts de host para
#     container (env/.env, asterisk/config, security/state etc.) são negados
#     pelo SELinux sem relabeling. Para uma instalação limpa e não travar em
#     "Permission Denied" dentro dos containers, este script coloca o SELinux
#     em modo "Permissive" (persistido em /etc/selinux/config) — é o mesmo
#     trade-off comum em hosts Docker RHEL-family quando não há tempo de
#     escrever políticas SELinux dedicadas. Para reforçar depois, a alternativa
#     é adicionar sufixo ":z" em cada bind mount do docker-compose.yml e voltar
#     o SELinux para Enforcing — não fizemos isso aqui por não querer alterar
#     um arquivo compartilhado com o ambiente Ubuntu de produção.
#   • Pacotes que conflitam com docker-ce (podman/buildah/runc do módulo
#     container-tools) são removidos antes da instalação, para não travar o
#     dnf em conflito de pacotes.
#
# Uso:
#   bash install-oracle9.sh [--update]
#
# Variáveis de ambiente opcionais (dimensionamento de hardware — recomendação
# documentada em CLAUDE.md/tela Documentação, NUNCA validada por teste de carga):
#   VOIPIA_AGENT_COUNT=<n>          quantidade de agentes de Call Center simultâneos
#                                       (pula a pergunta interativa; útil em automação)
#   VOIPIA_ACCEPT_HARDWARE_RISK=yes aceita seguir com hardware abaixo do recomendado
#                                       em execução não-interativa (sem isso, aborta)
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
        ol)
            [ "${VERSION_ID%%.*}" = "9" ] || log_err "Este script é para Oracle Linux 9.x. Detectado: $OS_NAME $OS_VER."
            ;;
        rhel|rocky|almalinux)
            [ "${VERSION_ID%%.*}" = "9" ] || log_err "Este script é para a família RHEL 9.x. Detectado: $OS_NAME $OS_VER."
            log_warn "Script escrito e testado para Oracle Linux 9 — $OS_NAME é ABI-compatível, mas não é o alvo oficial."
            ;;
        ubuntu)
            log_err "Este é o script para Oracle Linux 9. Para Ubuntu, use install.sh."
            ;;
        *)
            log_err "OS não suportado: $OS_NAME. Suportado: Oracle Linux 9 (e família RHEL 9)."
            ;;
    esac
    PKG_MANAGER="dnf"
    DISTRO="rhel9"
    log_ok "Sistema detectado: $OS_NAME $OS_VER"
}

# ── Variáveis ─────────────────────────────────────────────────────────────────
REPO_URL="https://github.com/kaiohsc2017/VoipIA.git"
INSTALL_DIR="/opt/VoipIA"
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

  Plataforma VoIP + IA · v3.2 · Oracle Linux 9
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
    curl -sf --unix-socket "$INSTALL_DIR/caddy-admin/admin.sock" "http://localhost/load" \
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

[ "$(id -u)" -eq 0 ] || log_err "Execute como root: sudo bash install-oracle9.sh"

PUBLIC_IP=$(curl -sf --max-time 5 https://api.ipify.org 2>/dev/null || \
            curl -sf --max-time 5 https://ifconfig.me 2>/dev/null || \
            hostname -I | awk '{print $1}')
log_ok "IP público: ${CYAN}${PUBLIC_IP}${NC}"

TOTAL_RAM=$(free -m | awk '/^Mem:/{print $2}')
CPU_COUNT=$(nproc)
log_ok "RAM: ${TOTAL_RAM}MB"
log_ok "vCPU: ${CPU_COUNT}"

# ── Dimensionamento de hardware pelo volume de agentes ───────────────────────
# Referência documentada (CLAUDE.md / tela Documentação → Call Center → Segurança
# e Endurecimento Operacional): 250 agentes simultâneos ⇒ ~24 vCPU / ~64GB RAM em
# servidor único (ou 2 servidores dedicados App+Banco) — recomendação por cálculo/
# composição, NUNCA validada por teste de carga real (descartado por decisão do
# usuário em 2026-08-15). Escala linear a partir dessa referência, com piso mínimo
# para instalações pequenas/dev.
log_step "1b. Dimensionamento de hardware para o volume de agentes"
log_info "Referência (250 agentes simultâneos): ~24 vCPU / ~64GB RAM em servidor único"
log_info "(ou App 16-24vCPU/32GB + Banco dedicado 8vCPU/32GB/NVMe em 2 servidores)."
log_info "Ver detalhes em CLAUDE.md ou na tela Documentação do sistema."

AGENT_COUNT="${VOIPIA_AGENT_COUNT:-}"
if [ -z "$AGENT_COUNT" ]; then
    if [ -t 0 ]; then
        read -r -p "Quantos agentes de Call Center vão se conectar simultaneamente neste ambiente? [10]: " AGENT_COUNT
        AGENT_COUNT="${AGENT_COUNT:-10}"
    else
        AGENT_COUNT=10
        log_warn "Execução não-interativa sem VOIPIA_AGENT_COUNT definido — assumindo 10 agentes (ambiente pequeno/dev)."
    fi
fi
case "$AGENT_COUNT" in
    ''|*[!0-9]*) log_err "Valor inválido para quantidade de agentes simultâneos: '$AGENT_COUNT' (esperado número inteiro)." ;;
esac

REC_CPU=$(( (AGENT_COUNT * 24 + 249) / 250 )); [ "$REC_CPU" -lt 2 ] && REC_CPU=2
REC_RAM_GB=$(( (AGENT_COUNT * 64 + 249) / 250 )); [ "$REC_RAM_GB" -lt 4 ] && REC_RAM_GB=4
REC_RAM_MB=$(( REC_RAM_GB * 1024 ))

log_info "Detectado neste servidor: ${CPU_COUNT} vCPU / ${TOTAL_RAM}MB RAM"
log_info "Recomendado para ${AGENT_COUNT} agentes simultâneos: ${REC_CPU} vCPU / ${REC_RAM_GB}GB RAM"

HARDWARE_ABAIXO=0
if [ "$CPU_COUNT" -lt "$REC_CPU" ]; then HARDWARE_ABAIXO=1; fi
if [ "$TOTAL_RAM" -lt "$REC_RAM_MB" ]; then HARDWARE_ABAIXO=1; fi

if [ "$HARDWARE_ABAIXO" -eq 1 ]; then
    log_warn "Hardware ABAIXO do recomendado para ${AGENT_COUNT} agentes simultâneos."
    log_warn "Risco: degradação de áudio (RTP), lentidão no backend/PostgreSQL e instabilidade"
    log_warn "sob carga real — esta recomendação não foi validada por teste de carga."
    if [ -t 0 ]; then
        read -r -p "Digite 'ACEITO O RISCO' (maiúsculas) para prosseguir mesmo assim, ou qualquer outra tecla para abortar: " RISK_ACK
        [ "$RISK_ACK" = "ACEITO O RISCO" ] || log_err "Instalação abortada — hardware insuficiente para ${AGENT_COUNT} agentes simultâneos."
        log_warn "Risco aceito pelo operador — prosseguindo com hardware abaixo do recomendado."
    else
        [ "${VOIPIA_ACCEPT_HARDWARE_RISK:-}" = "yes" ] || log_err "Instalação abortada (execução não-interativa): hardware abaixo do recomendado para ${AGENT_COUNT} agentes. Defina VOIPIA_ACCEPT_HARDWARE_RISK=yes para prosseguir mesmo assim."
        log_warn "VOIPIA_ACCEPT_HARDWARE_RISK=yes — prosseguindo mesmo com hardware abaixo do recomendado."
    fi
else
    log_ok "Hardware compatível com o volume de ${AGENT_COUNT} agentes informado."
fi

# ── Pacotes que conflitam com docker-ce ──────────────────────────────────────
log_step "2. Removendo pacotes conflitantes (podman/buildah/runc do container-tools)"
# Instalação padrão do Oracle Linux 9 pode trazer o módulo container-tools
# (podman/buildah/runc/containerd) — instalar docker-ce por cima sem remover
# esses pacotes é a causa mais comum de "transaction check error" no dnf.
# --noautoremove evita levar dependências que não são exclusivas desses pacotes.
dnf remove -y --noautoremove podman podman-docker buildah runc containerd 2>/dev/null \
    && log_ok "Pacotes conflitantes removidos" \
    || log_info "Nenhum pacote conflitante encontrado"

# ── Pacotes base ──────────────────────────────────────────────────────────────
log_step "3. Instalação de dependências"
log_info "Atualizando metadados de pacotes (sem dar upgrade no sistema)..."
dnf makecache -y -q

log_info "Instalando dnf-plugins-core (necessário para 'dnf config-manager')..."
dnf install -y -q dnf-plugins-core

# EPEL — necessário para fail2ban. Pacote oficial da Oracle (evita usar o
# epel-release genérico do Fedora, que não é totalmente suportado no OL9).
if ! dnf repolist 2>/dev/null | grep -qi epel; then
    log_info "Habilitando repositório EPEL da Oracle..."
    dnf install -y -q oracle-epel-release-el9 2>/dev/null \
        || dnf install -y -q epel-release 2>/dev/null \
        || log_warn "Não foi possível habilitar EPEL automaticamente — fail2ban pode falhar ao instalar."
fi

# Pacotes-base bem estabelecidos no repositório padrão do OL9 — falha aqui
# deve interromper o script (algo mais grave está errado no host/mirror).
dnf install -y -q \
    curl wget git unzip jq \
    ca-certificates gnupg2 \
    firewalld nftables \
    fail2ban \
    gettext \
    policycoreutils-python-utils
log_ok "Dependências base instaladas"

# iptables-nft e container-selinux: nomes de pacote podem variar entre
# mirrors/point-releases do OL9. Best-effort — não abortam a instalação, só
# avisam, pois o restante do script (Docker + firewalld) funciona sem eles
# na grande maioria dos casos (Docker já traz seu próprio caminho de nftables
# via containerd; container-selinux só importa se você reforçar SELinux depois).
dnf install -y -q iptables-nft 2>/dev/null \
    && log_ok "iptables-nft instalado" \
    || log_warn "iptables-nft não instalado (pacote pode ter outro nome neste mirror) — Docker deve funcionar mesmo assim via containerd/nftables nativo."
dnf install -y -q container-selinux 2>/dev/null \
    && log_ok "container-selinux instalado" \
    || log_warn "container-selinux não instalado — só relevante se você reforçar SELinux para Enforcing depois."

# ── SELinux ───────────────────────────────────────────────────────────────────
log_step "4. SELinux"
if command -v getenforce &>/dev/null; then
    CURRENT_SELINUX=$(getenforce)
    if [ "$CURRENT_SELINUX" = "Enforcing" ]; then
        log_warn "SELinux está Enforcing — os bind mounts do docker-compose.yml"
        log_warn "(env/.env, asterisk/config, security/state, etc.) seriam negados"
        log_warn "sem relabeling dedicado. Ajustando para Permissive para uma"
        log_warn "instalação limpa (trade-off documentado no topo deste script)."
        setenforce 0
        if [ -f /etc/selinux/config ]; then
            sed -i 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/selinux/config
        fi
        log_ok "SELinux definido como Permissive (persistido em /etc/selinux/config)"
    else
        log_ok "SELinux já está em modo $CURRENT_SELINUX — nada a fazer"
    fi
else
    log_info "SELinux não detectado neste host — pulando"
fi

# ── Docker ────────────────────────────────────────────────────────────────────
log_step "5. Docker Engine"
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
    DOCKER_VER=$(docker --version | awk '{print $3}' | tr -d ',')
    log_ok "Docker já instalado: v$DOCKER_VER"
else
    log_info "Adicionando repositório oficial da Docker (compatível com RHEL/Oracle Linux)..."
    dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    log_info "Instalando Docker Engine..."
    dnf install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    log_ok "Docker instalado (ainda não iniciado — firewalld precisa ser configurado antes)"
fi

# ── Firewall (firewalld) ──────────────────────────────────────────────────────
# Configurado ANTES de iniciar o Docker: reiniciar firewalld depois que o
# Docker já criou suas chains de NAT/FORWARD apaga essas regras — a ordem
# aqui evita esse problema clássico de "containers sem rede depois do boot".
log_step "6. Configuração do Firewall (firewalld)"
systemctl enable --now firewalld > /dev/null 2>&1
FW="firewall-cmd --permanent"
$FW --add-service=ssh > /dev/null 2>&1
$FW --add-port=80/tcp > /dev/null 2>&1        # HTTP (redirect Caddy)
$FW --add-port=443/tcp > /dev/null 2>&1       # HTTPS
$FW --add-port=443/udp > /dev/null 2>&1       # HTTP/3 QUIC
$FW --add-port=5060/udp > /dev/null 2>&1      # SIP UDP
$FW --add-port=5060/tcp > /dev/null 2>&1      # SIP TCP
$FW --add-port=8088/tcp > /dev/null 2>&1      # WebRTC WS (Asterisk)
$FW --add-port=16000-16500/udp > /dev/null 2>&1  # RTP media
$FW --add-port=3478/udp > /dev/null 2>&1      # TURN (coturn) — controle
$FW --add-port=3478/tcp > /dev/null 2>&1      # TURN (coturn) — controle
$FW --add-port=5349/tcp > /dev/null 2>&1      # TURNS (TLS)
$FW --add-port=5349/udp > /dev/null 2>&1      # TURNS (TLS)
$FW --add-port=49152-49652/udp > /dev/null 2>&1  # TURN — relay (coturn/turnserver.conf)
# Masquerade na zona pública — Docker depende de NAT de saída para os
# containers acessarem a internet (build de imagens, APIs de IA, etc.).
$FW --add-masquerade > /dev/null 2>&1
firewall-cmd --reload > /dev/null 2>&1
log_ok "firewalld configurado"

# ── Iniciar Docker (depois do firewall já configurado) ───────────────────────
systemctl enable --now docker > /dev/null 2>&1
log_ok "Docker Engine ativo"

# ── Caddy ─────────────────────────────────────────────────────────────────────
# Caddy faz parte do docker compose — não é necessário iniciar manualmente.
log_step "7. Caddy (proxy reverso HTTPS)"
log_info "Caddy sobe junto com o stack via docker compose (próximo passo)"

# ── Repositório ───────────────────────────────────────────────────────────────
log_step "8. Repositório VoipIA"
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
log_step "9. Estrutura de diretórios"
mkdir -p "$ENV_DIR"
mkdir -p "$INSTALL_DIR/asterisk/sounds"
# 700 (não 750): o diretório carrega BACKEND_JWT_SECRET, senhas de ramal SIP e
# chaves de provedores de IA — nenhum outro usuário do host deve nem listar o
# conteúdo. Contêineres não-root que precisam ler env/.env fazem isso via bind
# mount com o usuário do processo dentro do container, não via grupo do host.
chmod 700 "$ENV_DIR"
log_ok "Diretórios criados"

# ── Arquivo .env ──────────────────────────────────────────────────────────────
log_step "10. Configuração do ambiente (.env)"

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
    RAMAL_1001_PASS=$(gen_pass)
    RAMAL_1002_PASS=$(gen_pass)
    RAMAL_9001_PASS=$(gen_pass)
    RAMAL_9002_PASS=$(gen_pass)
    TURN_PASS=$(gen_pass)

    cat > "$ENV_FILE" << EOF
# =============================================================================
# VoipIA — Variáveis de Ambiente
# Gerado em: $(date '+%Y-%m-%d %H:%M:%S')
# ATENÇÃO: Nunca versione este arquivo. Já está no .gitignore.
# =============================================================================

# ── Administrador ─────────────────────────────────────────────────────────────
ADMIN_USERNAME=admin
ADMIN_PASSWORD=${ADMIN_PASS}

# ── JWT ───────────────────────────────────────────────────────────────────────
BACKEND_JWT_SECRET=${JWT_SECRET}

# ── Chave interna (ai-agent ↔ backend ↔ docker-helper) ───────────────────────
INTERNAL_API_KEY=${INTERNAL_KEY}

# ── PostgreSQL ────────────────────────────────────────────────────────────────
POSTGRES_DB=asteriskia
POSTGRES_USER=asteriskia
POSTGRES_PASSWORD=${POSTGRES_PASS}
# Porta de acesso local (bind 127.0.0.1 apenas — ver docker-compose.yml)
POSTGRES_PORT=5433

# ── Asterisk AMI ──────────────────────────────────────────────────────────────
AST_AMI_USER=asteriskia
AST_AMI_PASSWORD=${AMI_PASS}
AST_AMI_PORT=5038
AST_OUTBOUND_TRUNK=tronco-sip
AST_OUTBOUND_CONTEXT=discagem-sainte

# ── SIP ───────────────────────────────────────────────────────────────────────
# IP público do servidor — OBRIGATÓRIO para RTP/WebRTC funcionar
SIP_PUBLIC_IP=${PUBLIC_IP}
SIP_DOMAIN=app.voiphash.com.br

# Tronco SIP — peer IP-based (sem usuário/senha, fechado por IP)
SIP_TRUNK_HOST=186.233.141.64
SIP_TRUNK_FROM_DOMAIN=voiphash.com.br

# Ramais internos (senhas de registro SIP, injetadas no pjsip.conf via envsubst)
RAMAL_1001_PASSWORD=${RAMAL_1001_PASS}
RAMAL_1002_PASSWORD=${RAMAL_1002_PASS}
RAMAL_9001_PASSWORD=${RAMAL_9001_PASS}
RAMAL_9002_PASSWORD=${RAMAL_9002_PASS}

# ── AudioSocket ───────────────────────────────────────────────────────────────
AUDIOSOCKET_HOST=ai-agent
AUDIOSOCKET_PORT=9092

# ── Áudio ─────────────────────────────────────────────────────────────────────
AUDIO_STORAGE_PATH=/var/spool/asterisk/monitor

# ── Frontend React (VITE_ = build time — rebuilde ao alterar) ─────────────────
VITE_API_URL=https://app.voiphash.com.br/api/v1
VITE_ASTERISK_WS=wss://app.voiphash.com.br/asterisk-ws
VITE_SIP_URI=sip:9001@app.voiphash.com.br
VITE_SIP_PASSWORD=${RAMAL_9001_PASS}
VITE_STUN_URL=stun:stun.l.google.com:19302

# ── TURN (coturn — relay de RTP quando STUN não basta, ex: NAT simétrico) ─────
TURN_CREDENTIAL=${TURN_PASS}
VITE_TURN_URL=turn:${PUBLIC_IP}:3478
VITE_TURN_USER=asteriskia
VITE_TURN_CREDENTIAL=${TURN_PASS}

# ── CORS ──────────────────────────────────────────────────────────────────────
BACKEND_ALLOWED_ORIGINS=https://app.voiphash.com.br

# ── Google Gemini ─────────────────────────────────────────────────────────────
GEMINI_API_KEY=
GEMINI_MODEL_STT=gemini-2.5-flash
GEMINI_MODEL_LLM=gemini-2.5-flash
GEMINI_MODEL_TTS=gemini-2.5-flash-preview-tts

# ── Jira ──────────────────────────────────────────────────────────────────────
JIRA_BASE_URL=
JIRA_USER_EMAIL=
JIRA_API_TOKEN=
JIRA_PROJECT_KEY=
JIRA_ISSUE_TYPE=Task

# ── Zabbix ────────────────────────────────────────────────────────────────────
ZABBIX_API_URL=
ZABBIX_USER=
ZABBIX_PASSWORD=
ZABBIX_MIN_SEVERITY=4
ZABBIX_POLL_INTERVAL_MINUTES=5

# ── Telegram ──────────────────────────────────────────────────────────────────
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=

# ── Plataforma de Agentes — LLM ───────────────────────────────────────────────
AGENTS_LLM_PROVIDER=google
AGENTS_LLM_MODEL=gemini-2.5-flash
AGENTS_LLM_ENABLED=false
AGENTS_LLM_GOOGLE_KEY=
AGENTS_LLM_ANTHROPIC_KEY=
AGENTS_LLM_OPENAI_KEY=
AGENTS_LLM_MINIMAX_KEY=
AGENTS_LLM_MINIMAX_GROUP_ID=
AGENTS_LLM_COMPAT_URL=http://localhost:11434/v1
AGENTS_LLM_COMPAT_KEY=
EOF

    chmod 600 "$ENV_FILE"
    log_ok ".env gerado com credenciais seguras"
    echo ""
    echo -e "  ${BOLD}Credenciais geradas:${NC}"
    echo -e "  Admin:    ${CYAN}admin${NC} / ${CYAN}${ADMIN_PASS}${NC}"
    echo -e "  Postgres: ${CYAN}${POSTGRES_PASS}${NC}"
    echo -e "  Guarde essas credenciais em local seguro!"
fi

# ── Lockdown SIP (systemd watcher no host) ────────────────────────────────────
log_step "11. Serviço de lockdown SIP"
if [ -f "$INSTALL_DIR/security/voipia-lockdown.service" ]; then
    cp "$INSTALL_DIR/security/voipia-lockdown.service" /etc/systemd/system/
    chmod +x "$INSTALL_DIR/security/lockdown-watcher.sh"
    systemctl daemon-reload
    systemctl enable --now voipia-lockdown 2>/dev/null \
        && log_ok "Serviço voipia-lockdown ativo" \
        || log_warn "Não foi possível iniciar voipia-lockdown — verifique 'systemctl status voipia-lockdown'"
else
    log_warn "voipia-lockdown.service não encontrado — lockdown SIP não instalado"
fi

# ── Regras nftables (isolamento de containers) ───────────────────────────────
# firewalld no OL9 também usa nftables como backend (tabelas próprias,
# nomeadas "firewalld") — as regras abaixo vivem em tabelas separadas
# ("ip raw"/"ip filter") e coexistem sem conflito.
log_step "12. Regras nftables para isolamento de containers"
if [ -f "$INSTALL_DIR/security/apply-raw-rules.sh" ]; then
    chmod +x "$INSTALL_DIR/security/apply-raw-rules.sh"
    bash "$INSTALL_DIR/security/apply-raw-rules.sh" \
        && log_ok "Regras nftables aplicadas" \
        || log_warn "Falha ao aplicar regras nftables — execute manualmente: bash $INSTALL_DIR/security/apply-raw-rules.sh"
else
    log_warn "apply-raw-rules.sh não encontrado — isolamento de containers não configurado"
fi

# ── Build e subida ────────────────────────────────────────────────────────────
log_step "13. Build e inicialização dos containers"
cd "$INSTALL_DIR"

# O docker compose lê o .env da raiz do projeto, mas o arquivo real fica em
# env/.env (montado como volume nos containers). Cria symlink para ambos
# verem o mesmo arquivo. Sem isso, variáveis como SIP_PUBLIC_IP ficam vazias.
if [ ! -e "$INSTALL_DIR/.env" ]; then
    ln -sf "$ENV_FILE" "$INSTALL_DIR/.env"
    log_ok "Symlink .env criado (raiz -> env/.env)"
fi

log_info "Carregando variáveis do .env..."
set -a; source "$ENV_FILE"; set +a

# ── Build com auto-diagnóstico via IA ────────────────────────────────────────
ai_fix_build() {
    local SERVICE="$1"
    local ERROR_LOG="$2"
    local GEMINI_KEY="${GEMINI_API_KEY:-}"

    # Sem chave Gemini — pula o auto-fix
    [ -z "$GEMINI_KEY" ] && return 1

    log_info "🤖 Consultando IA para diagnosticar o erro de build..."

    local DOCKERFILE_CONTENT=""
    if [ -f "$INSTALL_DIR/$SERVICE/Dockerfile" ]; then
        DOCKERFILE_CONTENT=$(head -80 "$INSTALL_DIR/$SERVICE/Dockerfile")
    fi

    local OS_INFO
    OS_INFO="OS: $OS_NAME $OS_VER | Docker: $(docker --version 2>/dev/null | head -1)"

    # Monta prompt para o Gemini
    local PROMPT
    PROMPT=$(cat << PROMPT_EOF
Você é um engenheiro DevOps especialista em Docker e Asterisk.
Um build Docker falhou. Analise o erro e forneça APENAS um patch bash para corrigir o Dockerfile ou o ambiente.

Sistema: $OS_INFO
Serviço: $SERVICE

ERRO:
$ERROR_LOG

DOCKERFILE (primeiras 80 linhas):
$DOCKERFILE_CONTENT

Responda SOMENTE com um objeto JSON no formato:
{
  "diagnosis": "causa raiz em 1 linha",
  "fix_type": "dockerfile" ou "env" ou "network",
  "bash_commands": ["comando1", "comando2"],
  "dockerfile_patch": "sed -i 's/old/new/g' $INSTALL_DIR/$SERVICE/Dockerfile",
  "explanation": "o que foi corrigido"
}
Não inclua markdown, apenas JSON puro.
PROMPT_EOF
)

    # Chama Gemini API
    local RESPONSE
    RESPONSE=$(curl -sf --max-time 30         "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_KEY"         -H "Content-Type: application/json"         -d "{"contents":[{"parts":[{"text":$(echo "$PROMPT" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")}]}]}"         2>/dev/null)

    [ -z "$RESPONSE" ] && { log_warn "IA não respondeu."; return 1; }

    # Extrai JSON da resposta
    local AI_JSON
    AI_JSON=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    text = data['candidates'][0]['content']['parts'][0]['text']
    # Remove markdown se houver
    text = text.strip()
    if text.startswith('\`\`\`'):
        text = '
'.join(text.split('
')[1:-1])
    print(text)
except Exception as e:
    print('{}')
" 2>/dev/null)

    [ -z "$AI_JSON" ] || [ "$AI_JSON" = "{}" ] && { log_warn "IA não retornou JSON válido."; return 1; }

    local DIAGNOSIS FIX_TYPE EXPLANATION DOCKERFILE_PATCH
    DIAGNOSIS=$(echo "$AI_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('diagnosis',''))" 2>/dev/null)
    FIX_TYPE=$(echo "$AI_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('fix_type',''))" 2>/dev/null)
    EXPLANATION=$(echo "$AI_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('explanation',''))" 2>/dev/null)
    DOCKERFILE_PATCH=$(echo "$AI_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('dockerfile_patch',''))" 2>/dev/null)

    echo -e "
  ${CYAN}🤖 Diagnóstico IA:${NC} $DIAGNOSIS"
    echo -e "  ${CYAN}Tipo de fix:${NC} $FIX_TYPE"
    echo -e "  ${CYAN}Solução:${NC} $EXPLANATION"

    # Executa comandos bash sugeridos pela IA
    local BASH_CMDS
    BASH_CMDS=$(echo "$AI_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for cmd in d.get('bash_commands', []):
    print(cmd)
" 2>/dev/null)

    # SEGURANÇA: o script roda como root. NUNCA executar automaticamente
    # comandos/patch vindos de um LLM (podem ser destrutivos por alucinação
    # ou por conteúdo malicioso presente no log de build). Apenas exibimos as
    # sugestões e exigimos confirmação interativa explícita antes de aplicar.
    if [ -n "$BASH_CMDS" ] || [ -n "$DOCKERFILE_PATCH" ]; then
        echo -e "\n  ${YELLOW}⚠  A IA sugeriu os seguintes comandos (NÃO aplicados automaticamente):${NC}"
        [ -n "$BASH_CMDS" ]        && echo -e "${GRAY}$BASH_CMDS${NC}"
        [ -n "$DOCKERFILE_PATCH" ] && echo -e "${GRAY}$DOCKERFILE_PATCH${NC}"
        # Em modo não-interativo (sem TTY), nunca executa.
        if [ ! -t 0 ]; then
            log_warn "Execução não-interativa: sugestões da IA ignoradas por segurança."
            return 1
        fi
        printf "  Revise acima. Aplicar estes comandos como root? [digite 'sim' para confirmar]: "
        local CONFIRM=""
        read -r CONFIRM
        if [ "$CONFIRM" != "sim" ]; then
            log_warn "Correções da IA descartadas pelo operador."
            return 1
        fi
        while IFS= read -r cmd; do
            [ -z "$cmd" ] && continue
            cmd="${cmd//\$INSTALL_DIR/$INSTALL_DIR}"
            cmd="${cmd//\$SERVICE/$SERVICE}"
            echo -e "  ${GRAY}▶ $cmd${NC}"
            eval "$cmd" || true
        done <<< "$BASH_CMDS"
        if [ -n "$DOCKERFILE_PATCH" ]; then
            DOCKERFILE_PATCH="${DOCKERFILE_PATCH//\$INSTALL_DIR/$INSTALL_DIR}"
            DOCKERFILE_PATCH="${DOCKERFILE_PATCH//\$SERVICE/$SERVICE}"
            eval "$DOCKERFILE_PATCH" || true
        fi
    fi

    return 0
}

build_with_ai() {
    local MAX_RETRIES=3
    local ATTEMPT=1
    local BUILD_LOG="/tmp/asteriskia-build.log"

    while [ $ATTEMPT -le $MAX_RETRIES ]; do
        log_info "Build tentativa $ATTEMPT/$MAX_RETRIES..."

        docker compose build --no-cache 2>&1 | tee "$BUILD_LOG" | tail -8

        if [ ${PIPESTATUS[0]} -eq 0 ]; then
            log_ok "Build concluído com sucesso!"
            return 0
        fi

        log_warn "Build falhou na tentativa $ATTEMPT."

        # Identifica qual serviço falhou
        local FAILED_SERVICE
        FAILED_SERVICE=$(grep -oP "target \K\w+" "$BUILD_LOG" | tail -1)
        FAILED_SERVICE="${FAILED_SERVICE:-asterisk}"

        # Extrai as últimas linhas de erro relevantes
        local ERROR_EXCERPT
        ERROR_EXCERPT=$(grep -E "ERROR|error:|Error|FAILED|Cannot|failed" "$BUILD_LOG" | tail -15)

        if [ $ATTEMPT -lt $MAX_RETRIES ]; then
            # Tenta auto-fix via IA
            if ai_fix_build "$FAILED_SERVICE" "$ERROR_EXCERPT"; then
                log_info "Fix aplicado. Tentando build novamente..."
            else
                log_warn "Auto-fix não disponível. Aguardando 5s antes de tentar novamente..."
                sleep 5
            fi
        fi

        ATTEMPT=$((ATTEMPT + 1))
    done

    log_warn "Build falhou após $MAX_RETRIES tentativas."
    log_warn "Log completo em: $BUILD_LOG"
    echo -e "
  Para depurar manualmente:"
    echo -e "  ${CYAN}docker compose build asterisk 2>&1 | tail -30${NC}"
    return 1
}

log_info "Construindo imagens (pode demorar 15-20 min na primeira vez)..."
log_info "Auto-diagnóstico via IA ativo (requer GEMINI_API_KEY no .env)"
build_with_ai

# ── Verificação do Caddy ───────────────────────────────────────────────────────
# O Caddy faz parte do compose — sobe junto e carrega o Caddyfile automaticamente.
log_step "14. Verificação do Caddy (HTTPS)"
if docker inspect --format='{{.State.Status}}' voipia-caddy 2>/dev/null | grep -q running; then
    log_ok "Caddy rodando — HTTPS ativo em https://app.voiphash.com.br"
else
    log_warn "Caddy ainda não respondeu. Verifique: docker compose logs caddy"
fi

# ── coturn (TURNS/TLS) — depende do certificado que o Caddy acabou de emitir ──
log_step "14.1 Verificação do coturn (relay TURN/TURNS)"
CERT_DIR="/var/lib/docker/volumes/asteriskia_caddy_data/_data/caddy/certificates/acme-v02.api.letsencrypt.org-directory/app.voiphash.com.br"
CERT_WAIT=0
while [ ! -d "$CERT_DIR" ] && [ "$CERT_WAIT" -lt 60 ]; do
    sleep 5
    CERT_WAIT=$((CERT_WAIT + 5))
done
if [ -d "$CERT_DIR" ]; then
    log_ok "Certificado TLS emitido — reiniciando coturn para montar o certificado atual"
    docker compose restart coturn > /dev/null 2>&1 \
        && log_ok "coturn reiniciado com TLS" \
        || log_warn "Falha ao reiniciar coturn — execute manualmente: docker compose restart coturn"
else
    log_warn "Certificado TLS ainda não emitido após ${CERT_WAIT}s — TURNS (TLS) não vai funcionar até você reiniciar o coturn manualmente:"
    log_warn "  docker compose restart coturn"
    log_warn "STUN e TURN sem TLS continuam funcionando normalmente nesse meio-tempo."
fi

# ── Verificação ───────────────────────────────────────────────────────────────
log_step "15. Verificação da instalação"
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

check_container "voipia-postgres"
check_container "asteriskia-docker-helper"
check_container "voipia-backend"
check_container "voipia-frontend"
check_container "voipia-asterisk"
check_container "voipia-ai-agent"
check_container "voipia-agents-api"
check_container "voipia-coturn"
check_container "voipia-security"
check_container "voipia-caddy"

if curl -sf --max-time 10 "https://app.voiphash.com.br/api/health" > /dev/null 2>&1; then
    log_ok "Backend API respondendo via HTTPS"
else
    log_warn "Backend API: não respondeu ainda (pode estar inicializando)"
fi

# ── Ramal 9002 ────────────────────────────────────────────────────────────────
log_step "16. Configurando ramal SIP físico (9002)"
log_info "Aguardando Asterisk carregar..."
sleep 5
docker exec voipia-asterisk asterisk -rx "module reload res_pjsip.so" 2>/dev/null && \
    log_ok "PJSIP recarregado" || log_warn "PJSIP reload falhou (normal se ainda inicializando)"

# ── Resumo ────────────────────────────────────────────────────────────────────
log_step "✅ Instalação concluída!"

ADMIN_PASS_SHOW=$(grep "^ADMIN_PASSWORD=" "$ENV_FILE" | cut -d= -f2)
RAMAL_9002_PASS_SHOW=$(grep "^RAMAL_9002_PASSWORD=" "$ENV_FILE" | cut -d= -f2)

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
echo -e "  Usuário:  ${CYAN}9002${NC}  Senha: ${CYAN}${RAMAL_9002_PASS_SHOW}${NC}"
echo -e "  Codecs:   ${CYAN}G.729 / G.711a / G.711u${NC}"
echo ""
echo -e "${BOLD}${YELLOW}Notas específicas do Oracle Linux 9:${NC}"
echo -e "   ${YELLOW}•${NC} SELinux foi colocado em ${CYAN}Permissive${NC} — ver comentário no topo deste"
echo -e "     script para o trade-off e como reforçar depois (labels ':z' + Enforcing)."
echo -e "   ${YELLOW}•${NC} O firewall é gerenciado por ${CYAN}firewalld${NC}, não ufw. Para abrir uma porta"
echo -e "     nova: ${CYAN}firewall-cmd --permanent --add-port=PORTA/PROTO && firewall-cmd --reload${NC}"
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
echo -e "${BOLD}Suporte:${NC} github.com/kaiohsc2017/VoipIA"
