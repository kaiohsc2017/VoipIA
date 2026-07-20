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
import time

import httpx

from src.config import BACKEND_URL, INTERNAL_API_KEY

logger = logging.getLogger("asteriskia.insights.backend_client")

_AUTH_HEADERS = {"X-Internal-Key": INTERNAL_API_KEY}
_client = httpx.AsyncClient(timeout=15.0, headers=_AUTH_HEADERS)

_MAX_ATTEMPTS = 2
_RETRY_BACKOFF_SECONDS = 0.5

# Ficha de avaliação ativa — cacheada em memória com TTL curto (mesmo padrão do
# .env em config.py), pra não bater no backend a cada chamada processada dentro
# do mesmo ciclo de poll (várias rodam em paralelo, limitadas pelo semáforo).
_SCORECARD_CACHE_TTL_SECONDS = 60
_scorecard_cache: dict | None = None
_scorecard_cache_ts: float = 0.0


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


async def get_active_scorecard() -> dict | None:
    """Ficha de avaliação ativa no momento (Fase 1 do Quality Management, V38),
    cacheada por _SCORECARD_CACHE_TTL_SECONDS. None quando nenhuma ficha está
    ativa (204) ou quando o backend está inacessível — nesse caso o pipeline
    segue exatamente como antes desta feature (avaliação é opcional)."""
    global _scorecard_cache, _scorecard_cache_ts
    now = time.monotonic()
    if now - _scorecard_cache_ts <= _SCORECARD_CACHE_TTL_SECONDS:
        return _scorecard_cache

    try:
        resp = await _client.get(f"{BACKEND_URL}/api/v1/internal/insights/active-scorecard")
        if resp.status_code == 204:
            _scorecard_cache = None
        else:
            resp.raise_for_status()
            _scorecard_cache = resp.json()
        _scorecard_cache_ts = now
    except Exception as e:
        logger.warning("Falha ao consultar ficha de avaliação ativa — seguindo sem avaliação: %s", e)
        # Não atualiza o timestamp — próxima chamada tenta de novo, sem esperar o TTL inteiro.
        return _scorecard_cache

    return _scorecard_cache


async def get_pending_reports() -> list[dict]:
    """Relatórios de performance pendentes de narrativa (Fase 2 do Quality Management,
    V39) — agregado numérico já calculado no Java, este serviço só chama o LLM para
    escrever a narrativa a partir dele."""
    resp = await _request_with_retry("GET", f"{BACKEND_URL}/api/v1/internal/insights/reports/pending")
    return resp.json()


async def mark_report_processing(report_id: int) -> None:
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights/reports/{report_id}/processing"
    )


async def mark_report_error(report_id: int, error_msg: str) -> None:
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights/reports/{report_id}/error",
        json={"errorMsg": error_msg[:2000]},
    )


async def submit_report_result(report_id: int, payload: dict) -> None:
    await _request_with_retry(
        "POST", f"{BACKEND_URL}/api/v1/internal/insights/reports/{report_id}/result",
        json=payload, timeout=30.0,
    )


async def get_pending_uploads() -> list[dict]:
    """Arquivos do portal do supervisor aguardando processamento (Fase 3 do Quality
    Management, V40) — já registrados com metadados pelo Java no momento do upload,
    sem precisar de descoberta por regex de nome de arquivo."""
    resp = await _request_with_retry("GET", f"{BACKEND_URL}/api/v1/internal/insights/uploads/pending")
    return resp.json()
