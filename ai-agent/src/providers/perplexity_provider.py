"""
providers/perplexity_provider.py — Provedor Perplexity.

A API da Perplexity é compatível com a interface OpenAI (Chat Completions).
Diferencial: modelos sonar têm acesso à internet em tempo real com citação de fontes.

Suporta apenas LLM — sem STT ou TTS.
"""
from __future__ import annotations

import asyncio
import logging

from src.providers.base import BaseAIProvider, ProviderError

logger = logging.getLogger("asteriskia.provider.perplexity")

_perplexity_client = None
_perplexity_key    = ""


def _get_perplexity(api_key: str):
    global _perplexity_client, _perplexity_key
    try:
        import openai as _sdk
    except ImportError:
        raise ImportError("Instale: pip install openai")
    if _perplexity_client is None or _perplexity_key != api_key:
        _perplexity_client = _sdk.OpenAI(
            api_key=api_key,
            base_url="https://api.perplexity.ai",
        )
        _perplexity_key = api_key
        logger.info("Cliente Perplexity criado com base_url https://api.perplexity.ai")
    return _perplexity_client


class PerplexityProvider(BaseAIProvider):
    """
    Provedor Perplexity — LLM com pesquisa web em tempo real.
    Ideal como fallback LLM quando precisar de informações atualizadas.
    Suporta apenas LLM — sem STT ou TTS nativos.
    """

    def __init__(self, model_id: str, api_key: str):
        self._model_id = model_id
        self._api_key  = api_key

    @property
    def provider_id(self) -> str:
        return "perplexity"

    @property
    def model_id(self) -> str:
        return self._model_id

    async def transcribe(self, pcm_data: bytes) -> str:
        raise ProviderError("perplexity", self._model_id,
            NotImplementedError("Perplexity não suporta STT"))

    async def synthesize_speech_streaming(self, text: str, writer) -> bool:
        raise ProviderError("perplexity", self._model_id,
            NotImplementedError("Perplexity não suporta TTS"))

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            client = _get_perplexity(self._api_key)

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
            raise ProviderError("perplexity", self._model_id, e) from e
