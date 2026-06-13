#!/bin/bash
set -e

echo "[security] Iniciando AsteriskIA Security Container..."

# Garante que iptables está disponível
if ! command -v iptables &>/dev/null; then
    echo "[security] ERRO: iptables não encontrado"
    exit 1
fi

# Cria socket dir com permissões corretas
mkdir -p /var/run/fail2ban
chmod 750 /var/run/fail2ban

# Aguarda o log do Asterisk estar disponível
LOG_FILE="${ASTERISK_LOG_FILE:-/var/log/asterisk/full}"
echo "[security] Aguardando log do Asterisk em $LOG_FILE..."
MAX_WAIT=60
WAITED=0
while [ ! -f "$LOG_FILE" ] && [ $WAITED -lt $MAX_WAIT ]; do
    sleep 2
    WAITED=$((WAITED + 2))
done

if [ ! -f "$LOG_FILE" ]; then
    echo "[security] AVISO: log do Asterisk não encontrado em $LOG_FILE — criando placeholder"
    touch "$LOG_FILE"
fi

echo "[security] Log do Asterisk encontrado. Iniciando fail2ban..."

# Inicia fail2ban em foreground com log para stdout
exec fail2ban-server \
    --nodaemon \
    --loglevel INFO \
    --logtarget STDOUT
