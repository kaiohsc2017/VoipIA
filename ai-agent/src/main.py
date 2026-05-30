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
        # Determinação do Flow
        # O contexto da chamada é determinado pelo UUID prefixado
        # com o tipo de fluxo ao ser originado via AMI.
        # Por padrão, chamadas da extensão 1000 vão para o Jira,
        # e chamadas com prefixo "alerta" vão para o Zabbix.
        # Na Fase 1, implementaremos a lógica de roteamento
        # consultando o backend via REST.
        # ---------------------------------------------------------

        # TODO (Fase 1 e 3): Consultar backend para determinar o flow
        # Provisoriamente: todo fluxo usa JiraCallFlow
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
