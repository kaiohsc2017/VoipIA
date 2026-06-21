"""
providers/grok_provider.py — Provedor Grok (xAI).

A API do Grok é 100% compatível com a interface OpenAI (mesmo formato de
request/response). Reutilizamos o cliente openai apontando para a base_url
da xAI — sem duplicar lógica.

Suporta apenas LLM — Grok não tem API de STT ou TTS.
"""
from __future__ import annotations

import asyncio
import logging

from src.providers.base import BaseAIProvider, ProviderError

logger = logging.getLogger("asteriskia.provider.grok")

_grok_client = None
_grok_key    = ""


def _get_grok(api_key: str):
    global _grok_client, _grok_key
    try:
        import openai as _sdk
    except ImportError:
        raise ImportError("Instale: pip install openai")
    if _grok_client is None or _grok_key != api_key:
        _grok_client = _sdk.OpenAI(
            api_key=api_key,
            base_url="https://api.x.ai/v1",
        )
        _grok_key = api_key
        logger.info("Cliente Grok (xAI) criado com base_url https://api.x.ai/v1")
    return _grok_client


class GrokProvider(BaseAIProvider):
    """
    Provedor Grok usando a API OpenAI-compatible da xAI.
    Suporta apenas LLM — sem STT ou TTS nativos.
    """

    def __init__(self, model_id: str, api_key: str):
        self._model_id = model_id
        self._api_key  = api_key

    @property
    def provider_id(self) -> str:
        return "grok"

    @property
    def model_id(self) -> str:
        return self._model_id

    async def transcribe(self, pcm_data: bytes) -> str:
        raise ProviderError("grok", self._model_id,
            NotImplementedError("Grok não suporta STT — escolha outro provedor para esta capability"))

    async def synthesize_speech_streaming(self, text: str, writer) -> bool:
        raise ProviderError("grok", self._model_id,
            NotImplementedError("Grok não suporta TTS — escolha outro provedor para esta capability"))

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            client = _get_grok(self._api_key)

            messages = [{"role": "system", "content": system_instruction}]
            for turn in history:
                role    = "assistant" if turn.get("role") == "model" else "user"
                content = turn.get("text", "") or turn.get("content", "")
                messages.append({"role": role, "content": content})

            def _call():
                resp = client.chat.completions.create(
                    model=self._model_id,
                    messages=messages,
                    max_tokens=512,
                )
                return resp.choices[0].message.content or ""

            return await asyncio.to_thread(_call)

        except Exception as e:
            raise ProviderError("grok", self._model_id, e) from e
