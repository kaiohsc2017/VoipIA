"""
zabbix_alert_flow.py — Fluxo de Alerta de Infraestrutura (Módulo 3)

Recebe o conteúdo do incidente Zabbix via backend,
sintetiza em voz via TTS e reproduz para o destinatário.
"""

import asyncio
import logging
from src.protocol import read_frame, write_audio
from src.services.gemini_service import GeminiService
from src.services import backend_client as bc

logger = logging.getLogger("asteriskia.flow.zabbix")


class ZabbixAlertFlow:
    """
    Orquestrador do fluxo de leitura de alerta Zabbix por telefone.

    Responsabilidades:
      1. Buscar dados do incidente no backend (pelo call_uuid)
      2. Sintetizar o conteúdo do incidente em voz (TTS)
      3. Reproduzir o alerta ao destinatário
      4. Notificar backend sobre o status da chamada

    Nota: o call_uuid recebido pelo Audiosocket tem prefixo "alert-".
    O backend armazena o UUID SEM o prefixo em asterisk_call_id,
    então precisamos strip do prefixo antes de consultar.
    """

    def __init__(
        self,
        call_uuid: str,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter
    ):
        self.call_uuid = call_uuid
        # Remove prefixo "alert-" para consultar o backend
        self.backend_uuid = call_uuid.removeprefix("alert-")
        self.reader = reader
        self.writer = writer
        self.gemini = GeminiService()

    async def execute(self) -> None:
        """Executa o fluxo de leitura de alerta."""
        logger.info(f"[{self.call_uuid}] Iniciando fluxo de alerta Zabbix")

        # 1. Busca dados do incidente no backend
        alert_data = await self._fetch_alert_data()
        if not alert_data:
            logger.error(f"[{self.call_uuid}] Dados do alerta não encontrados")
            return

        incident_summary = alert_data.get("zabbixIncidentSummary", "Incidente não especificado")
        severity = alert_data.get("zabbixSeverity", "Alta")
        host = alert_data.get("zabbixHost", "Host não identificado")

        # 2. Monta mensagem de voz clara e estruturada
        voice_message = (
            f"Atenção! Este é um alerta crítico de infraestrutura. "
            f"Severidade: {severity}. "
            f"Host afetado: {host}. "
            f"Detalhe do incidente: {incident_summary}. "
            f"Por favor, verifique imediatamente o sistema de monitoramento. "
            f"Esta mensagem foi enviada automaticamente pelo sistema AsteriskIA."
        )

        # 3. TTS: sintetiza e envia o alerta por voz
        try:
            audio_pcm = await self.gemini.synthesize_speech(voice_message)
            await write_audio(self.writer, audio_pcm)
            # Aguarda reprodução completa
            words = len(voice_message.split())
            await asyncio.sleep(max(5.0, words / 2.5))
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao reproduzir alerta: {e}")

        # 4. Notifica backend que a chamada foi atendida e o alerta lido
        await self._update_call_status("ATENDIDA")
        logger.info(f"[{self.call_uuid}] Fluxo de alerta Zabbix concluído")

    async def _fetch_alert_data(self) -> dict | None:
        """Busca dados do alerta no backend pelo UUID da chamada (autenticado)."""
        try:
            return await bc.get(f"/api/v1/alert-calls/by-uuid/{self.backend_uuid}")
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao buscar dados do alerta: {e}")
            return None

    async def _update_call_status(self, status: str) -> None:
        """Atualiza o status da chamada de alerta no backend (autenticado)."""
        try:
            await bc.patch(
                f"/api/v1/alert-calls/by-uuid/{self.backend_uuid}",
                json={"callStatus": status}
            )
        except Exception as e:
            logger.error(f"[{self.call_uuid}] Erro ao atualizar status do alerta: {e}")
