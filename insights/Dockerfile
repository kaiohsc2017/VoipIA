# =============================================================
# Dockerfile — Serviço de Insights (Python)
# Transcreve e analisa gravações do call center corporativo Verint
# (.wav + .xml em /opt/audio) — módulo apartado do domínio Asterisk.
# =============================================================

FROM python:3.12-slim

# ffmpeg decodifica o WAV proprietário Verint (codec G.729A) para PCM;
# libsndfile1 é dependência de runtime do librosa (leitura de áudio).
RUN apt-get update && apt-get install -y \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

CMD ["python", "-m", "src.main"]
