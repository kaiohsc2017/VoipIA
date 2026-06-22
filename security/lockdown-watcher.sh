#!/bin/bash
# AsteriskIA — Lockdown Watcher (roda no HOST via systemd)
CMD_DIR="/var/run/asteriskia-security"
mkdir -p "$CMD_DIR"
echo "[lockdown-watcher] Iniciado"

while true; do
    # Executa scripts .cmd pendentes
    for f in "$CMD_DIR"/*.cmd; do
        [ -f "$f" ] || continue
        echo "[lockdown-watcher] Executando: $f"
        bash "$f" > "${f%.cmd}.out" 2>&1
        echo "[lockdown-watcher] Concluído: $(tail -1 ${f%.cmd}.out)"
        rm -f "$f"
    done

    # Reaaplica lockdown se as regras sumiram (ex: restart do Docker)
    PERSISTENT="$CMD_DIR/lockdown-persistent.sh"
    if [ -f "$PERSISTENT" ]; then
        if ! iptables-legacy -L DOCKER-USER 2>/dev/null | grep -q "DROP.*dpt:sip"; then
            echo "[lockdown-watcher] Regras perdidas, reaplicando lockdown..."
            bash "$PERSISTENT" > /tmp/lockdown-reapply.out 2>&1
        fi
    fi

    sleep 2
done
