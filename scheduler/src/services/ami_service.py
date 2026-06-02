"""
ami_service.py — Integração com Asterisk Manager Interface (AMI) via panoramisk.

Responsabilidades:
  - Originar chamadas de teste de conectividade
  - Capturar eventos AMI de resultado (Hangup)
  - Mapear código SIP → status do teste
"""

import asyncio
import logging
from typing import Optional

from panoramisk import Manager, Message
from src.config import (
    AMI_HOST, AMI_PORT, AMI_USER, AMI_PASSWORD,
    AMI_ORIGINATE_TIMEOUT_MS, TRUNK_NAME, DIALPLAN_TEST_CONTEXT,
)

logger = logging.getLogger("scheduler.ami")


# Mapeamento de código SIP → status do teste (Módulo 2)
SIP_STATUS_MAP: dict[int, str] = {
    200: "SUCESSO",
    183: "SUCESSO",   # Session Progress (ringing)
    486: "OCUPADO",
    600: "OCUPADO",
    480: "SEM_RESPOSTA",
    408: "TIMEOUT",
    404: "INVALIDO",
    403: "INVALIDO",
    503: "INDISPONIVEL",
    603: "RECUSADO",
}

# Mapeamento de Asterisk hangup cause → status
# (usado como fallback quando não há código SIP direto)
HANGUP_CAUSE_MAP: dict[int, str] = {
    16: "SUCESSO",       # Normal Clearing
    17: "OCUPADO",       # User Busy
    18: "SEM_RESPOSTA",  # No User Responding
    19: "SEM_RESPOSTA",  # No Answer from User
    20: "SEM_RESPOSTA",  # Subscriber Absent
    21: "RECUSADO",      # Call Rejected
    28: "INVALIDO",      # Invalid Number Format
    38: "FALHA",         # Network Out of Order
    41: "FALHA",         # Temporary Failure
}


class AmiService:
    """
    Serviço AMI assíncrono para originar chamadas de teste (Módulo 2).

    Usa a biblioteca panoramisk para comunicação com o AMI.
    Cada chamada é aguardada por até ORIGINATE_TIMEOUT_MS milissegundos.

    Nota: panoramisk >= 1.2 não aceita o parâmetro `loop` (deprecated desde Python 3.10).
    """

    def __init__(self):
        self._manager: Optional[Manager] = None

    async def connect(self) -> None:
        """Conecta e autentica no AMI."""
        # panoramisk >= 1.2 não usa loop= (asyncio usa o event loop global)
        self._manager = Manager(
            host=AMI_HOST,
            port=AMI_PORT,
            username=AMI_USER,
            secret=AMI_PASSWORD,
        )
        await self._manager.connect()
        logger.info("AMI: conectado em %s:%d", AMI_HOST, AMI_PORT)

    async def disconnect(self) -> None:
        """Desconecta do AMI."""
        if self._manager:
            self._manager.close()
            self._manager = None

    async def originate_test_call(
        self,
        phone_number: str,
        test_result_id: int,
        execution_order: int = 1,
    ) -> tuple[str, int | None, str | None]:
        """
        Origina uma chamada de teste e aguarda o resultado.

        Returns:
            Tupla (status, sip_code, sip_reason):
              - status: SUCESSO | FALHA | OCUPADO | SEM_RESPOSTA | etc.
              - sip_code: Código SIP/Cause recebido (pode ser None)
              - sip_reason: Descrição do código (pode ser None)
        """
        if not self._manager:
            try:
                await self.connect()
            except Exception as e:
                logger.error("Não foi possível conectar ao AMI: %s", e)
                return "FALHA", None, str(e)

        action_id = f"test-{test_result_id}-{execution_order}"

        # Evento que será sinalizado quando a chamada terminar
        done_event = asyncio.Event()
        result_holder: dict = {"status": "FALHA", "sip_code": None, "sip_reason": None}

        def on_hangup(manager: Manager, message: Message) -> None:
            """Callback disparado pelo evento Hangup do AMI."""
            if (message.get("ActionID") == action_id or
                    message.get("Channel", "").startswith(f"PJSIP/{phone_number}")):

                cause_str = message.get("Cause", "0")
                cause_txt = message.get("Cause-txt", "Normal Clearing")

                sip_code = int(cause_str) if cause_str.isdigit() else None
                # Primeiro tenta mapear como código SIP, depois como Cause
                status = (
                    SIP_STATUS_MAP.get(sip_code)
                    or HANGUP_CAUSE_MAP.get(sip_code, "FALHA")
                )

                result_holder["status"] = status
                result_holder["sip_code"] = sip_code
                result_holder["sip_reason"] = cause_txt
                done_event.set()

        self._manager.register_event("Hangup", on_hangup)

        try:
            # Envia ação Originate
            originate_action = {
                "Action": "Originate",
                "ActionID": action_id,
                "Channel": f"PJSIP/{phone_number}@{TRUNK_NAME}",
                "Context": DIALPLAN_TEST_CONTEXT,
                "Exten": "s",
                "Priority": "1",
                "Timeout": str(AMI_ORIGINATE_TIMEOUT_MS),
                "CallerID": "AsteriskIA-Test <9999>",
                "Async": "true",
                "Variable": f"TEST_RESULT_ID={test_result_id},FLOW_TYPE=CONNECTIVITY_TEST",
            }
            await self._manager.send_action(originate_action)
            logger.info("Chamada originada para %s (actionId=%s)", phone_number, action_id)

            # Aguarda evento de hangup por até TIMEOUT + 15s de margem
            timeout_secs = (AMI_ORIGINATE_TIMEOUT_MS / 1000) + 15
            await asyncio.wait_for(done_event.wait(), timeout=timeout_secs)

        except asyncio.TimeoutError:
            logger.warning("Timeout aguardando resultado para %s", phone_number)
            result_holder["status"] = "TIMEOUT"
        except Exception as e:
            logger.error("Erro ao originar chamada para %s: %s", phone_number, e)
            result_holder["status"] = "FALHA"
            result_holder["sip_reason"] = str(e)
            # Tenta reconectar para o próximo ciclo
            try:
                await self.disconnect()
            except Exception:
                pass
            self._manager = None
        finally:
            try:
                self._manager.deregister_event("Hangup", on_hangup)
            except Exception:
                pass

        return (
            result_holder["status"],
            result_holder["sip_code"],
            result_holder["sip_reason"],
        )
