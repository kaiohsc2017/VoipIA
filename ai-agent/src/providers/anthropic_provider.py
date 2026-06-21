"""
providers/anthropic_provider.py — Provedor Anthropic (Claude).

Suporta apenas LLM — Claude não tem API de STT ou TTS nativa.
Usa a biblioteca anthropic oficial via asyncio.to_thread.
"""
from __future__ import annotations

import asyncio
import logging

from src.providers.base import BaseAIProvider, ProviderError

logger = logging.getLogger("asteriskia.provider.anthropic")

# Singleton do cliente Anthropic por processo
_anthropic_client = None
_anthropic_key    = ""


def _get_anthropic_client(api_key: str):
    global _anthropic_client, _anthropic_key
    try:
        import anthropic as _anthropic_sdk
    except ImportError:
        raise ImportError("Instale: pip install anthropic")

    if _anthropic_client is None or _anthropic_key != api_key:
        _anthropic_client = _anthropic_sdk.Anthropic(api_key=api_key)
        _anthropic_key    = api_key
    return _anthropic_client


class AnthropicProvider(BaseAIProvider):

    def __init__(self, model_id: str, api_key: str):
        self._model_id = model_id
        self._api_key  = api_key

    @property
    def provider_id(self) -> str:
        return "anthropic"

    @property
    def model_id(self) -> str:
        return self._model_id

    async def transcribe(self, pcm_data: bytes) -> str:
        raise ProviderError("anthropic", self._model_id,
            NotImplementedError("Anthropic não suporta STT"))

    async def synthesize_speech_streaming(self, text: str, writer) -> bool:
        raise ProviderError("anthropic", self._model_id,
            NotImplementedError("Anthropic não suporta TTS"))

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            client = _get_anthropic_client(self._api_key)

            messages = [
                {"role": turn["role"] if turn["role"] in ("user","assistant") else "user",
                 "content": turn.get("text","") or turn.get("content","")}
                for turn in history
            ]

            def _call():
                resp = client.messages.create(
                    model=self._model_id,
                    max_tokens=1024,
                    system=system_instruction,
                    messages=messages,
                )
                return resp.content[0].text if resp.content else ""

            return await asyncio.to_thread(_call)

        except Exception as e:
            raise ProviderError("anthropic", self._model_id, e) from e
