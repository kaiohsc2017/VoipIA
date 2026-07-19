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

# ---------- Stage 2: Build da SPA de Insights ----------
FROM node:22-alpine AS insights-builder

WORKDIR /app

COPY insights-platform/frontend/package.json insights-platform/frontend/package-lock.json* ./
RUN npm ci --silent

COPY insights-platform/frontend/ .

# Mesma API do backend Java usada pelo Telecom — a SPA de Insights reusa
# /api/v1/insights/** no mesmo backend Spring Boot (sem backend próprio).
ARG VITE_API_URL=https://app.voiphash.com.br/api/v1
ENV VITE_API_URL=$VITE_API_URL

RUN npm run build

# ---------- Stage 3: Build da SPA de Agentes ----------
FROM node:22-alpine AS agents-builder

WORKDIR /app

COPY agents-platform/frontend/package.json agents-platform/frontend/package-lock.json* ./
RUN npm ci --silent

COPY agents-platform/frontend/ .

# VITE_API_URL aponta pro backend Telecom (login + streaming-token do WS de
# alertas) — o backend próprio de Agentes (FastAPI) é acessado via
# VITE_AGENTS_API_URL, default '/agents' (relativo, mesma origem, atrás do
# Caddy @agents-api) — não precisa de build arg em produção.
ARG VITE_API_URL=https://app.voiphash.com.br/api/v1
ENV VITE_API_URL=$VITE_API_URL

RUN npm run build

# ---------- Stage 4: Nginx ----------
FROM nginx:1.27-alpine

# Remove default config
RUN rm -f /etc/nginx/conf.d/default.conf

# Copia config customizada (SPA fallback + proxy reverso)
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copia build estático do Telecom
COPY --from=builder /app/dist /usr/share/nginx/html

# Copia build da SPA de Insights
COPY --from=insights-builder /app/dist /usr/share/nginx/html/insights

# Copia build da SPA de Agentes
COPY --from=agents-builder /app/dist /usr/share/nginx/html/agents

# Achado de segurança (débito aceito): master process do nginx rodava como
# root (necessário só pra bindar a porta 80, <1024). Roda como o usuário
# "nginx" (já existe na imagem oficial, UID 101) — precisa de
# CAP_NET_BIND_SERVICE (adicionado no docker-compose.yml) pra ainda
# conseguir bindar a porta privilegiada sem ser root. Diretórios de
# runtime (cache/pid/log) precisam de chown explícito porque por padrão
# só o processo root os cria/possui na inicialização.
RUN chown -R nginx:nginx /var/cache/nginx /var/log/nginx /usr/share/nginx/html \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
USER nginx

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
