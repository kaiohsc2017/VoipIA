# =============================================================
# Frontend Dockerfile — Multi-stage build
# Contexto de build: raiz do repositório (definido no docker-compose.yml)
# Necessário para copiar agents-platform/frontend no stage 2.
# =============================================================

# ---------- Stage 1: Build do React ----------
FROM node:22-alpine AS builder

WORKDIR /app

# Instala dependências primeiro (cache layer)
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --silent

# Copia código-fonte do frontend e builda
COPY frontend/ .

# Variáveis de build injetadas pelo docker-compose (via .env)
# ⚠️  Todas as variáveis VITE_ são resolvidas em BUILD TIME.
ARG VITE_API_URL=https://app.voiphash.com.br/api/v1
ENV VITE_API_URL=$VITE_API_URL

ARG VITE_ASTERISK_WS=wss://app.voiphash.com.br/asterisk-ws
ENV VITE_ASTERISK_WS=$VITE_ASTERISK_WS

ARG VITE_SIP_URI=sip:9001@app.voiphash.com.br
ENV VITE_SIP_URI=$VITE_SIP_URI

ARG VITE_SIP_PASSWORD=webrtc9001pass
ENV VITE_SIP_PASSWORD=$VITE_SIP_PASSWORD

ARG VITE_STUN_URL=stun:stun.l.google.com:19302
ENV VITE_STUN_URL=$VITE_STUN_URL

ARG VITE_TURN_URL=
ENV VITE_TURN_URL=$VITE_TURN_URL

ARG VITE_TURN_USER=asteriskia
ENV VITE_TURN_USER=$VITE_TURN_USER

ARG VITE_TURN_CREDENTIAL=
ENV VITE_TURN_CREDENTIAL=$VITE_TURN_CREDENTIAL

RUN npm run build

# ---------- Stage 2: Nginx ----------
FROM nginx:1.27-alpine

# Remove default config
RUN rm -f /etc/nginx/conf.d/default.conf

# Copia config customizada (SPA fallback + proxy reverso)
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copia build estático do Telecom
COPY --from=builder /app/dist /usr/share/nginx/html

# Copia arquivos estáticos da plataforma de Agentes
COPY agents-platform/frontend/index.html /usr/share/nginx/html/agents/index.html
COPY agents-platform/frontend/docs.html  /usr/share/nginx/html/agents/docs.html
COPY agents-platform/frontend/js         /usr/share/nginx/html/agents/js

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
