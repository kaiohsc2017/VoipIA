#!/usr/bin/env bash
# =============================================================================
# install.sh — AsteriskIA v3.1 · Instalação Automatizada
# =============================================================================
# Compatível com:
#   • Ubuntu 22.04 LTS
#   • Ubuntu 24.04 LTS
#
# Uso:
#   curl -fsSL https://raw.githubusercontent.com/kaiohsc2017/AsteriskIA/main/install.sh | bash
#   -- ou --
#   bash install.sh [--update]
#
# Stack instalado:
#   Docker Engine + Compose v2 · Caddy (no compose)
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
        *)
            log_err "OS não suportado: $OS_NAME. Suportado: Ubuntu 22.04 LTS e 24.04 LTS."
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

# ── AudioSocket ───────────────────────────────────────────────────────────────
AUDIOSOCKET_HOST=ai-agent
AUDIOSOCKET_PORT=9092

# ── Áudio ─────────────────────────────────────────────────────────────────────
AUDIO_STORAGE_PATH=/var/spool/asterisk/monitor

# ── Frontend React (VITE_ = build time — rebuilde ao alterar) ─────────────────
VITE_API_URL=https://app.voiphash.com.br/api/v1
VITE_ASTERISK_WS=wss://app.voiphash.com.br/asterisk-ws
VITE_SIP_URI=sip:9001@app.voiphash.com.br
VITE_SIP_PASSWORD=webrtc9001pass

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
ufw allow 10000:10100/udp > /dev/null 2>&1  # RTP media
ufw --force enable > /dev/null 2>&1
log_ok "UFW configurado"

# ── Lockdown SIP (systemd watcher no host) ────────────────────────────────────
log_step "8.1 Serviço de lockdown SIP"
if [ -f "$INSTALL_DIR/security/asteriskia-lockdown.service" ]; then
    cp "$INSTALL_DIR/security/asteriskia-lockdown.service" /etc/systemd/system/
    chmod +x "$INSTALL_DIR/security/lockdown-watcher.sh"
    systemctl daemon-reload
    systemctl enable --now asteriskia-lockdown 2>/dev/null \
        && log_ok "Serviço asteriskia-lockdown ativo" \
        || log_warn "Não foi possível iniciar asteriskia-lockdown — verifique 'systemctl status asteriskia-lockdown'"
else
    log_warn "asteriskia-lockdown.service não encontrado — lockdown SIP não instalado"
fi

# ── Build e subida ────────────────────────────────────────────────────────────
log_step "9. Build e inicialização dos containers"
cd "$INSTALL_DIR"

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

    if [ -n "$BASH_CMDS" ]; then
        log_info "Aplicando correções sugeridas pela IA..."
        while IFS= read -r cmd; do
            [ -z "$cmd" ] && continue
            echo -e "  ${GRAY}▶ $cmd${NC}"
            # Substitui variáveis conhecidas
            cmd="${cmd//\$INSTALL_DIR/$INSTALL_DIR}"
            cmd="${cmd//\$SERVICE/$SERVICE}"
            eval "$cmd" 2>/dev/null || true
        done <<< "$BASH_CMDS"
    fi

    # Aplica patch no Dockerfile se houver
    if [ -n "$DOCKERFILE_PATCH" ]; then
        DOCKERFILE_PATCH="${DOCKERFILE_PATCH//\$INSTALL_DIR/$INSTALL_DIR}"
        DOCKERFILE_PATCH="${DOCKERFILE_PATCH//\$SERVICE/$SERVICE}"
        eval "$DOCKERFILE_PATCH" 2>/dev/null || true
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
if docker inspect --format='{{.State.Status}}' asteriskia-caddy 2>/dev/null | grep -q running; then
    log_ok "Caddy rodando — HTTPS ativo em https://app.voiphash.com.br"
else
    log_warn "Caddy ainda não respondeu. Verifique: docker compose logs caddy"
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

check_container "asteriskia-postgres"
check_container "asteriskia-backend"
check_container "asteriskia-frontend"
check_container "asteriskia-asterisk"
check_container "asteriskia-ai-agent"

if curl -sf --max-time 10 "https://app.voiphash.com.br/api/v1/health" > /dev/null 2>&1; then
    log_ok "Backend API respondendo via HTTPS"
else
    log_warn "Backend API: não respondeu ainda (pode estar inicializando)"
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
