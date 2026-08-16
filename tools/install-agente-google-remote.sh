#!/usr/bin/env bash
# Instala o agente-google.py (variante Google Gemini) em uma nova VPS,
# com banco de memória PostgreSQL próprio e comando global no PATH.
#
# Uso (execute NA VPS de destino, como root ou via sudo):
#   ./install-agente-google-remote.sh
#
# Pré-requisitos: docker instalado, script agente-google.py já copiado
# para o mesmo diretório deste instalador (scp -r tools/ vps-nova:/opt/VoipIA/tools/).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_PY="$SCRIPT_DIR/agente-google.py"
INSTALL_DIR="${VOIPIA_AGENT_DIR:-/opt/asteriskia-agent}"
DB_CONTAINER="asteriskia-agent-memory"
DB_VOLUME="asteriskia-agent-memory-data"
DB_PASS_FILE="$INSTALL_DIR/.db_password"

if [ ! -f "$AGENT_PY" ]; then
    echo "❌  agente-google.py não encontrado em $SCRIPT_DIR — copie o tools/ completo antes de rodar este instalador."
    exit 1
fi

echo "▶ Instalando dependências Python…"
pip3 install --break-system-packages -q google-genai psycopg2-binary 2>/dev/null \
    || pip3 install -q google-genai psycopg2-binary

echo "▶ Preparando diretório de instalação: $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp "$AGENT_PY" "$INSTALL_DIR/agente-google.py"

echo "▶ Subindo PostgreSQL dedicado à memória do agente (container: $DB_CONTAINER)…"
if ! docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
    if [ ! -f "$DB_PASS_FILE" ]; then
        openssl rand -base64 24 > "$DB_PASS_FILE"
        chmod 600 "$DB_PASS_FILE"
    fi
    DB_PASS="$(cat "$DB_PASS_FILE")"
    docker volume create "$DB_VOLUME" >/dev/null
    docker run -d \
        --name "$DB_CONTAINER" \
        --restart unless-stopped \
        -e POSTGRES_DB=asteriskia_agent \
        -e POSTGRES_USER=asteriskia_agent \
        -e POSTGRES_PASSWORD="$DB_PASS" \
        -p 127.0.0.1:5434:5432 \
        -v "$DB_VOLUME:/var/lib/postgresql/data" \
        postgres:16-alpine >/dev/null
    echo "  Aguardando Postgres iniciar…"
    for i in $(seq 1 30); do
        docker exec "$DB_CONTAINER" pg_isready -U asteriskia_agent >/dev/null 2>&1 && break
        sleep 1
    done
else
    echo "  Container $DB_CONTAINER já existe — reaproveitando."
    DB_PASS="$(cat "$DB_PASS_FILE")"
fi

if [ ! -f "$INSTALL_DIR/.env" ]; then
    read -rp "▶ Cole a GEMINI_API_KEY: " GEMINI_KEY
    cat > "$INSTALL_DIR/.env" <<EOF
GEMINI_API_KEY=$GEMINI_KEY
GEMINI_MODEL_LLM=gemini-2.5-flash
DB_HOST=127.0.0.1
DB_PORT=5434
DB_NAME=asteriskia_agent
DB_USER=asteriskia_agent
DB_PASS=$DB_PASS
EOF
    chmod 600 "$INSTALL_DIR/.env"
    echo "  .env criado em $INSTALL_DIR/.env (chmod 600)."
else
    echo "  .env já existe em $INSTALL_DIR/.env — não sobrescrito."
fi

echo "▶ Criando comando global 'asteriskia-agent'…"
cat > /usr/local/bin/asteriskia-agent <<EOF
#!/bin/bash
export VOIPIA_DIR="$INSTALL_DIR"
cd "$INSTALL_DIR"
python3 agente-google.py "\$@"
EOF
chmod +x /usr/local/bin/asteriskia-agent

echo ""
echo "✅ Instalado. Rode 'asteriskia-agent' de qualquer diretório para iniciar."
echo "   Memória isolada nesta VPS — banco em $DB_CONTAINER (porta 5434, somente localhost)."
