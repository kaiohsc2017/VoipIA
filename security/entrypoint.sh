#!/bin/bash
set -e

echo "[security] Iniciando AsteriskIA Security Container..."

if ! command -v iptables &>/dev/null; then
    echo "[security] ERRO: iptables não encontrado"; exit 1
fi

# Limpa socket antigo do fail2ban (evita "unexpected state" error)
rm -f /var/run/fail2ban/fail2ban.sock

mkdir -p /var/run/fail2ban
chmod 750 /var/run/fail2ban

# Diretório de comandos iptables do backend
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

# Watcher de comandos em background — independente do fail2ban
(while true; do
    for f in "$CMD_DIR"/*.cmd; do
        [ -f "$f" ] || continue
        echo "[security-watcher] Executando: $f"
        bash "$f" > "${f%.cmd}.out" 2>&1
        echo "[security-watcher] Resultado: $(cat ${f%.cmd}.out)"
        rm -f "$f"
    done
    sleep 1
done) &

# Watcher de lockdown persistente — reaaplica iptables após restart
LOCKDOWN_SCRIPT="$CMD_DIR/lockdown-persistent.sh"
(while true; do
    sleep 10
    if [ -f "$LOCKDOWN_SCRIPT" ]; then
        # Verifica se a chain ainda existe
        if ! iptables -L ASTERISK_WHITELIST &>/dev/null 2>&1; then
            echo "[security-watcher] Chain perdida, reaplicando lockdown..."
            bash "$LOCKDOWN_SCRIPT" 2>/dev/null || true
        fi
    fi
done) &

exec fail2ban-server \
    -f \
    --loglevel INFO \
    --logtarget STDOUT
