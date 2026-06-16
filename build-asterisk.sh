#!/bin/bash
# =============================================================
# build-asterisk.sh — Build customizado do AsteriskIA
# Uso: ./build-asterisk.sh
# Recomendado rodar em tmux ou com nohup
# =============================================================

set -e

echo "[AsteriskIA] Iniciando build customizado do Asterisk..."

cd "$(dirname "$0")"

# Limpa imagem antiga (se existir)
docker rmi apptelecom-asterisk 2>/dev/null || true

# Build
docker compose build --no-cache asterisk

echo "[AsteriskIA] Build finalizado com sucesso!"
echo "[AsteriskIA] Para subir o container: docker compose up -d asterisk"