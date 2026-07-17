"""
config.py — Configurações do serviço de Insights.

Mesma abordagem do ai-agent/src/config.py: variáveis de integração (GEMINI_*)
são lidas do .env em disco a cada chamada via get_config(), com TTL curto —
o .env é o mesmo volume Docker compartilhado com o SettingsService do backend.
Variáveis estruturais (AUDIO_DIR, BACKEND_URL, POLL_INTERVAL) são lidas uma
vez no boot, pois mudá-las já exigiria reiniciar o processo.
"""

import os
import time
from dotenv import load_dotenv, dotenv_values
from pathlib import Path

# Caminho do .env montado como volume Docker (mesmo arquivo do ai-agent/backend)
_ENV_PATH = Path(os.getenv("DOTENV_PATH", "/opt/asteriskia/env/.env"))

load_dotenv(_ENV_PATH if _ENV_PATH.exists() else None)

# --- Variáveis estruturais — lidas uma vez no boot ---

AUDIO_DIR: str = os.getenv("INSIGHTS_AUDIO_DIR", "/opt/audio")
BACKEND_URL: str = os.getenv("BACKEND_URL", "http://backend:8080")
POLL_INTERVAL_SECONDS: int = int(os.getenv("INSIGHTS_POLL_INTERVAL_SECONDS", "60"))
MAX_CONCURRENT_PROCESSING: int = int(os.getenv("INSIGHTS_MAX_CONCURRENCY", "2"))

_raw_internal_key = os.getenv("INTERNAL_API_KEY", "")
if not _raw_internal_key:
    import logging as _log
    _log.getLogger("asteriskia.insights.config").critical(
        "INTERNAL_API_KEY não configurada — defina no arquivo .env")
elif _raw_internal_key == "internal_changeme_dev":
    import logging as _log
    _log.getLogger("asteriskia.insights.config").critical(
        "INTERNAL_API_KEY com valor padrão inseguro — altere no arquivo .env")
INTERNAL_API_KEY: str = _raw_internal_key


# --- Leitura dinâmica — cacheia o .env com TTL curto (mesmo padrão do ai-agent) ---

_DOTENV_CACHE_TTL = 60
_dotenv_cache: dict[str, str] = {}
_dotenv_cache_ts: float = 0.0


def get_config(key: str, default: str = "") -> str:
    """Lê uma variável do .env, cacheada em memória por _DOTENV_CACHE_TTL segundos.
    Fallback: variável de ambiente do processo -> default."""
    global _dotenv_cache, _dotenv_cache_ts
    now = time.monotonic()
    if now - _dotenv_cache_ts > _DOTENV_CACHE_TTL:
        if _ENV_PATH.exists():
            _dotenv_cache = dotenv_values(_ENV_PATH)
        _dotenv_cache_ts = now
    val = _dotenv_cache.get(key, "")
    if val:
        return val
    return os.getenv(key, default)


def get_gemini_api_key() -> str: return get_config("GEMINI_API_KEY", "")
def get_gemini_model_stt() -> str: return get_config("GEMINI_MODEL_STT", "gemini-2.5-flash")
def get_gemini_model_insights() -> str: return get_config("GEMINI_MODEL_LLM", "gemini-2.5-flash")
