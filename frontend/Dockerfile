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
ARG VITE_API_URL=http://localhost:8080/api/v1
ENV VITE_API_URL=$VITE_API_URL

RUN npm run build

# ---------- Stage 2: Nginx ----------
FROM nginx:1.27-alpine

# Remove default config
RUN rm -f /etc/nginx/conf.d/default.conf

# Copia config customizada (SPA fallback)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copia build estático
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
