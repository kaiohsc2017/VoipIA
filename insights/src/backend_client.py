"""
backend_client.py — Cliente HTTP compartilhado do serviço de Insights.

Espelha ai-agent/src/services/backend_client.py: header X-Internal-Key em
todas as requisições, cliente httpx.AsyncClient único reaproveitado, retry
simples em falhas transitórias de conexão/timeout. Este serviço NUNCA acessa
o Postgres direto — toda persistência/consulta de estado passa pelo backend
Java, mesmo padrão já usado pelo ai-agent.

Os dois endpoints abaixo (`known-refs` e `insights`) são implementados no
backend Java na Fase 3 deste plano — este cliente já nasce pronto para
consumi-los.
"""

from __future__ import annotations

import asyncio
import logging

import httpx

from src.config import BACKEND_URL, INTERNAL_API_KEY

logger = logging.getLogger("asteriskia.insights.backend_client")

_AUTH_HEADERS = {"X-Internal-Key": INTERNAL_API_KEY}
_client = httpx.AsyncClient(timeout=15.0, headers=_AUTH_HEADERS)

_MAX_ATTEMPTS = 2
_RETRY_BACKOFF_SECONDS = 0.5


async def _request_with_retry(method: str, url: str, **kwargs) -> httpx.Response:
    last_error: Exception | None = None
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            resp = await _client.request(method, url, **kwargs)
            resp.raise_for_status()
            return resp
        except (httpx.ConnectError, httpx.TimeoutException) as e:
            last_error = e
            if attempt < _MAX_ATTEMPTS:
                logger.warning(
                    "Falha transitória em %s %s (tentativa %d/%d): %s",
                    method, url, attempt, _MAX_ATTEMPTS, e,
                )
                await asyncio.sleep(_RETRY_BACKOFF_SECONDS)
    raise last_error


async def get_known_call_refs() -> set[str]:
    """Busca no backend os call_ref já conhecidos (em qualquer status), para o
    watcher não reprocessar pares já descobertos em ciclos de poll anteriores."""
    resp = await _request_with_retry("GET", f"{BACKEND_URL}/api/v1/internal/insights/known-refs")
    data = resp.json()
    return set(data.get("callRefs", []))


async def submit_insights(payload: dict) -> None:
    """Envia o resultado completo do processamento de uma chamada (metadados,
    transcrição diarizada, insights) para persistência no backend."""
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights", json=payload, timeout=30.0
    )
