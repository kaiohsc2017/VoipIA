"""
gemini_client.py — Cliente Gemini singleton do serviço de Insights.

Espelha o padrão de ai-agent/src/providers/gemini_shared.py::get_global_client
(recria o cliente apenas se a API key mudar), sem acoplar às tools de function
calling específicas do fluxo de chamada em tempo real do ai-agent — este
serviço só faz STT/diarização e geração de insights em lote.
"""

from __future__ import annotations

import logging

import google.genai as genai

from src.config import get_gemini_api_key

logger = logging.getLogger("asteriskia.insights.gemini_client")

_client_instance: genai.Client | None = None
_client_api_key: str | None = None


def get_client() -> genai.Client:
    """Retorna o cliente Gemini singleton do processo, recriando apenas se a API key mudar."""
    global _client_instance, _client_api_key

    api_key = get_gemini_api_key()
    if not api_key:
        raise RuntimeError(
            "GEMINI_API_KEY não configurada. Acesse Settings → Google Gemini para configurar."
        )
    if _client_instance is None or _client_api_key != api_key:
        _client_instance = genai.Client(api_key=api_key)
        _client_api_key = api_key
        logger.info("Cliente Gemini (re)criado — API key atualizada.")
    return _client_instance
