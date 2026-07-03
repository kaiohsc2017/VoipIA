FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
# Achado de segurança (débito aceito, revertido): tentei rodar como usuário
# não-root, mas o bind mount /opt/AsteriskIA/env (docker-compose.yml) aponta
# pra um diretório do HOST com permissão 700 root:root — um UID não-root
# dentro do container não consegue nem atravessar o diretório pra ler/
# escrever .env.agents (confirmado em produção: agents-api caiu em crash
# loop com PermissionError ao tentar). Corrigir direito exigiria mudar
# permissão de diretório no HOST (fora do escopo de um Dockerfile) ou um
# esquema de GID compartilhado — mesma classe de problema documentada pro
# backend Java (fila security_cmds). Mantido como root por ora.
# Migração roda uma vez antes de forkar os workers
CMD ["sh", "-c", "python migrate.py && uvicorn main:app --host 0.0.0.0 --port 8000 --workers 2"]
