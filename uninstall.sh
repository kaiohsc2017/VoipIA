#!/usr/bin/env bash
# =============================================================================
# uninstall.sh — Remove o VoipIA por completo do servidor
# =============================================================================
# Uso: quando o cliente desiste do sistema — mais rápido e seguro do que
# formatar o servidor inteiro, especialmente se ele tiver outros serviços
# rodando (este script NUNCA mexe em nada que não seja do VoipIA).
#
# Remove:
#   - Containers, rede e volumes Docker do projeto (inclui banco de dados,
#     gravações de chamadas, base de conhecimento — TUDO é perdido)
#   - Imagens Docker construídas pelo projeto (asteriskia-*)
#   - O diretório /opt/VoipIA inteiro (código, .env, credenciais)
#   - O serviço systemd voipia-lockdown
#   - Regras nftables de isolamento/lockdown criadas por este projeto
#
# NÃO remove (podem ser usados por outros serviços neste servidor):
#   - Docker Engine em si
#   - Regras de UFW (portas podem ser compartilhadas) — a menos que
#     --remove-firewall-rules seja passado explicitamente
#   - Imagens base compartilhadas (postgres, nginx, caddy, coturn)
#   - Qualquer outro container/serviço não relacionado ao VoipIA
#
# Uso:
#   sudo bash uninstall.sh                       → interativo, com backup
#   sudo bash uninstall.sh --dry-run             → mostra o que seria feito, sem remover nada
#   sudo bash uninstall.sh --no-backup           → pula o backup final (dump do banco + .env)
#   sudo bash uninstall.sh --force               → pula a confirmação interativa (automação)
#   sudo bash uninstall.sh --remove-firewall-rules → também remove as regras de UFW do
#                                                     VoipIA (só use se o VPS for dedicado)
# =============================================================================
set -uo pipefail
# Sem -e: remoção é best-effort — uma etapa falhar não deve impedir as demais.

INSTALL_DIR="/opt/VoipIA"
BACKUP_DIR="/root/asteriskia-uninstall-backup-$(date +%Y%m%d-%H%M%S)"

