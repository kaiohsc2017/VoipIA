"""
audio_capture.py — captura de áudio do cliente e transcrição via STT (Módulo 1).

Extraído de jira_call_flow.py (fase 22, O3.3 da refatoração). Corrige junto uma race
condition pré-existente: o VAD (webrtcvad.Vad) era um singleton de módulo mutado via
set_mode() a cada ligação — duas chamadas simultâneas pisavam uma na configuração da
outra. Agora cada AudioCapture (uma por chamada, ver JiraCallFlow.__init__) instancia
seu próprio VAD.
"""

import asyncio
import logging
import struct

import webrtcvad

from src.flows.speech_field_formatter import NOISE_PATTERNS, normalize_transcription
from src.protocol import read_frame

logger = logging.getLogger("asteriskia.flow.jira")

# Taxa de amostragem do AudioSocket (8kHz, 16bit, mono)
_SAMPLE_RATE = 8000
_BYTES_SAMPLE = 2

# Threshold RMS — usado só como fallback do VAD para frames de tamanho não-padrão.
_SILENCE_THRESHOLD = 700

# Agressividade padrão do WebRTC VAD (0-3) — sobrescrita por chamada via
# set_aggressiveness(), configurável na tela de Fluxo URA.
_VAD_AGGRESSIVENESS = 3

# Segundos de silêncio contínuo para encerrar a captura (perguntas estruturadas)
SILENCE_TIMEOUT_SECS = 2.0

# Duração máxima de captura em segundos (relógio real — garante encerramento
# independente de frame timing)
MAX_CAPTURE_PERGUNTA = 12.0  # perguntas: até 12s de relógio


def _resolve_vad_aggressiveness(raw: str | None) -> int:
    """Converte o valor configurado na tela de Fluxo URA (0-3) para o modo do VAD, com fallback seguro."""
    try:
        level = int(str(raw).strip())
    except (TypeError, ValueError):
        return _VAD_AGGRESSIVENESS
    return level if level in (0, 1, 2, 3) else _VAD_AGGRESSIVENESS


