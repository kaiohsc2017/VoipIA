"""
zabbix_alert_flow.py — Fluxo de Alerta de Infraestrutura (Módulo 3)

Recebe o conteúdo do incidente Zabbix via backend,
sintetiza em voz via TTS e reproduz para o destinatário.
"""

import asyncio
import logging
import time
import httpx
from src.protocol import write_audio_paced, wait_playback_and_drain
from src.services.ai_service import AIService
from src.services import backend_client as bc

logger = logging.getLogger("asteriskia.flow.zabbix")

# Taxa de amostragem do AudioSocket (8kHz, 16bit, mono) — mesmo formato do Módulo 1
_SAMPLE_RATE  = 8000
_BYTES_SAMPLE = 2

# Buffer pós-fala: write_audio_paced já pacinga em tempo real (20ms/frame),
# então elapsed ≈ duration — o buffer só cobre jitter do event loop/rede.
_POST_SPEAK_BUFFER_SECS = 0.8


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
        self.gemini = AIService()

    async def execute(self) -> None:
        """Executa o fluxo de leitura de alerta."""
        logger.info("[%s] Iniciando fluxo de alerta Zabbix", self.call_uuid)

        # 1. Busca dados do incidente no backend
        alert_data = await self._fetch_alert_data()
        if not alert_data:
            logger.error("[%s] Dados do alerta não encontrados", self.call_uuid)
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
            f"Esta mensagem foi enviada automaticamente pelo sistema VoipIA."
        )

        # 3. TTS: sintetiza e envia o alerta por voz
        try:
            audio_pcm = await self.gemini.synthesize_speech(voice_message)
            duration = len(audio_pcm) / (_SAMPLE_RATE * _BYTES_SAMPLE)
            t_start = time.monotonic()
            ok = await write_audio_paced(self.writer, audio_pcm)
            elapsed = time.monotonic() - t_start
            if not ok:
                logger.warning("[%s] Conexão encerrada durante reprodução do alerta", self.call_uuid)
            else:
                # Mesmo padrão de espera+drenagem do Módulo 1 (jira_call_flow) —
                # antes só aguardava, sem drenar frames stale do reader.
                await wait_playback_and_drain(
                    self.reader, duration, elapsed, _POST_SPEAK_BUFFER_SECS, self.call_uuid
                )
        except Exception as e:
            logger.error("[%s] Erro ao reproduzir alerta: %s", self.call_uuid, e)

        # 4. Notifica backend que a chamada foi atendida e o alerta lido
        await self._update_call_status("ATENDIDA")
        logger.info("[%s] Fluxo de alerta Zabbix concluído", self.call_uuid)

    async def _fetch_alert_data(self) -> dict | None:
        """Busca dados do alerta no backend pelo UUID da chamada (autenticado)."""
        try:
            return await bc.get(f"/api/v1/alert-calls/by-uuid/{self.backend_uuid}")
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                logger.info("[%s] Alerta %s não encontrado no backend", self.call_uuid, self.backend_uuid)
            else:
                logger.error("[%s] Erro HTTP ao buscar dados do alerta: %s", self.call_uuid, e)
            return None
        except Exception as e:
            logger.error("[%s] Erro ao buscar dados do alerta: %s", self.call_uuid, e)
            return None

    async def _update_call_status(self, status: str) -> None:
        """Atualiza o status da chamada de alerta no backend (autenticado)."""
        try:
            await bc.patch(
                f"/api/v1/alert-calls/by-uuid/{self.backend_uuid}",
                json={"callStatus": status}
            )
        except Exception as e:
            logger.error("[%s] Erro ao atualizar status do alerta: %s", self.call_uuid, e)
