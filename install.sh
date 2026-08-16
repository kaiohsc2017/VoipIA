#!/usr/bin/env bash
# =============================================================================
# install.sh — VoipIA v3.2 · Instalação Automatizada
# =============================================================================
# Compatível com:
#   • Ubuntu 22.04 LTS
#   • Ubuntu 24.04 LTS
#
# Para Oracle Linux 9 (RHEL family), use install-oracle9.sh — os dois scripts
# provisionam o mesmo stack, mas o gerenciamento de pacotes/firewall/SELinux
# diverge o suficiente entre apt/ufw e dnf/firewalld para justificar scripts
# separados em vez de um único script com ramificações por toda parte.
#
# Uso:
#   curl -fsSL https://raw.githubusercontent.com/kaiohsc2017/VoipIA/main/install.sh | bash
#   -- ou --
#   bash install.sh [--update]
#
# Variáveis de ambiente opcionais (dimensionamento de hardware — recomendação
# documentada em CLAUDE.md/tela Documentação, NUNCA validada por teste de carga):
#   VOIPIA_AGENT_COUNT=<n>          quantidade de agentes de Call Center simultâneos
#                                       (pula a pergunta interativa; útil em automação)
#   VOIPIA_ACCEPT_HARDWARE_RISK=yes aceita seguir com hardware abaixo do recomendado
#                                       em execução não-interativa (sem isso, aborta)
#
# Stack instalado:
#   Docker Engine + Compose v2 · Caddy 2 (TLS automático, no compose)
#   Asterisk 21 LTS · Spring Boot 3.3 · React 18 + TypeScript
#   Python 3.12 asyncio (ai-agent + agents-platform) · PostgreSQL 16 · Flyway migrations
#   coturn (relay TURN/TURNS para WebRTC) · docker-helper (único ponto com acesso
#   ao docker.sock — F-CRIT-10) · fail2ban + nftables (lockdown SIP, no compose e no host)
#   RBAC granular por grupos de acesso + controle de acesso por Business Unit
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
        *)
            log_err "OS não suportado: $OS_NAME. Suportado: Ubuntu 22.04/24.04 LTS. Para Oracle Linux 9, use install-oracle9.sh."
            ;;
    esac
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

[ "$(id -u)" -eq 0 ] || log_err "Execute como root: sudo bash install.sh"

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

# ── Pacotes base ──────────────────────────────────────────────────────────────
log_step "2. Instalação de dependências"
log_info "Atualizando índice de pacotes..."
$PKG_UPDATE

$PKG_INSTALL \
    curl wget git unzip jq \
    ca-certificates gnupg lsb-release \
    ufw fail2ban \
    gettext-base 2>/dev/null
log_ok "Dependências instaladas"

# ── Docker ────────────────────────────────────────────────────────────────────
log_step "3. Docker Engine"
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
    DOCKER_VER=$(docker --version | awk '{print $3}' | tr -d ',')
    log_ok "Docker já instalado: v$DOCKER_VER"
else
    log_info "Instalando Docker Engine..."
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
    log_ok "Docker instalado"
fi

# ── Caddy ─────────────────────────────────────────────────────────────────────
# Caddy faz parte do docker compose — não é necessário iniciar manualmente.
log_step "4. Caddy (proxy reverso HTTPS)"
log_info "Caddy sobe junto com o stack via docker compose (próximo passo)"

# ── Repositório ───────────────────────────────────────────────────────────────
log_step "5. Repositório VoipIA"
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
# 700 (não 750): o diretório carrega BACKEND_JWT_SECRET, senhas de ramal SIP e
# chaves de provedores de IA — nenhum outro usuário do host deve nem listar o
# conteúdo. Contêineres não-root que precisam ler env/.env fazem isso via bind
# mount com o usuário do processo dentro do container, não via grupo do host.
chmod 700 "$ENV_DIR"
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

# ── Chave interna (ai-agent ↔ backend) ───────────────────────────────────────
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

