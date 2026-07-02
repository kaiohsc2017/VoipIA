# =============================================================================
# Dockerfile — docker-helper
# Único container do stack que monta /var/run/docker.sock. Nenhum outro
# serviço (incluindo o backend Java) tem acesso direto ao socket do Docker.
# =============================================================================
FROM python:3.12-slim
WORKDIR /app

# Docker CLI + plugin compose — necessários para `docker logs`, `docker exec`
# e `docker compose up -d` via subprocess dentro deste container.
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
    https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list && \
    apt-get update -qq && \
    apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .

EXPOSE 8090
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090"]
