"""
backend_client.py — Cliente HTTP compartilhado para o AI Agent.

Adiciona automaticamente o header X-Internal-Key em todas as
requisições ao backend Spring Boot. Reutiliza um único
httpx.AsyncClient (evita handshake TCP+TLS a cada requisição) e
faz retry simples em falhas transitórias de conexão/timeout.
"""

import asyncio
import logging
import httpx
from src.config import BACKEND_URL, INTERNAL_API_KEY

logger = logging.getLogger("asteriskia.backend_client")

# Header de autenticação interna (shared secret entre serviços Docker)
_AUTH_HEADERS = {"X-Internal-Key": INTERNAL_API_KEY}

# Cliente único e reutilizável — criado uma vez para todo o processo.
_client = httpx.AsyncClient(timeout=15.0, headers=_AUTH_HEADERS)

_MAX_ATTEMPTS = 2
_RETRY_BACKOFF_SECONDS = 0.5


async def _request_with_retry(method: str, url: str, **kwargs) -> httpx.Response:
    """
    Executa a requisição HTTP via cliente compartilhado, com retry (backoff curto)
    apenas para falhas transitórias de conexão/timeout — erros HTTP de resposta
    (4xx/5xx) não são reprocessados.
    """
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


async def get(path: str, **kwargs) -> dict | list:
    """GET autenticado ao backend."""
    kwargs.setdefault("timeout", 10.0)
    resp = await _request_with_retry("GET", f"{BACKEND_URL}{path}", **kwargs)
    return resp.json()


async def post(path: str, json: dict, **kwargs) -> dict:
    """POST autenticado ao backend."""
    kwargs.setdefault("timeout", 15.0)
    resp = await _request_with_retry("POST", f"{BACKEND_URL}{path}", json=json, **kwargs)
    return resp.json()


async def patch(path: str, json: dict, **kwargs) -> None:
    """PATCH autenticado ao backend."""
    kwargs.setdefault("timeout", 10.0)
    await _request_with_retry("PATCH", f"{BACKEND_URL}{path}", json=json, **kwargs)
