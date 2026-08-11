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

CMD ["python", "-m", "src.main"]
