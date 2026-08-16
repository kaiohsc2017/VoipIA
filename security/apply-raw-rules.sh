#!/bin/bash
# VoipIA — Aplica regras nftables raw PREROUTING para isolamento de containers.
# Chamado pelo systemd após o Docker subir.
# Detecta a bridge atual da rede asteriskia_voipia-net dinamicamente.

set -e

NETWORK_NAME="asteriskia_voipia-net"
LOG_PREFIX="[asteriskia-raw-rules]"

echo "$LOG_PREFIX Detectando bridge de $NETWORK_NAME..."
NETWORK_ID=$(docker network inspect "$NETWORK_NAME" --format '{{.Id}}' 2>/dev/null | cut -c1-12)
if [ -z "$NETWORK_ID" ]; then
    echo "$LOG_PREFIX ERRO: rede $NETWORK_NAME não encontrada. Saindo."
    exit 1
fi

BRIDGE="br-${NETWORK_ID}"
echo "$LOG_PREFIX Bridge detectada: $BRIDGE"

# IPs fixos dos containers (definidos no docker-compose.yml)
CONTAINER_IPS="172.16.7.10 172.16.7.11 172.16.7.12 172.16.7.13 172.16.7.14 172.16.7.15 172.16.7.16 172.16.7.17"

echo "$LOG_PREFIX Aplicando regras raw PREROUTING..."
nft flush chain ip raw PREROUTING 2>/dev/null || true

for IP in $CONTAINER_IPS; do
    nft add rule ip raw PREROUTING iifname != "$BRIDGE" ip daddr "$IP" drop
done

# Protege a admin API do Caddy — apenas loopback
nft add rule ip raw PREROUTING iifname != "lo" meta l4proto tcp ip daddr 127.0.0.1 tcp dport 2019 drop

echo "$LOG_PREFIX Regras aplicadas com sucesso."
nft list chain ip raw PREROUTING
