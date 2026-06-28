"""
services/ai_service.py — Interface pública de IA com fallback automático.

FallbackRouter tenta cada entrada da chain em ordem.
Se o primário falhar (ProviderError), tenta o próximo transparentemente.
JiraCallFlow e ZabbixAlertFlow usam AIService sem saber qual provedor está ativo.
"""
from __future__ import annotations

import asyncio
import logging

from src.providers.base import BaseAIProvider, ProviderError, ProviderUnavailableError
from src.services import provider_registry as registry

logger = logging.getLogger("asteriskia.ai_service")


class AIService:
    """
    Substitui o GeminiService nos flows.
    Interface idêntica — flows existentes funcionam sem alteração.

    Métodos públicos:
      transcribe(pcm_data)                          → str
      synthesize_speech(text)                       → bytes
      synthesize_speech_streaming(text, writer)     → bool
      generate_response(prompt, context)            → str
      generate_response_with_tools(system, history) → str
    """

    # ── STT ──────────────────────────────────────────────────────────────────

    async def transcribe(self, pcm_data: bytes, hint: str = "") -> str:
        method = "transcribe_with_hint" if hint else "transcribe"
        args   = (pcm_data, hint) if hint else (pcm_data,)
        return await self._run("STT", method, *args)

    # ── LLM ──────────────────────────────────────────────────────────────────

    async def generate_response(self, prompt: str, context: str = "") -> str:
        history = [{"role": "user", "text": prompt}]
        return await self._run("LLM", "generate_response_with_tools",
                               "Responda de forma breve e clara em português.", history)

    async def generate_response_with_tools(
        self,
        system_instruction: str,
        history: list[dict],
    ) -> str:
        return await self._run("LLM", "generate_response_with_tools",
                               system_instruction, history)

    # ── TTS ──────────────────────────────────────────────────────────────────

    async def synthesize_speech(self, text: str) -> bytes:
        return await self._run("TTS", "synthesize_speech", text)

    async def synthesize_speech_streaming(self, text: str, writer) -> tuple[bool, float]:
        """Retorna (sucesso, duração_em_segundos)."""
        return await self._run("TTS", "synthesize_speech_streaming", text, writer)

    # ── FallbackRouter ────────────────────────────────────────────────────────

    async def _run(self, capability: str, method: str, *args):
        """
        Tenta cada provedor da chain em ordem.
        Em caso de ProviderError, loga e avança para o próximo.
        Levanta ProviderUnavailableError se todos falharem.
        """
        chain   = await registry.get_chain(capability)
        errors: list[ProviderError] = []

        if not chain:
            raise ProviderUnavailableError(capability, [])

        for entry in chain:
            provider_id = entry["provider"]
            model_id    = entry["modelId"]

            try:
                api_key  = await registry.get_provider_key(provider_id)
                provider = registry.build_provider(provider_id, model_id, api_key, capability)
                fn       = getattr(provider, method)
                result   = await fn(*args)
                if len(errors) > 0:
                    logger.info(
                        "[%s] Fallback bem-sucedido: %s/%s (após %d falha(s))",
                        capability, provider_id, model_id, len(errors)
                    )
                return result

            except ProviderError as e:
                logger.warning(
                    "[%s] Provedor %s/%s falhou: %s — tentando próximo",
                    capability, provider_id, model_id, e.cause
                )
                errors.append(e)

            except Exception as e:
                pe = ProviderError(provider_id, model_id, e)
                logger.warning("[%s] Erro inesperado em %s/%s: %s", capability, provider_id, model_id, e)
                errors.append(pe)

        raise ProviderUnavailableError(capability, errors)
