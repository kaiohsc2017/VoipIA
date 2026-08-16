#!/bin/bash
# VoipIA — Lockdown Watcher (roda no HOST via systemd)
CMD_DIR="/var/run/voipia-security"
mkdir -p "$CMD_DIR"
echo "[lockdown-watcher] Iniciado"

while true; do
    for f in "$CMD_DIR"/*.cmd; do
        [ -f "$f" ] || continue
        echo "[lockdown-watcher] Executando: $f"
        bash "$f" > "${f%.cmd}.out" 2>&1
        echo "[lockdown-watcher] OK: $(tail -1 ${f%.cmd}.out)"
        rm -f "$f"
    done

    # Reaaplica lockdown se regras sumiram (ex: restart Docker)
    PERSISTENT="$CMD_DIR/lockdown-persistent.sh"
    if [ -f "$PERSISTENT" ]; then
        if ! nft list chain ip filter DOCKER-USER 2>/dev/null | grep -q "dport 5060 drop"; then
            echo "[lockdown-watcher] Regras perdidas, reaplicando..."
            bash "$PERSISTENT" > /tmp/lockdown-reapply.out 2>&1
        fi
    fi

    sleep 2
done
