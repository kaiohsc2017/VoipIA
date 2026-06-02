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

# Configuração de logging estruturado
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("asteriskia.main")


def _detect_flow_type(call_uuid: str) -> str:
    """
    Determina o tipo de flow a partir do UUID da chamada.

    Estratégia (em ordem de preferência):
    1. Prefixo "alert-" no UUID → ZABBIX_ALERT
       (O AmiOriginateService.originateAlertCall() prefixará o UUID ao chamar)
    2. Qualquer outro UUID → JIRA_CALL

    O prefixo é definido pelo lado do backend no momento do Originate.
    O AmiOriginateService passa FLOW_TYPE=ZABBIX_ALERT como variável
    de canal, que o dialplan repassa ao Audiosocket como parte do UUID
    no formato "<flow_prefix>-<original-uuid>".

    Args:
        call_uuid: UUID bruto recebido no primeiro frame Audiosocket.

    Returns:
        "ZABBIX_ALERT" ou "JIRA_CALL"
    """
    if call_uuid.startswith("alert-"):
        return "ZABBIX_ALERT"
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
        # Determinação do Flow pelo prefixo do UUID
        #
        # O AMI passa a variável FLOW_TYPE no campo ActionID ou
        # como parte do UUID ao originar a chamada:
        #   - UUID prefixado com "alert-" → ZabbixAlertFlow
        #   - Qualquer outro              → JiraCallFlow
        #
        # Também suportamos consulta ao backend como fallback:
        # GET /api/v1/alert-calls/by-uuid/{uuid} → 200 = Zabbix alert
        # ---------------------------------------------------------

        flow_type = _detect_flow_type(call_uuid)
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
