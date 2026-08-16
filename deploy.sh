#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Deploy VoipIA na VPS
# Puxa o código mais recente e reinicia apenas os containers afetados.
#
# Uso:
#   bash deploy.sh           → atualiza e reinicia todo o stack
#   bash deploy.sh --build   → força rebuild de todas as imagens
# =============================================================================
set -euo pipefail

APP_DIR="/opt/VoipIA"
FORCE_BUILD="${1:-}"

log()  { echo -e "\n\033[1;34m[$(date '+%H:%M:%S')]\033[0m $*"; }
ok()   { echo -e "\033[0;32m✔\033[0m $*"; }
fail() { echo -e "\033[0;31m✖\033[0m $*"; exit 1; }

log "Deploy VoipIA — $(date '+%d/%m/%Y %H:%M:%S')"

# ── 1. Atualiza o código ──────────────────────────────────────────────────────
log "1/3 · Atualizando repositório..."
cd "$APP_DIR"
git pull origin main
ok "Repositório atualizado"

# ── 2. Sobe o stack ───────────────────────────────────────────────────────────
log "2/3 · Subindo stack Docker..."
if [ "$FORCE_BUILD" = "--build" ]; then
    docker compose up -d --build
else
    # Pull de imagens externas (postgres, caddy) + rebuild das locais alteradas
    docker compose up -d --build
fi
ok "Stack subindo"

# ── 3. Aguarda healthchecks ───────────────────────────────────────────────────
log "3/3 · Verificando serviços..."
sleep 5
docker compose ps --format "table {{.Name}}\t{{.Status}}"

log "Deploy concluído · https://app.voiphash.com.br"
