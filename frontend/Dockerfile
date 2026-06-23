# =============================================================
# Frontend Dockerfile — Multi-stage build
# Stage 1: Node.js build
# Stage 2: Nginx serve
# =============================================================

# ---------- Stage 1: Build ----------
FROM node:22-alpine AS builder

WORKDIR /app

# Instala dependências primeiro (cache layer)
COPY package.json package-lock.json* ./
RUN npm ci --silent

# Copia restante e builda
COPY . .

# Variáveis de build injetadas pelo docker-compose (via .env)
# ⚠️  Todas as variáveis VITE_ precisam ser ARGs/ENVs — são resolvidas em BUILD TIME,
#    não em runtime. Sem isso, o valor padrão do código é usado (ex: localhost).
ARG VITE_API_URL=http://localhost:8080/api/v1
ENV VITE_API_URL=$VITE_API_URL

ARG VITE_ASTERISK_WS=ws://localhost:8088/ws
ENV VITE_ASTERISK_WS=$VITE_ASTERISK_WS

ARG VITE_SIP_URI=sip:9001@localhost
ENV VITE_SIP_URI=$VITE_SIP_URI

ARG VITE_SIP_PASSWORD=webrtc9001pass
ENV VITE_SIP_PASSWORD=$VITE_SIP_PASSWORD

ARG VITE_STUN_URL=stun:stun.l.google.com:19302
ENV VITE_STUN_URL=$VITE_STUN_URL

RUN npm run build

# ---------- Stage 2: Nginx ----------
FROM nginx:1.27-alpine

# Remove default config
RUN rm -f /etc/nginx/conf.d/default.conf

# Copia config customizada (SPA fallback)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copia build estático do Telecom
COPY --from=builder /app/dist /usr/share/nginx/html

# Copia arquivos estáticos da plataforma de Agentes
# nginx.conf da pasta agents é excluído — não deve ser servido como arquivo estático
COPY agents-platform/frontend/index.html /usr/share/nginx/html/agents/index.html
COPY agents-platform/frontend/docs.html  /usr/share/nginx/html/agents/docs.html
COPY agents-platform/frontend/js         /usr/share/nginx/html/agents/js

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
