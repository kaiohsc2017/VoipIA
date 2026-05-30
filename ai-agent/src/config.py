"""
config.py — Configurações do Agente de IA
Carrega variáveis de ambiente e disponibiliza para o sistema.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# --- Audiosocket ---
AUDIOSOCKET_HOST: str = os.getenv("AUDIOSOCKET_HOST", "0.0.0.0")
AUDIOSOCKET_PORT: int = int(os.getenv("AUDIOSOCKET_PORT", "9092"))

# --- Google Gemini ---
GEMINI_API_KEY: str = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL_STT: str = os.getenv("GEMINI_MODEL_STT", "gemini-2.0-flash")
GEMINI_MODEL_LLM: str = os.getenv("GEMINI_MODEL_LLM", "gemini-2.0-flash")
GEMINI_MODEL_TTS: str = os.getenv("GEMINI_MODEL_TTS", "gemini-2.5-flash-preview-tts")

# --- Backend ---
BACKEND_URL: str = os.getenv("BACKEND_URL", "http://backend:8080")

# --- Áudio ---
AUDIO_STORAGE_PATH: str = os.getenv("AUDIO_STORAGE_PATH", "/var/asteriskia/recordings")

# --- Constantes de protocolo Audiosocket ---
# Tamanho do cabeçalho: 1 byte tipo + 2 bytes comprimento
AUDIOSOCKET_HEADER_SIZE: int = 3
# Tipos de mensagem do protocolo Audiosocket
MSG_TYPE_UUID: int = 0x01     # Identificador UUID da chamada
MSG_TYPE_AUDIO: int = 0x10    # Payload de áudio PCM
MSG_TYPE_HANGUP: int = 0x00   # Sinalização de encerramento
MSG_TYPE_ERROR: int = 0xFF    # Erro
