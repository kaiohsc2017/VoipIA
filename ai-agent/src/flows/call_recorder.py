"""
call_recorder.py — gravação em WAV e registro do chamado no backend/Jira (Módulo 1).

Extraído de jira_call_flow.py (fase 22, O3.3 da refatoração).
"""

import asyncio
import logging
import wave

from src.services import backend_client as bc
from src.services.subject_classifier import classify_subject

logger = logging.getLogger("asteriskia.flow.jira")

# Taxa de amostragem do AudioSocket (8kHz, 16bit, mono)
_SAMPLE_RATE = 8000
_BYTES_SAMPLE = 2


class CallRecorder:
    """
    Acumula respostas coletadas durante o fluxo e, ao final, grava o WAV da chamada
    e registra o chamado no backend (que abre a issue no Jira).

    `recorded_audio` é a MESMA lista compartilhada com AudioCapture e com o
    orquestrador (JiraCallFlow._recorded_audio) — inclui tanto a voz da URA (TTS)
    quanto a do cliente (captura), na ordem cronológica em que ocorreram.
    """

    def __init__(
        self,
        call_uuid: str,
        ai,
        caller_number: str,
        ura_id: int,
        recorded_audio: list[bytes],
    ):
        self.call_uuid = call_uuid
        self.ai = ai
        self.caller_number = caller_number
        self.ura_id = ura_id
        self.recorded_audio = recorded_audio
        self.collected_answers: dict[str, str] = {}
        self.transcriptions: list[str] = []

    def _write_wav_sync(self, path: str) -> None:
        """Implementação síncrona — nunca chamar direto de um contexto async
        (ver write_wav, que descarrega esta chamada para uma thread)."""
        if not self.recorded_audio:
            logger.warning("[%s] Sem áudio gravado — arquivo WAV não será criado", self.call_uuid)
            return
        pcm = b"".join(self.recorded_audio)
        with wave.open(path, 'wb') as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)       # 16-bit
            wf.setframerate(_SAMPLE_RATE)
            wf.writeframes(pcm)
        logger.info("[%s] Gravação salva: %s (%d bytes / %.1fs)",
                    self.call_uuid, path, len(pcm), len(pcm) / (_SAMPLE_RATE * _BYTES_SAMPLE))

    async def write_wav(self, path: str) -> None:
        """
        Escreve em disco a chamada completa (perguntas da URA + respostas do
        cliente, na ordem em que ocorreram) como WAV 8kHz/16bit/mono.

        Descarregada para uma thread (asyncio.to_thread) — é uma operação de
        I/O síncrona (wave/write) e o event loop é compartilhado por TODAS as
        chamadas simultâneas atendidas pelo servidor AudioSocket; sem isso, a
        cadência de 20ms/frame das demais chamadas sofre jitter enquanto esta
        grava.
        """
        await asyncio.to_thread(self._write_wav_sync, path)

    async def create_jira_issue(
        self,
        audio_path: str | None = None,
        duration_secs: int = 0,
    ) -> str | None:
        try:
            full_transcription = "\n".join(self.transcriptions)
            self.collected_answers.setdefault("description", full_transcription)
            if self.caller_number and self.caller_number != "desconhecido":
                self.collected_answers.setdefault("customfield_telefone", self.caller_number)

            subject_tag = await self.classify_subject(full_transcription)

            payload = {
                "callUuid":         self.call_uuid,
                "uraId":            self.ura_id,
                "fields":           self.collected_answers,
                "audioFilePath":    audio_path or f"/var/spool/asterisk/monitor/{self.call_uuid}.wav",
                "transcription":    full_transcription,
                "callerNumber":     self.caller_number,
                "callDurationSecs": duration_secs,
                "subjectTag":       subject_tag,
                **self.ai.usage.to_payload(),
            }
            data = await bc.post("/api/v1/calls/register", json=payload)
            return data.get("jiraIssueKey")
        except Exception as e:
            logger.error("[%s] Erro ao registrar chamado: %s", self.call_uuid, e)
            return None

    def guess_call_type(self) -> str | None:
        """Mesma heurística usada no Java (CallRecordService) para achar o campo de
        'tipo de atendimento' entre as respostas coletadas — usada aqui só para buscar
        o vocabulário de assuntos já classificados daquele tipo, nunca persistida."""
        for key, value in self.collected_answers.items():
            lowered = key.lower()
            if "tipo" in lowered or "issuetype" in lowered or "type" in lowered:
                return value
        return None

    async def classify_subject(self, full_transcription: str) -> str | None:
        """Best-effort — nunca bloqueia nem derruba o registro da chamada se falhar.
        Lógica de prompt/vocabulário em services/subject_classifier.py, reaproveitada
        também pelo backfill em lote (scripts/backfill_subject_tags.py)."""
        return await classify_subject(self.ai, full_transcription, self.guess_call_type(), log_ctx=self.call_uuid)

    @staticmethod
    def format_issue_key(key: str) -> str:
        parts = key.split("-")
        if len(parts) == 2:
            return f"{' '.join(parts[0])}, {parts[1]}"
        return key
