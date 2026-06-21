"""
providers/gemini.py — Provedor Google Gemini.

Encapsula o GeminiService existente para funcionar como BaseAIProvider.
Toda a lógica de streaming, resampling e function calling permanece intacta.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from src.providers.base import BaseAIProvider, ProviderError
from src.services.gemini_service import GeminiService

logger = logging.getLogger("asteriskia.provider.gemini")


class GeminiProvider(BaseAIProvider):
    """
    Adapter entre BaseAIProvider e o GeminiService existente.
    O GeminiService continua sendo o singleton de processo — reutilizamos
    sem recriar cliente HTTP.
    """

    def __init__(self, model_id: str):
        self._model_id   = model_id
        self._svc        = GeminiService()

    @property
    def provider_id(self) -> str:
        return "gemini"

    @property
    def model_id(self) -> str:
        return self._model_id

    async def transcribe(self, pcm_data: bytes) -> str:
        try:
            return await self._svc.transcribe(pcm_data)
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        try:
            return await self._svc.generate_response_with_tools(
                system_instruction=system_instruction,
                history=history,
            )
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    async def synthesize_speech_streaming(self, text: str, writer) -> bool:
        try:
            return await self._svc.synthesize_speech_streaming(text, writer)
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e

    async def synthesize_speech(self, text: str) -> bytes:
        try:
            return await self._svc.synthesize_speech(text)
        except Exception as e:
            raise ProviderError("gemini", self._model_id, e) from e
