#!/bin/bash
set -e

echo "[security] Iniciando VoipIA Security Container..."

# Débito de segurança corrigido: GID compartilhado 1500 (grupo "voipia-app" no
# host) via setgid nos diretórios — backend (não-root, mesmo GID) acessa o socket do
# fail2ban e a fila de comandos sem precisar de 777 nem de root. Setgid garante que
# QUALQUER processo (mesmo root, como o próprio fail2ban-server/watcher aqui) que criar
# um arquivo novo dentro herda o grupo do diretório — não depende do GID de quem escreve.

# Limpa socket antigo do fail2ban
rm -f /var/run/fail2ban/fail2ban.sock
mkdir -p /var/run/fail2ban
chown root:1500 /var/run/fail2ban
chmod 2770 /var/run/fail2ban

CMD_DIR="/var/run/voipia-security"
mkdir -p "$CMD_DIR"
chown root:1500 "$CMD_DIR"
chmod 2770 "$CMD_DIR"

# fail2ban-server cria o .sock com permissão própria (700) sem herdar bits rw de
# grupo do diretório — corrige em background assim que o socket aparecer, sem
# atrasar o boot do fail2ban-server (exec abaixo).
(
    for _ in $(seq 1 30); do
        if [ -S /var/run/fail2ban/fail2ban.sock ]; then
            chmod 660 /var/run/fail2ban/fail2ban.sock
            break
        fi
        sleep 1
    done
) &

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
