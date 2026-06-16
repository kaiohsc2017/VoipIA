#!/usr/bin/env bash
# ============================================================
# deploy.sh — Deploy AsteriskIA na VPS
# Execute diretamente na VPS: bash deploy.sh
# ============================================================
set -euo pipefail

APP_DIR="/opt/AsteriskIA"
BACKEND_DIR="$APP_DIR/backend"
FRONTEND_DIR="$APP_DIR/frontend"
FRONTEND_DIST="$FRONTEND_DIR/dist"
NGINX_WEBROOT="/var/www/asteriskia"   # ajuste se necessário
BACKEND_SERVICE="apptelecom-backend"
JAVA_JAR="$BACKEND_DIR/target/apptelecom-backend-*.jar"

echo "======================================================"
echo "  Deploy AsteriskIA — $(date '+%d/%m/%Y %H:%M:%S')"
echo "======================================================"

# --- 1. Git pull ---
echo ""
echo "[1/5] Atualizando código..."
cd "$APP_DIR"
git pull origin main

# --- 2. Build backend ---
echo ""
echo "[2/5] Compilando backend Spring Boot..."
cd "$BACKEND_DIR"
./mvnw clean package -DskipTests -q
echo "     Backend compilado com sucesso."

# --- 3. Reiniciar serviço backend ---
echo ""
echo "[3/5] Reiniciando serviço backend..."
if systemctl is-active --quiet "$BACKEND_SERVICE"; then
  systemctl restart "$BACKEND_SERVICE"
else
  systemctl start "$BACKEND_SERVICE"
fi
sleep 3
systemctl is-active "$BACKEND_SERVICE" && echo "     Serviço backend: RODANDO ✓" || echo "     ATENÇÃO: backend não iniciou — verifique 'journalctl -u $BACKEND_SERVICE -n 30'"

# --- 4. Copiar frontend (dist já compilado e commitado) ---
echo ""
echo "[4/5] Publicando frontend..."
if [ -d "$NGINX_WEBROOT" ]; then
  rm -rf "$NGINX_WEBROOT"/*
  cp -r "$FRONTEND_DIST"/. "$NGINX_WEBROOT"/
  echo "     Frontend copiado para $NGINX_WEBROOT ✓"
else
  echo "     Diretório $NGINX_WEBROOT não existe — tentando localizar webroot nginx..."
  # Tenta detectar webroot pelo nginx.conf
  DETECTED=$(grep -r 'root ' /etc/nginx/sites-enabled/ /etc/nginx/conf.d/ 2>/dev/null | grep -v '#' | awk '{print $2}' | tr -d ';' | head -1)
  if [ -n "$DETECTED" ]; then
    echo "     Webroot detectado: $DETECTED"
    rm -rf "$DETECTED"/*
    cp -r "$FRONTEND_DIST"/. "$DETECTED"/
    echo "     Frontend copiado ✓"
  else
    echo "     ATENÇÃO: não foi possível detectar o webroot. Copie manualmente:"
    echo "     cp -r $FRONTEND_DIST/. /seu/webroot/"
  fi
fi

# --- 5. Recarregar nginx ---
echo ""
echo "[5/5] Recarregando nginx..."
nginx -t 2>&1 && systemctl reload nginx && echo "     Nginx recarregado ✓"

echo ""
echo "======================================================"
echo "  Deploy concluído! $(date '+%d/%m/%Y %H:%M:%S')"
echo "  Acesse: https://app.voiphash.com.br"
echo "======================================================"