class AudioCapture:
    """
    Captura frames de áudio de uma chamada, filtra silêncio via VAD e transcreve via STT.

    Uma instância por chamada — `self.recorded_audio` é a MESMA lista compartilhada
    com o orquestrador (JiraCallFlow._recorded_audio) e com CallRecorder, para que o
    WAV final inclua tanto a voz da URA (TTS) quanto a do cliente (captura), na ordem
    cronológica em que ocorreram.
    """

    def __init__(
        self,
        call_uuid: str,
        reader: asyncio.StreamReader,
        ai,
        recorded_audio: list[bytes],
    ):
        self.call_uuid = call_uuid
        self.reader = reader
        self.ai = ai
        self.recorded_audio = recorded_audio
        self.vad = webrtcvad.Vad(_VAD_AGGRESSIVENESS)

    def set_aggressiveness(self, raw: str | None) -> None:
        """Ajusta a sensibilidade do VAD desta chamada (config vinda da tela de Fluxo URA)."""
        self.vad.set_mode(_resolve_vad_aggressiveness(raw))

    def is_speech_frame(self, payload: bytes) -> bool:
        """
        Classifica um frame de 20ms como voz humana (True) ou ruído/silêncio (False).

        Usa o WebRTC VAD em vez de um threshold de energia (RMS) fixo — o VAD
        analisa características espectrais da fala e é muito mais robusto a
        ruído ambiente, focando na voz de quem fala diretamente ao microfone em
        vez de ser enganado por som ao redor.
        """
        if len(payload) != 320:  # fora do frame padrão 20ms/8kHz — fallback por energia
            samples = struct.unpack(f"<{len(payload)//2}h", payload)
            rms = (sum(x * x for x in samples) / len(samples)) ** 0.5
            return rms >= _SILENCE_THRESHOLD
        return self.vad.is_speech(payload, _SAMPLE_RATE)

    def trim_silence(self, pcm: bytes, frame_size: int = 320) -> bytes:
        """
        Remove frames de silêncio/ruído do início e fim do PCM.
        Reduz o tamanho do áudio enviado ao STT — menos dados = resposta mais rápida.
        """
        frames = [pcm[i:i+frame_size] for i in range(0, len(pcm), frame_size) if len(pcm[i:i+frame_size]) == frame_size]
        if not frames:
            return pcm

        # Encontra primeiro frame com voz — mantém 5 frames antes (100ms) para
        # não cortar consoantes iniciais (ex: "k" de "kaio", "c" de "cinco")
        start = 0
        for i, f in enumerate(frames):
            if self.is_speech_frame(f):
                start = max(0, i - 5)
                break

        # Encontra último frame com voz
        end = len(frames)
        for i in range(len(frames) - 1, -1, -1):
            if self.is_speech_frame(frames[i]):
                end = min(len(frames), i + 3)  # mantém 3 frames depois
                break

        return b"".join(frames[start:end])

    async def capture_audio(
        self,
        silence_timeout: float = SILENCE_TIMEOUT_SECS,
        max_duration: float = MAX_CAPTURE_PERGUNTA,
    ) -> bytes:
        """
        Captura frames de áudio até silêncio ou timeout de relógio real.

        Usa time.monotonic() para garantir encerramento em max_duration segundos
        independente de overhead do asyncio ou jitter de frames WebRTC/RTP.
        """
        import time

        audio_chunks: list[bytes] = []
        frame_duration = 320 / (_SAMPLE_RATE * _BYTES_SAMPLE)  # 0.02s por frame
        silence_limit = int(silence_timeout / frame_duration)

        silence_count = 0
        t_start = time.monotonic()

        while True:
            # Limite de tempo de relógio real — imune a jitter de frames
            if time.monotonic() - t_start >= max_duration:
                logger.debug("[%s] Captura encerrada por limite de %.1fs", self.call_uuid, max_duration)
                break

            try:
                frame = await asyncio.wait_for(read_frame(self.reader), timeout=2.0)
            except asyncio.TimeoutError:
                logger.debug("[%s] Timeout de frame — encerrando captura", self.call_uuid)
                break

            if frame is None or frame.is_hangup:
                break
            if not frame.is_audio:
                continue

            payload = frame.payload
            audio_chunks.append(payload)
            self.recorded_audio.append(payload)  # acumula para gravação WAV

            if not self.is_speech_frame(payload):
                silence_count += 1
                if silence_count >= silence_limit:
                    elapsed = time.monotonic() - t_start
                    logger.debug("[%s] Silêncio detectado em %.1fs", self.call_uuid, elapsed)
                    break
            else:
                silence_count = 0

        return b"".join(audio_chunks)

    async def listen_and_transcribe(
        self,
        silence_timeout: float = SILENCE_TIMEOUT_SECS,
        max_duration: float = MAX_CAPTURE_PERGUNTA,
        hint: str = "",
        field_key: str = "",
        expected_values: str = "",
    ) -> str | None:
        """Captura áudio do cliente, remove silêncio e transcreve via STT."""
        audio = await self.capture_audio(silence_timeout=silence_timeout, max_duration=max_duration)
        if not audio:
            return None
        audio = self.trim_silence(audio)
        if len(audio) < 320 * 10:  # menos de 10 frames (~0.2s) → sem voz real
            return None
        try:
            text = await self.ai.transcribe(audio, hint=hint)
            if not text:
                return None
            # Filtra transcrições de ruído/música — o STT captou o áudio da URA
            text_lower = text.lower().strip()
            if any(p in text_lower for p in NOISE_PATTERNS):
                logger.debug("[%s] STT filtrou ruído: %r", self.call_uuid, text)
                return None
            text = normalize_transcription(text, field_key, expected_values)
            logger.info("[%s] STT[%s]: %r", self.call_uuid, field_key or "?", text)
            return text
        except Exception as e:
            logger.error("[%s] Erro STT: %s", self.call_uuid, e)
            return None
