"""
config.py — Configurações do Agente de IA

Abordagem 3 — reload dinâmico sem restart:
  Variáveis de integração (GEMINI_*) são lidas do arquivo .env em disco
  a cada chamada via get_config(). O .env é um volume Docker compartilhado
  com o SettingsService do backend — qualquer alteração na tela de Settings
  reflete imediatamente no próximo uso, sem restart do container.

  Variáveis estruturais (AUDIOSOCKET_*, BACKEND_URL) são lidas uma vez
  no boot porque mudá-las exigiria reiniciar o processo de qualquer forma.
"""

import os
from dotenv import load_dotenv, dotenv_values
from pathlib import Path

# Caminho do .env montado como volume Docker
_ENV_PATH = Path(os.getenv("DOTENV_PATH", "/opt/asteriskia/env/.env"))

# Boot: carrega .env no ambiente do processo (para variáveis estruturais)
load_dotenv(_ENV_PATH if _ENV_PATH.exists() else None)

# --- Variáveis estruturais — lidas uma vez no boot ---

AUDIOSOCKET_HOST: str   = os.getenv("AUDIOSOCKET_HOST", "0.0.0.0")
AUDIOSOCKET_PORT: int   = int(os.getenv("AUDIOSOCKET_PORT", "9092"))
BACKEND_URL: str        = os.getenv("BACKEND_URL", "http://backend:8080")
AUDIO_STORAGE_PATH: str = os.getenv("AUDIO_STORAGE_PATH", "/var/asteriskia/recordings")

_raw_internal_key = os.getenv("INTERNAL_API_KEY", "")
if not _raw_internal_key:
    import logging as _log
    _log.getLogger("asteriskia.config").critical(
        "INTERNAL_API_KEY não configurada — defina no arquivo .env")
elif _raw_internal_key == "internal_changeme_dev":
    import logging as _log
    _log.getLogger("asteriskia.config").critical(
        "INTERNAL_API_KEY com valor padrão inseguro — altere no arquivo .env")
INTERNAL_API_KEY: str = _raw_internal_key

# --- Protocolo Audiosocket — constantes imutaveis ---

AUDIOSOCKET_HEADER_SIZE: int = 3
MSG_TYPE_UUID: int   = 0x01
MSG_TYPE_AUDIO: int  = 0x10
MSG_TYPE_HANGUP: int = 0x00
MSG_TYPE_ERROR: int  = 0xFF


# --- Leitura dinamica — rele o arquivo .env a cada chamada ---

def get_config(key: str, default: str = "") -> str:
    """
    Le uma variavel diretamente do arquivo .env em disco.
    Alteracoes na tela de Settings refletem imediatamente - sem restart.
    Fallback: variavel de ambiente do processo -> default.
    """
    if _ENV_PATH.exists():
        val = dotenv_values(_ENV_PATH).get(key, "")
        if val:
            return val
    return os.getenv(key, default)


def get_gemini_api_key()   -> str: return get_config("GEMINI_API_KEY", "")
def get_gemini_model_stt() -> str: return get_config("GEMINI_MODEL_STT", "gemini-2.5-flash")
def get_gemini_model_llm() -> str: return get_config("GEMINI_MODEL_LLM", "gemini-2.5-flash")
def get_gemini_model_tts() -> str: return get_config("GEMINI_MODEL_TTS", "gemini-2.5-flash-preview-tts")
