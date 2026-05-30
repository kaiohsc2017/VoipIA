#!/bin/sh
# =============================================================
# docker-entrypoint.sh — Asterisk
# Substitui variáveis de ambiente nos arquivos de configuração
# antes de iniciar o Asterisk.
# =============================================================

set -e

# Substitui variáveis de ambiente em arquivos de configuração
# usando envsubst para evitar edição manual de configs
for f in /etc/asterisk/*.conf; do
    envsubst < "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done

echo "[AsteriskIA] Iniciando Asterisk..."
exec "$@"
