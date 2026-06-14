"""
main.py — Servidor TCP Audiosocket
Ponto de entrada do Agente de IA.

Escuta conexões TCP na porta configurada. Para cada chamada
recebida do Asterisk, determina o fluxo correto (URA Jira ou
Alerta Zabbix) baseado no contexto informado via UUID/canal,
e delega ao flow correspondente.
"""

import asyncio
import logging
from src.config import AUDIOSOCKET_HOST, AUDIOSOCKET_PORT
from src.protocol import read_frame
from src.flows.jira_call_flow import JiraCallFlow
from src.flows.zabbix_alert_flow import ZabbixAlertFlow
from src.services import backend_client as bc

# Configuração de logging estruturado
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("asteriskia.main")


async def _detect_flow_type(call_uuid: str) -> str:
    """
    Determina o tipo de flow a partir do UUID da chamada.

    Estratégia (em ordem de preferência):
    1. Consulta o backend: GET /api/v1/alert-calls/by-uuid/{uuid}
       → 200 = chamada de alerta Zabbix já registrada pelo AmiOriginateService
       → 404/erro = chamada Jira (flow padrão)

    O AmiOriginateService registra a chamada de alerta no banco ANTES de
    fazer o Originate via AMI, então quando o Asterisk conecta ao AudioSocket
    a entry já existe no backend.

    Args:
        call_uuid: UUID formatado (ex: "a1b2c3d4-e5f6-...") recebido no frame Audiosocket.

    Returns:
        "ZABBIX_ALERT" ou "JIRA_CALL"
    """
    try:
        await bc.get(f"/api/v1/alert-calls/by-uuid/{call_uuid}")
        logger.info(f"UUID {call_uuid} encontrado em alert-calls → ZABBIX_ALERT")
        return "ZABBIX_ALERT"
    except Exception:
        # 404 ou qualquer erro de rede → assume JiraCallFlow (padrão seguro)
        return "JIRA_CALL"


async def handle_connection(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter
) -> None:
    """
    Callback chamado para cada nova conexão Audiosocket.
    Lê o frame inicial de UUID, determina o flow e executa.
    """
    peer = writer.get_extra_info("peername")
    logger.info(f"Nova conexão Audiosocket de {peer}")

    call_uuid = None

    try:
        # O primeiro frame enviado pelo Asterisk SEMPRE é o UUID da chamada
        uuid_frame = await read_frame(reader)
        if uuid_frame is None or not uuid_frame.is_uuid:
            logger.warning("Frame inicial inválido — esperado UUID. Encerrando.")
            return

        call_uuid = uuid_frame.call_uuid
        logger.info(f"Chamada iniciada | UUID: {call_uuid}")

        # ---------------------------------------------------------
        # Determinação do Flow via backend lookup
        #
        # O protocolo AudioSocket transmite o UUID como 16 bytes binários —
        # não é possível embutir prefixos textuais nesse campo.
        # A estratégia correta é consultar o backend:
        #   - AmiOriginateService registra a chamada ANTES do Originate
        #   - GET /api/v1/alert-calls/by-uuid/{uuid} → 200 = ZABBIX_ALERT
        #   - 404 = JIRA_CALL (chamada recebida do tronco ou ramal 1000)
        #
        # Para chamadas via ramal 1001 (teste local/webrtc), o dialplan
        # seta FLOW_TYPE=ZABBIX_ALERT como variável de canal — consultável
        # via AMI GetVar se necessário no futuro.
        # ---------------------------------------------------------

        flow_type = await _detect_flow_type(call_uuid)
        logger.info(f"Flow selecionado: {flow_type} | UUID: {call_uuid}")

        if flow_type == "ZABBIX_ALERT":
            flow = ZabbixAlertFlow(call_uuid, reader, writer)
        else:
            flow = JiraCallFlow(call_uuid, reader, writer)

        await flow.execute()

    except Exception as e:
        logger.error(f"Erro na chamada {call_uuid}: {e}", exc_info=True)
    finally:
        writer.close()
        await writer.wait_closed()
        logger.info(f"Conexão encerrada | UUID: {call_uuid}")


async def main() -> None:
    """Inicializa e mantém o servidor TCP Audiosocket."""
    server = await asyncio.start_server(
        handle_connection,
        host=AUDIOSOCKET_HOST,
        port=AUDIOSOCKET_PORT
    )

    addr = server.sockets[0].getsockname()
    logger.info(f"=== AsteriskIA Agente de IA iniciado em {addr} ===")

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
