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


async def get_known_call_refs() -> dict[str, str]:
    """Busca no backend os call_ref já conhecidos + status atual de cada um (pending/
    processing/done/error). O watcher usa isso pra decidir: 'done' -> pular; qualquer
    outro status -> (re)processar."""
    resp = await _request_with_retry("GET", f"{BACKEND_URL}/api/v1/internal/insights/known-refs")
    data = resp.json()
    return {c["callRef"]: c["status"] for c in data.get("calls", [])}


async def register_pending(call_ref: str, wav_path: str, xml_path: str) -> None:
    """Registra um par .wav+.xml recém-descoberto, status='pending' — chamado ANTES de
    entrar na fila de processamento, pra aparecer na aba Processamento mesmo antes de
    começar a rodar. Idempotente no lado do backend (não sobrescreve status existente)."""
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights/{call_ref}/pending",
        json={"wavPath": wav_path, "xmlPath": xml_path},
    )


async def mark_processing(call_ref: str, wav_path: str | None = None, xml_path: str | None = None) -> None:
    """Marca o início real do processamento (retirada da fila) — chamado no início de
    process_pair(), tanto pra chamadas novas quanto pra retries de erro."""
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights/{call_ref}/processing",
        json={"wavPath": wav_path, "xmlPath": xml_path},
    )


async def mark_error(call_ref: str, error_msg: str) -> None:
    """Marca falha no processamento — sem isso, erros nunca ficavam visíveis (só nos logs
    do container) e o watcher reprocessava a mesma chamada pra sempre, silenciosamente."""
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights/{call_ref}/error",
        json={"errorMsg": error_msg[:2000]},
    )


async def submit_insights(payload: dict) -> None:
    """Envia o resultado completo do processamento de uma chamada (metadados,
    transcrição diarizada, insights) para persistência no backend."""
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights", json=payload, timeout=30.0
    )
