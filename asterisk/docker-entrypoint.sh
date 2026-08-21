#!/bin/sh
# =============================================================
# docker-entrypoint.sh — Asterisk
# Substitui APENAS as variáveis de ambiente declaradas nos templates.
# Usa envsubst com lista explícita para não apagar variáveis
# do dialplan do Asterisk como ${EXTEN}, ${CALLERID}, etc.
# =============================================================

set -e

# Instala envsubst (gettext-base) se não disponível
if ! command -v envsubst > /dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq gettext-base \
        && rm -rf /var/lib/apt/lists/*
fi

# Variáveis que devem ser substituídas nos templates
# NUNCA adicionar variáveis do dialplan Asterisk aqui (${EXTEN}, ${CALLERID}, etc.)
VARS='${AST_AMI_USER}${AST_AMI_PASSWORD}${AST_ARI_USER}${AST_ARI_PASSWORD}${SIP_TRUNK_HOST}${SIP_TRUNK_FROM_DOMAIN}${SIP_DOMAIN}${SIP_PUBLIC_IP}${INTERNAL_API_KEY}${RAMAL_1001_PASSWORD}${RAMAL_1002_PASSWORD}${RAMAL_9001_PASSWORD}${RAMAL_9002_PASSWORD}${POSTGRES_DB}${POSTGRES_USER}${POSTGRES_PASSWORD}'

# Processa arquivos *.conf.template → *.conf
for f in /etc/asterisk/*.conf.template; do
    [ -f "$f" ] || continue
    dest="${f%.template}"
    envsubst "$VARS" < "$f" > "$dest"
    echo "[VoipIA] Template processado: $(basename $f) → $(basename $dest)"
done

# Achado de auditoria (hardening não-root): as portas do Asterisk (5060, 8088, RTP
# 16000-16500) estão todas acima de 1024 — não exigem privilégio nenhum para bind, só
# corrigir a posse dos caminhos que o processo precisa escrever. Roda ainda como root aqui
# (chown -R exige) porque /var/spool/asterisk e /var/log/asterisk são volume nomeado cujo
# conteúdo pode ter sido criado por uma versão anterior do container rodando como root —
# /etc/asterisk (bind mount do host) já vem preparado com o grupo compartilhado GID 1500.
chown -R asterisk:asterisk /var/spool/asterisk /var/log/asterisk /var/run/asterisk 2>/dev/null || true

echo "[VoipIA] Iniciando Asterisk como usuário não-root (asterisk)..."
exec setpriv --reuid=asterisk --regid=asterisk --init-groups "$@"
