#!/bin/sh
# =============================================================
# docker-entrypoint.sh — Asterisk
# Substitui variáveis de ambiente nos arquivos de configuração
# antes de iniciar o Asterisk.
# =============================================================

set -e

# Instala envsubst (gettext-base) se não disponível
if ! command -v envsubst > /dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq gettext-base \
        && rm -rf /var/lib/apt/lists/*
fi

# Substitui variáveis de ambiente em arquivos de configuração
for f in /etc/asterisk/*.conf; do
    envsubst < "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done

echo "[AsteriskIA] Iniciando Asterisk..."
exec "$@"
