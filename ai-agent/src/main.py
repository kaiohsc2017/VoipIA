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
import httpx
from src.config import AUDIOSOCKET_HOST, AUDIOSOCKET_PORT
from src.protocol import read_frame
from src.flows.jira_call_flow import JiraCallFlow
from src.flows.zabbix_alert_flow import ZabbixAlertFlow
from src.services import backend_client as bc
from src.services.ai_service import AIService
from src.services.audio_cache import audio_cache

# Configuração de logging estruturado
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("asteriskia.main")

# Referências das tasks de background — evita que o GC colete a task antes de
# terminar e exceções sumam em silêncio (mesmo padrão do agents-platform/executor.py).
_background_tasks: set[asyncio.Task] = set()


def _spawn_background_task(coro) -> asyncio.Task:
    """Cria uma task de background mantendo referência forte até ela terminar."""
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)
    return task


async def _sync_audio_cache() -> None:
    """
    Sincroniza o cache de áudio TTS com os textos de TODAS as URAs ativas.

    Chamado no startup e a cada 60s. Gera PCM apenas para textos sem cache;
    textos já presentes são ignorados (cache hit imediato).
    """
    try:
        uras = await bc.get("/api/v1/uras")
        if not isinstance(uras, list):
            return

        texts: list[str] = ["Obrigado! Estou registrando seu chamado. Aguarde um momento."]

        for ura in uras:
            if not ura.get("active", True):
                continue
            ura_id = ura["id"]
            try:
                settings_raw, questions_raw = await asyncio.gather(
                    bc.get(f"/api/v1/uras/{ura_id}/settings"),
                    bc.get(f"/api/v1/uras/{ura_id}/questions"),
                )
            except Exception as e:
                logger.warning("Falha ao buscar settings/questions da URA %s: %s", ura_id, e)
                continue
            if isinstance(settings_raw, list):
                texts += [item["value"] for item in settings_raw if item.get("value", "").strip()]
            if isinstance(questions_raw, list):
                texts += [q["question_text"] for q in questions_raw if q.get("question_text", "").strip()]

        if texts:
            await audio_cache.warm_up(texts, AIService())
    except Exception as e:
        logger.warning("Sincronização do cache de áudio falhou: %s — continuando sem cache", e)


async def _cache_refresh_loop() -> None:
    """Warm-up inicial + refresh periódico do cache de áudio a cada 60 segundos."""
    await asyncio.sleep(10)  # aguarda backend estar pronto
    await _sync_audio_cache()
    while True:
        await asyncio.sleep(60)
        await _sync_audio_cache()


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
    except httpx.HTTPStatusError as e:
        # 404 é o caso normal/esperado (chamada não é de alerta Zabbix) — sem log de erro
        if e.response.status_code != 404:
            logger.warning(
                "Backend retornou %d ao consultar alert-calls para UUID %s — assumindo JIRA_CALL",
                e.response.status_code, call_uuid,
            )
        return "JIRA_CALL"
    except (httpx.ConnectError, httpx.TimeoutException) as e:
        logger.error(
            "Falha de rede/timeout ao consultar alert-calls para UUID %s (%s) — assumindo JIRA_CALL",
            call_uuid, e,
        )
        return "JIRA_CALL"
    except Exception as e:
        logger.error(
            "Erro inesperado ao consultar alert-calls para UUID %s (%s) — assumindo JIRA_CALL",
            call_uuid, e,
        )
        return "JIRA_CALL"


DEFAULT_URA_ID = 1  # URA legada (service desk, ramal 1000) — fallback seguro


async def _resolve_ura_id(call_uuid: str) -> int:
    """
    Resolve qual URA conduz esta chamada.

    O dialplan do Asterisk registra a correlação uuid→ramal via CURL logo
    após gerar o UUID (ver extensions.conf, faixa _2XXX) e ANTES de conectar
    ao AudioSocket — mesmo princípio do lookup usado para ZABBIX_ALERT, só
    que quem registra aqui é o próprio dialplan, não o backend Java.

    Se não houver registro (ex: ramal 1000 legado, ou o CURL falhou/expirou),
    cai na URA padrão — nunca derruba a chamada por isso.
    """
    try:
        result = await bc.get(f"/api/v1/internal/ura-routing/by-uuid/{call_uuid}")
        return int(result)
    except Exception:
        return DEFAULT_URA_ID


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
            # O número do chamador é coletado durante o fluxo da URA (perguntas)
            # e consolidado na transcrição. O protocolo AudioSocket transmite
            # apenas o UUID binário — não há canal para o CALLER_NUM aqui.
            ura_id = await _resolve_ura_id(call_uuid)
            logger.info(f"URA resolvida: id={ura_id} | UUID: {call_uuid}")
            flow = JiraCallFlow(call_uuid, reader, writer, ura_id=ura_id)

        await flow.execute()

    except (BrokenPipeError, ConnectionResetError):
        logger.warning(f"Conexão encerrada pelo Asterisk | UUID: {call_uuid}")
    except Exception as e:
        logger.error(f"Erro na chamada {call_uuid}: {e}", exc_info=True)
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except (BrokenPipeError, ConnectionResetError):
            pass  # Asterisk já encerrou a conexão — esperado
        logger.info(f"Conexão encerrada | UUID: {call_uuid}")


async def main() -> None:
    """Inicializa e mantém o servidor TCP Audiosocket."""
    server = await asyncio.start_server(
        handle_connection,
        host=AUDIOSOCKET_HOST,
        port=AUDIOSOCKET_PORT
    )

    addr = server.sockets[0].getsockname()
    logger.info(f"=== VoipIA Agente de IA iniciado em {addr} ===")

    _spawn_background_task(_cache_refresh_loop())  # warm-up + refresh em background

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
