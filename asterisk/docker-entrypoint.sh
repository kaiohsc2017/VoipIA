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

# 1. Processa arquivos *.conf.template → *.conf
#    Ex: pjsip.conf.template → pjsip.conf
for f in /etc/asterisk/*.conf.template; do
    [ -f "$f" ] || continue
    dest="${f%.template}"
    envsubst < "$f" > "$dest"
    echo "[AsteriskIA] Template processado: $(basename $f) → $(basename $dest)"
done

# 2. Processa arquivos *.conf que não têm template (sem substituição de vars)
#    Esses já estão prontos — não precisa de envsubst
#    (apenas garante que existam no diretório)

echo "[AsteriskIA] Iniciando Asterisk..."
exec "$@"
