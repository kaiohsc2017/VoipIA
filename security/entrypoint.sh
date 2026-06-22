#!/bin/bash
set -e

echo "[security] Iniciando AsteriskIA Security Container..."

# Limpa socket antigo do fail2ban
rm -f /var/run/fail2ban/fail2ban.sock
mkdir -p /var/run/fail2ban
chmod 750 /var/run/fail2ban

CMD_DIR="/var/run/asteriskia-security"
mkdir -p "$CMD_DIR"
chmod 777 "$CMD_DIR"

LOG_FILE="${ASTERISK_LOG_FILE:-/var/log/asterisk/full}"
echo "[security] Aguardando log do Asterisk em $LOG_FILE..."
MAX_WAIT=60; WAITED=0
while [ ! -f "$LOG_FILE" ] && [ $WAITED -lt $MAX_WAIT ]; do
    sleep 2; WAITED=$((WAITED + 2))
done
[ ! -f "$LOG_FILE" ] && touch "$LOG_FILE"

echo "[security] Iniciando fail2ban e watcher de comandos..."

# Watcher de comandos .cmd
(while true; do
    for f in "$CMD_DIR"/*.cmd; do
        [ -f "$f" ] || continue
        echo "[security-watcher] Executando: $f"
        bash "$f" > "${f%.cmd}.out" 2>&1
        echo "[security-watcher] OK: $(tail -1 ${f%.cmd}.out)"
        rm -f "$f"
    done
    sleep 1
done) &

# Watcher de lockdown persistente — reaaplica nftables após restart
(while true; do
    sleep 15
    PERSISTENT="$CMD_DIR/lockdown-persistent.sh"
    if [ -f "$PERSISTENT" ]; then
        if ! nft list table ip asteriskia &>/dev/null 2>&1; then
            echo "[security-watcher] Tabela nft perdida, reaplicando lockdown..."
            bash "$PERSISTENT" > /tmp/nft-reapply.out 2>&1 || true
        fi
    fi
done) &

# Aplica lockdown imediatamente ao iniciar se havia lockdown ativo
PERSISTENT="$CMD_DIR/lockdown-persistent.sh"
if [ -f "$PERSISTENT" ]; then
    echo "[security] Reaplicando lockdown após restart..."
    bash "$PERSISTENT" > /tmp/nft-startup.out 2>&1 || true
    echo "[security] Lockdown reaplicado"
fi

exec fail2ban-server \
    -f \
    --loglevel INFO \
    --logtarget STDOUT