DRY_RUN=false
NO_BACKUP=false
FORCE=false
REMOVE_FW=false
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --no-backup) NO_BACKUP=true ;;
        --force) FORCE=true ;;
        --remove-firewall-rules) REMOVE_FW=true ;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Argumento desconhecido: $arg (use --help)"; exit 1 ;;
    esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'; BOLD='\033[1m'
log_ok()   { echo -e "${GREEN}✔${NC} $1"; }
log_info() { echo -e "${CYAN}→${NC} $1"; }
log_warn() { echo -e "${YELLOW}⚠${NC} $1"; }
log_err()  { echo -e "${RED}✖${NC} $1"; }
log_step() { echo -e "\n${BOLD}══════════════════════════════════════════${NC}"; echo -e "${BOLD} $1${NC}"; echo -e "${BOLD}══════════════════════════════════════════${NC}"; }

# Executa (ou só mostra, em --dry-run) um comando destrutivo. Nunca aborta o
# script se o comando falhar — remoção é best-effort, uma etapa faltando não
# deve impedir a limpeza das demais.
run() {
    if [ "$DRY_RUN" = true ]; then
        echo -e "  ${YELLOW}[dry-run]${NC} $*"
    else
        echo -e "  ${CYAN}▶${NC} $*"
        eval "$*" || log_warn "Comando falhou (continuando): $*"
    fi
}

[ "$(id -u)" -eq 0 ] || { log_err "Execute como root: sudo bash uninstall.sh"; exit 1; }

echo -e "${BOLD}${RED}"
cat << 'BANNER'
    ___         __           _      __    _____
   /   |  _____/ /____  _____(_)____/ /__ /  _/  ___
  / /| | / ___/ __/ _ \/ ___/ / ___/ //_/ / /   / _ \
 / ___ |(__  ) /_/  __/ /  / (__  ) ,<  _/ /   / ___/
/_/  |_/____/\__/\___/_/  /_/____/_/|_|/___/   /_/

  DESINSTALADOR — remove o VoipIA deste servidor
BANNER
echo -e "${NC}"

if [ ! -d "$INSTALL_DIR" ]; then
    log_err "Instalação não encontrada em $INSTALL_DIR. Nada a remover."
    exit 1
fi

log_step "O que será removido"
echo -e "  • Containers, rede e volumes Docker do VoipIA (dados de chamadas, gravações,"
echo -e "    banco de dados, base de conhecimento — tudo incluído)"
echo -e "  • Imagens Docker construídas pelo projeto (ex: ${CYAN}voipia-backend${NC}) — imagens base"
echo -e "    compartilhadas (postgres, nginx, caddy, coturn) ${BOLD}não${NC} são removidas"
echo -e "  • O diretório ${CYAN}${INSTALL_DIR}${NC} inteiro (código, .env, credenciais)"
echo -e "  • O serviço systemd ${CYAN}voipia-lockdown${NC}"
echo -e "  • Regras nftables de isolamento/lockdown criadas por este projeto"
echo ""
echo -e "${BOLD}O que ${RED}NÃO${NC}${BOLD} será removido${NC} (podem ser usados por outros serviços neste servidor):"
echo -e "  • Docker Engine em si"
echo -e "  • Regras de UFW (80/443/5060/8088/RTP/TURN) — use ${CYAN}--remove-firewall-rules${NC} se quiser removê-las também"
echo -e "  • Qualquer outro container/serviço não relacionado ao VoipIA"
echo ""

if [ "$NO_BACKUP" = false ] && [ "$DRY_RUN" = false ]; then
    log_info "Um backup final (dump do banco + .env) será salvo em ${CYAN}${BACKUP_DIR}${NC} antes da remoção."
    log_info "Use --no-backup pra pular essa etapa."
fi

if [ "$FORCE" = false ] && [ "$DRY_RUN" = false ]; then
    echo ""
    log_warn "Esta ação é IRREVERSÍVEL. Todos os dados (chamadas, gravações, configurações) serão perdidos."
    printf "  Digite exatamente ${BOLD}REMOVER${NC} para confirmar: "
    read -r CONFIRM
    if [ "$CONFIRM" != "REMOVER" ]; then
        log_info "Cancelado pelo operador. Nada foi removido."
        exit 0
    fi
fi

# ── 1. Backup final ───────────────────────────────────────────────────────────
log_step "1. Backup final"
if [ "$NO_BACKUP" = true ]; then
    log_warn "Pulado (--no-backup)"
elif [ "$DRY_RUN" = true ]; then
    echo -e "  ${YELLOW}[dry-run]${NC} criaria backup em $BACKUP_DIR (dump do Postgres + .env)"
else
    mkdir -p "$BACKUP_DIR"
    if docker inspect voipia-postgres &>/dev/null; then
        log_info "Gerando dump do PostgreSQL..."
        if docker exec voipia-postgres pg_dumpall -U asteriskia 2>/dev/null | gzip > "$BACKUP_DIR/postgres_dump.sql.gz"; then
            log_ok "Dump salvo: $BACKUP_DIR/postgres_dump.sql.gz"
        else
            log_warn "Não foi possível gerar o dump do Postgres (container parado ou credenciais indisponíveis)"
        fi
    else
        log_warn "Container voipia-postgres não encontrado — sem dump de banco"
    fi
    if [ -f "$INSTALL_DIR/env/.env" ]; then
        cp "$INSTALL_DIR/env/.env" "$BACKUP_DIR/.env.bak"
        log_ok ".env copiado (credenciais preservadas)"
    fi
    log_ok "Backup salvo em: $BACKUP_DIR"
fi

# ── 2. Stack Docker: containers, rede, volumes ────────────────────────────────
log_step "2. Removendo stack Docker (containers + rede + volumes)"
if [ -f "$INSTALL_DIR/docker-compose.yml" ]; then
    run "cd '$INSTALL_DIR' && docker compose down -v --remove-orphans -t 30"
else
    log_warn "docker-compose.yml não encontrado — pulando docker compose down"
fi

# Segurança extra: remove por nome qualquer container asteriskia-* que tenha sobrado
LEFTOVER=$(docker ps -aq --filter "name=asteriskia-" 2>/dev/null || true)
if [ -n "$LEFTOVER" ]; then
    run "docker rm -f $LEFTOVER"
fi

# ── 3. Imagens construídas pelo projeto ───────────────────────────────────────
log_step "3. Removendo imagens Docker do projeto"
IMAGES=$(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep '^asteriskia-' || true)
if [ -n "$IMAGES" ]; then
    for img in $IMAGES; do
        run "docker rmi -f '$img'"
    done
else
    log_info "Nenhuma imagem asteriskia-* encontrada"
fi

# ── 4. Serviço systemd de lockdown ────────────────────────────────────────────
log_step "4. Removendo serviço de lockdown SIP"
if systemctl list-unit-files 2>/dev/null | grep -q voipia-lockdown; then
    run "systemctl disable --now voipia-lockdown"
    run "rm -f /etc/systemd/system/voipia-lockdown.service"
    run "systemctl daemon-reload"
else
    log_info "Serviço voipia-lockdown não está instalado"
fi

# ── 5. Regras nftables criadas pelo projeto ───────────────────────────────────
log_step "5. Limpando regras nftables"
if command -v nft &>/dev/null; then
    # Isolamento de containers (raw PREROUTING) — chain dedicada ao VoipIA
    # (security/apply-raw-rules.sh), sempre segura de esvaziar.
    run "nft flush chain ip raw PREROUTING 2>/dev/null"
    # Lockdown SIP (DOCKER-USER) — só toca se o lockdown estava ativo, e com a
    # MESMA ação que a própria aplicação usa pra desativar o lockdown
    # (AsteriskAclService.removeLockdownIptables) — evita mexer em regras de
    # outros serviços que porventura também usem essa chain padrão do Docker.
    if [ -f "$INSTALL_DIR/security/state/lockdown.flag" ]; then
        log_warn "Lockdown SIP estava ativo — limpando chain DOCKER-USER"
        run "nft flush chain ip filter DOCKER-USER 2>/dev/null"
    fi
else
    log_info "nft não encontrado — pulando limpeza de nftables"
fi

# ── 6. Regras de firewall (UFW) — opcional ────────────────────────────────────
log_step "6. Regras de firewall (UFW)"
if [ "$REMOVE_FW" = true ]; then
    log_warn "Removendo regras de UFW específicas do VoipIA (--remove-firewall-rules)"
    for rule in "80/tcp" "443/tcp" "443/udp" "5060/udp" "5060/tcp" "8088/tcp" \
                "16000:16500/udp" "3478/udp" "3478/tcp" "5349/tcp" "5349/udp" \
                "49152:49652/udp"; do
        run "ufw delete allow $rule"
    done
else
    log_info "Regras de UFW preservadas — podem ser usadas por outros serviços deste servidor."
    log_info "Pra remover manualmente depois: ufw delete allow 5060/udp (repita pras demais portas do VoipIA)"
fi

# ── 7. Diretório da instalação ────────────────────────────────────────────────
log_step "7. Removendo ${INSTALL_DIR}"
cd /
run "rm -rf '$INSTALL_DIR'"

# ── Resumo ────────────────────────────────────────────────────────────────────
log_step "Concluído"
if [ "$DRY_RUN" = true ]; then
    log_info "Modo --dry-run: nada foi removido de verdade. Rode sem --dry-run para executar."
else
    log_ok "VoipIA removido do servidor."
    [ "$NO_BACKUP" = false ] && log_ok "Backup final disponível em: $BACKUP_DIR"
    [ "$REMOVE_FW" = false ] && log_info "Regras de UFW do VoipIA continuam ativas — remova manualmente se necessário."
    log_info "Docker Engine não foi removido (pode ser usado por outros serviços deste servidor)."
fi
