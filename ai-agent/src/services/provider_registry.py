"""
services/provider_registry.py — Carrega a chain ativa do banco via backend
e instancia o provedor correto para cada entrada.

Cache em memória de 60s — alinhado ao ConfigService do backend.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import TYPE_CHECKING

import httpx

from src.config import BACKEND_URL, INTERNAL_API_KEY
from src.providers.base import BaseAIProvider

if TYPE_CHECKING:
    pass

logger = logging.getLogger("asteriskia.provider_registry")

CACHE_TTL = 60  # segundos — igual ao ConfigService do backend

_chain_cache: dict[str, list[dict]] = {}
_cache_ts: float = 0.0
_cache_lock = asyncio.Lock()


async def _fetch_chain_from_backend() -> dict[str, list[dict]]:
    """Busca a chain ativa no backend via endpoint interno."""
    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.get(
            f"{BACKEND_URL}/api/v1/ai/chain/active",
            headers={"X-Internal-Key": INTERNAL_API_KEY},
        )
        resp.raise_for_status()
        return resp.json()


async def get_chain(capability: str) -> list[dict]:
    """
    Retorna a lista de {provider, modelId} para a capability,
    em ordem de prioridade. Cache de 60s.
    """
    global _chain_cache, _cache_ts

    async with _cache_lock:
        now = time.monotonic()
        if now - _cache_ts > CACHE_TTL or not _chain_cache:
            try:
                _chain_cache = await _fetch_chain_from_backend()
                _cache_ts    = now
                logger.info("Chain carregada do banco: %s",
                            {k: len(v) for k, v in _chain_cache.items()})
            except Exception as e:
                logger.error("Erro ao carregar chain do banco: %s — usando cache anterior", e)
                if not _chain_cache:
                    # Fallback de emergência: Gemini padrão
                    _chain_cache = {
                        "STT": [{"provider": "gemini", "modelId": "gemini-2.0-flash"}],
                        "LLM": [{"provider": "gemini", "modelId": "gemini-2.0-flash"}],
                        "TTS": [{"provider": "gemini", "modelId": "gemini-2.5-flash-preview-tts"}],
                    }

    return _chain_cache.get(capability, [])


async def _fetch_provider_key(provider: str) -> str:
    """Busca a API key real do provedor no backend (convenção AI_KEY_<PROVIDER> no system_config)."""
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(
                f"{BACKEND_URL}/api/v1/ai/providers/{provider}/key-internal",
                headers={"X-Internal-Key": INTERNAL_API_KEY},
            )
            if resp.status_code == 200:
                return resp.json().get("apiKey", "")
    except Exception as e:
        logger.debug("Erro ao buscar key do provedor %s: %s", provider, e)
    return ""


# Cache de keys por provedor (TTL = 120s)
_key_cache: dict[str, tuple[str, float]] = {}


async def get_provider_key(provider: str) -> str:
    now = time.monotonic()
    cached = _key_cache.get(provider)
    if cached and now - cached[1] < 120:
        return cached[0]
    key = await _fetch_provider_key(provider)
    _key_cache[provider] = (key, now)
    return key


def _build_gemini(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.gemini import GeminiProvider
    return GeminiProvider(model_id)


def _build_anthropic(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.anthropic_provider import AnthropicProvider
    return AnthropicProvider(model_id, api_key)


def _build_openai(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.openai_provider import OpenAIProvider
    return OpenAIProvider(model_id, api_key, capability)


def _build_elevenlabs(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.elevenlabs_provider import ElevenLabsProvider
    return ElevenLabsProvider(model_id, api_key)


def _build_grok(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.grok_provider import GrokProvider
    return GrokProvider(model_id, api_key)


def _build_perplexity(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.perplexity_provider import PerplexityProvider
    return PerplexityProvider(model_id, api_key)


def _build_local(model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    from src.providers.local_provider import LocalProvider
    return LocalProvider(model_id, capability)


# Registro por provider — cada builder faz import tardio do próprio módulo (evita
# carregar todos os SDKs de provedor de uma vez só por causa de um único usado).
_PROVIDER_BUILDERS = {
    "gemini": _build_gemini,
    "anthropic": _build_anthropic,
    "openai": _build_openai,
    "elevenlabs": _build_elevenlabs,
    "grok": _build_grok,
    "perplexity": _build_perplexity,
    "local": _build_local,
}


def build_provider(provider: str, model_id: str, api_key: str, capability: str) -> BaseAIProvider:
    """Instancia o provedor correto para o (provider, model_id) dado."""
    builder = _PROVIDER_BUILDERS.get(provider)
    if builder is None:
        # Provedor desconhecido — tenta Gemini como última salvaguarda
        logger.warning("Provedor desconhecido: %s — usando Gemini", provider)
        return _build_gemini("gemini-2.0-flash", api_key, capability)
    return builder(model_id, api_key, capability)
