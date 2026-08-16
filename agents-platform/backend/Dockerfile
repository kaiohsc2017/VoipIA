FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
# Débito de segurança corrigido: rodava como root (tentativa anterior revertida
# por falta de acesso ao /opt/VoipIA/env, 700 root:root). Agora o host usa
# GID 1500 fixo (grupo "voipia-app"), mesmo usado por backend/ai-agent —
# /opt/VoipIA/env foi ajustado com setgid+chgrp pra esse GID (ver CLAUDE.md),
# então um UID não-root neste grupo consegue ler/escrever .env.agents.
RUN groupadd -g 1500 voipia-app 2>/dev/null || true && \
    useradd -u 1503 -g voipia-app -M -s /usr/sbin/nologin agentsapi && \
    chown -R agentsapi:voipia-app /app
USER agentsapi
# Migração roda uma vez antes de forkar os workers
CMD ["sh", "-c", "python migrate.py && uvicorn main:app --host 0.0.0.0 --port 8000 --workers 2"]
