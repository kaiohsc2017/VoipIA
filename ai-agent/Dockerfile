# =============================================================
# Dockerfile — Agente de IA (Python)
# Servidor Audiosocket assíncrono que processa áudio de chamadas
# via Gemini (STT, LLM e TTS).
# =============================================================

FROM python:3.12-slim

# Dependências de sistema para processamento de áudio
RUN apt-get update && apt-get install -y \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

# Débito de segurança corrigido: container rodava como root (F-HIGH, auditoria
# 2026-07-02). GID 1500 fixo (grupo "voipia-app" no host), mesmo usado por
# backend/agents-backend — este container só precisa RW em asterisk_recordings/
# ura_audio_cache (volumes Docker) e leitura de env/.env (DOTENV_PATH), todos
# ajustados com setgid+chgrp para esse GID (ver CLAUDE.md).
RUN groupadd -g 1500 voipia-app 2>/dev/null || true && \
    useradd -u 1502 -g voipia-app -M -s /usr/sbin/nologin aiagent && \
    chown -R aiagent:voipia-app /app

USER aiagent

CMD ["python", "-m", "src.main"]
