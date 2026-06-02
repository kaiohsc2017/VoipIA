"""
backend_service.py — Comunicação autenticada com a API REST do backend Spring Boot.

Responsabilidades:
  - Buscar testes de conectividade ativos (NumberTest)
  - Registrar resultado de cada execução (TestResult)

Autenticação: X-Internal-Key header (shared secret entre serviços Docker).
"""

import logging
import httpx
from datetime import datetime
from src.config import BACKEND_URL, INTERNAL_API_KEY

logger = logging.getLogger("scheduler.backend")

# Headers padrão para todas as requisições internas
_AUTH_HEADERS = {
    "X-Internal-Key": INTERNAL_API_KEY,
    "Content-Type": "application/json",
}


class BackendService:
    """Cliente HTTP autenticado para comunicação com o backend AsteriskIA."""

    def __init__(self):
        self._base = BACKEND_URL

    def get_active_number_tests(self) -> list[dict]:
        """
        Retorna todos os NumberTests com is_active=true.
        Usado pelo scheduler para montar os jobs de chamada.
        """
        try:
            with httpx.Client(timeout=10.0, headers=_AUTH_HEADERS) as client:
                resp = client.get(
                    f"{self._base}/api/v1/number-tests",
                    params={"active": "true"}
                )
                resp.raise_for_status()
                tests = resp.json()
                logger.debug("Backend: %d testes ativos carregados", len(tests))
                return tests
        except Exception as e:
            logger.error("Erro ao buscar testes ativos: %s", e)
            return []

    def register_test_result(
        self,
        number_test_id: int,
        status: str,
        sip_response_code: int | None = None,
        sip_response_reason: str | None = None,
        asterisk_call_id: str | None = None,
        execution_order: int = 1,
    ) -> bool:
        """
        Registra o resultado de uma execução de teste no backend.

        Args:
            number_test_id: ID do NumberTest
            status:         SUCESSO | FALHA | OCUPADO | SEM_RESPOSTA | TIMEOUT | etc.
            sip_response_code: Código SIP retornado pelo Asterisk
            sip_response_reason: Descrição do código SIP
            asterisk_call_id: UNIQUEID da chamada no Asterisk
            execution_order: Número da execução dentro do batch
        """
        payload = {
            "numberTest": {"id": number_test_id},
            "executedAt": datetime.now().isoformat(),
            "status": status,
            "sipResponseCode": sip_response_code,
            "sipResponseReason": sip_response_reason,
            "asteriskCallId": asterisk_call_id,
            "executionOrder": execution_order,
        }
        try:
            with httpx.Client(timeout=10.0, headers=_AUTH_HEADERS) as client:
                resp = client.post(
                    f"{self._base}/api/v1/test-results",
                    json=payload
                )
                resp.raise_for_status()
                logger.info(
                    "Resultado registrado: numberTest=%d status=%s", number_test_id, status
                )
                return True
        except Exception as e:
            logger.error("Erro ao registrar resultado: %s", e)
            return False
