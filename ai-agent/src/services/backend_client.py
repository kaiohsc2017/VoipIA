"""
backend_client.py — Cliente HTTP compartilhado para o AI Agent.

Adiciona automaticamente o header X-Internal-Key em todas as
requisições ao backend Spring Boot.
"""

import logging
import httpx
from src.config import BACKEND_URL, INTERNAL_API_KEY

logger = logging.getLogger("asteriskia.backend_client")

# Header de autenticação interna (shared secret entre serviços Docker)
_AUTH_HEADERS = {"X-Internal-Key": INTERNAL_API_KEY}


def _headers() -> dict:
    return {**_AUTH_HEADERS, "Content-Type": "application/json"}


async def get(path: str, **kwargs) -> dict | list:
    """GET autenticado ao backend."""
    async with httpx.AsyncClient(timeout=10.0, headers=_AUTH_HEADERS) as client:
        resp = await client.get(f"{BACKEND_URL}{path}", **kwargs)
        resp.raise_for_status()
        return resp.json()


async def post(path: str, json: dict, **kwargs) -> dict:
    """POST autenticado ao backend."""
    async with httpx.AsyncClient(timeout=15.0, headers=_AUTH_HEADERS) as client:
        resp = await client.post(f"{BACKEND_URL}{path}", json=json, **kwargs)
        resp.raise_for_status()
        return resp.json()


async def patch(path: str, json: dict, **kwargs) -> None:
    """PATCH autenticado ao backend."""
    async with httpx.AsyncClient(timeout=10.0, headers=_AUTH_HEADERS) as client:
        resp = await client.patch(f"{BACKEND_URL}{path}", json=json, **kwargs)
        resp.raise_for_status()