# ── Firewall ──────────────────────────────────────────────────────────────────
log_step "8. Configuração do Firewall"
ufw --force reset > /dev/null 2>&1
ufw default deny incoming > /dev/null 2>&1
ufw default allow outgoing > /dev/null 2>&1
ufw allow ssh > /dev/null 2>&1
ufw allow 80/tcp > /dev/null 2>&1       # HTTP (redirect Caddy)
ufw allow 443/tcp > /dev/null 2>&1      # HTTPS
ufw allow 443/udp > /dev/null 2>&1      # HTTP/3 QUIC
ufw allow 5060/udp > /dev/null 2>&1     # SIP UDP
ufw allow 5060/tcp > /dev/null 2>&1     # SIP TCP
ufw allow 8088/tcp > /dev/null 2>&1     # WebRTC WS (Asterisk)
ufw allow 16000:16500/udp > /dev/null 2>&1  # RTP media
ufw allow 3478/udp > /dev/null 2>&1     # TURN (coturn) — controle
ufw allow 3478/tcp > /dev/null 2>&1     # TURN (coturn) — controle
ufw allow 5349/tcp > /dev/null 2>&1     # TURNS (TLS)
ufw allow 5349/udp > /dev/null 2>&1     # TURNS (TLS)
ufw allow 49152:49652/udp > /dev/null 2>&1  # TURN — relay (coturn/turnserver.conf)
ufw --force enable > /dev/null 2>&1
log_ok "UFW configurado"

# ── Lockdown SIP (systemd watcher no host) ────────────────────────────────────
log_step "8.1 Serviço de lockdown SIP"
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
log_step "8.2 Regras nftables para isolamento de containers"
if [ -f "$INSTALL_DIR/security/apply-raw-rules.sh" ]; then
    chmod +x "$INSTALL_DIR/security/apply-raw-rules.sh"
    bash "$INSTALL_DIR/security/apply-raw-rules.sh" \
        && log_ok "Regras nftables aplicadas" \
        || log_warn "Falha ao aplicar regras nftables — execute manualmente: bash $INSTALL_DIR/security/apply-raw-rules.sh"
else
    log_warn "apply-raw-rules.sh não encontrado — isolamento de containers não configurado"
fi

# ── Rotação do log do Asterisk (logrotate no host) ───────────────────────────
# O volume Docker asteriskia_asterisk_log cresce sem limite se nada rotacionar
# /var/log/asterisk/full — sem isso já chegou a 7G em produção. size 100M,
# rotate 10, maxage 10 (~1G rotacionado no total, o que vencer primeiro).
log_step "8.3 Rotação de log do Asterisk (logrotate)"
if [ -f "$INSTALL_DIR/security/voipia-asterisk.logrotate" ]; then
    cp "$INSTALL_DIR/security/voipia-asterisk.logrotate" /etc/logrotate.d/voipia-asterisk
    log_ok "logrotate configurado (/etc/logrotate.d/voipia-asterisk)"
else
    log_warn "voipia-asterisk.logrotate não encontrado — rotação do log do Asterisk não configurada"
fi

# ── Build e subida ────────────────────────────────────────────────────────────
log_step "9. Build e inicialização dos containers"
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
log_step "10. Verificação do Caddy (HTTPS)"
if docker inspect --format='{{.State.Status}}' voipia-caddy 2>/dev/null | grep -q running; then
    log_ok "Caddy rodando — HTTPS ativo em https://app.voiphash.com.br"
else
    log_warn "Caddy ainda não respondeu. Verifique: docker compose logs caddy"
fi

# ── coturn (TURNS/TLS) — depende do certificado que o Caddy acabou de emitir ──
# O coturn monta o certificado Let's Encrypt do Caddy (ver docker-compose.yml).
# Numa instalação nova, o caminho não existe até o Caddy concluir o primeiro
# ACME issuance — se o coturn subiu primeiro, o bind mount fica com um
# diretório vazio e o container nunca ganha o certificado até reiniciar.
log_step "10.1 Verificação do coturn (relay TURN/TURNS)"
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
log_step "12. Configurando ramal SIP físico (9002)"
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
